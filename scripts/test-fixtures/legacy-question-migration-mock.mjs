import http from "node:http";

/**
 * 为 PowerShell 全量迁移脚本提供无数据库、无 OSS 的本地 HTTP 验证端点。
 * 输入是脚本产生的任务/批次请求，输出只返回计数和稳定虚构 UUID；不会记录题干、答案或原始 JSON。
 */
const port = Number(process.env.LEGACY_MIGRATION_MOCK_PORT ?? "18080");
const failOnceBatchNumber = Number(process.env.LEGACY_MIGRATION_MOCK_FAIL_ONCE_BATCH ?? "0");
const tasksByRequest = new Map();
const tasksById = new Map();
const batches = new Map();
let nextTask = 1;
let batchRequestNumber = 0;
let injectedFailure = false;

function uuid(value) {
  return `00000000-0000-4000-8000-${String(value).padStart(12, "0")}`;
}

async function requestJson(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function reply(response, status, body) {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://127.0.0.1:${port}`);
    if (request.method === "POST" && url.pathname === "/api/v1/teacher/legacy-question-imports") {
      const body = await requestJson(request);
      const existing = tasksByRequest.get(body.clientRequestId);
      if (existing) return reply(response, 200, existing);
      if (body.maxItems < 1 || body.maxItems > 1000) return reply(response, 422, { code: "COMMON.VALIDATION_FAILED" });
      const task = { id: uuid(nextTask++), dryRun: body.dryRun, maxItems: body.maxItems };
      tasksByRequest.set(body.clientRequestId, task);
      tasksById.set(task.id, task);
      return reply(response, 201, task);
    }

    const batchMatch = url.pathname.match(/^\/api\/v1\/teacher\/legacy-question-imports\/([^/]+)\/batches$/);
    if (request.method === "POST" && batchMatch) {
      const task = tasksById.get(batchMatch[1]);
      if (!task) return reply(response, 404, { code: "QUESTION_IMPORT.NOT_FOUND" });
      const body = await requestJson(request);
      const replay = batches.get(body.batchId);
      if (replay) return reply(response, 200, { ...replay, replayed: true });
      if (!Array.isArray(body.items) || body.items.length < 1 || body.items.length > 20) {
        return reply(response, 422, { code: "COMMON.VALIDATION_FAILED" });
      }
      const invalidItem = body.items.some(item => !item || item.externalId !== String(item.json?.exerciseID ?? item.externalId)
        || typeof item.relativePath !== "string" || /^[A-Za-z]:|^\//.test(item.relativePath) || item.relativePath.includes("../")
        || !item.pathEdition || !item.pathGrade);
      if (invalidItem) return reply(response, 422, { code: "COMMON.VALIDATION_FAILED" });
      batchRequestNumber += 1;
      if (!injectedFailure && failOnceBatchNumber > 0 && batchRequestNumber === failOnceBatchNumber) {
        injectedFailure = true;
        return reply(response, 503, { code: "MOCK.RETRYABLE_FAILURE" });
      }
      const count = body.items.length;
      const result = {
        importTaskId: task.id,
        batchId: body.batchId,
        replayed: false,
        finalBatch: body.finalBatch,
        processedCount: count,
        importedCount: task.dryRun ? 0 : count,
        validCount: task.dryRun ? count : 0,
        reviewCount: 0,
        skippedCount: 0,
        failedCount: 0,
        items: []
      };
      batches.set(body.batchId, result);
      return reply(response, 200, result);
    }

    const cleanupMatch = url.pathname.match(/^\/api\/v1\/teacher\/legacy-question-imports\/([^/]+)\/created-resources$/);
    if (request.method === "DELETE" && cleanupMatch) {
      if (!tasksById.has(cleanupMatch[1])) return reply(response, 404, { code: "QUESTION_IMPORT.NOT_FOUND" });
      return reply(response, 200, {
        importTaskId: cleanupMatch[1], questionsDeleted: 1, sourceReferencesDeleted: 1,
        mediaReferencesReleased: 0, alreadyCleaned: false
      });
    }
    reply(response, 404, { code: "NOT_FOUND" });
  } catch {
    reply(response, 500, { code: "MOCK.INVALID_REQUEST" });
  }
});

server.listen(port, "127.0.0.1");
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
