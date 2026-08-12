/**
 * T10 浏览器验收专用最小 Gateway Fixture。
 * 只提供六组页面所需的合成 teacher/题目/导入投影；不读取真实题库，不创建数据库、媒体对象或消息。
 */
import { createServer } from "node:http";

const questionId = "00000000-0000-4000-8000-000000000101";
const versionId = "00000000-0000-4000-8000-000000000201";
const oldVersionId = "00000000-0000-4000-8000-000000000200";
const pointId = "00000000-0000-4000-8000-000000000301";
const taskId = "00000000-0000-4000-8000-000000000401";
const itemId = "00000000-0000-4000-8000-000000000501";
const timestamp = "2026-08-12T12:00:00Z";

const knowledgePoint = { id: pointId, code: "JUNIOR_MATH_ALGEBRA", name: "整式与方程", parentId: null,
  stage: "JUNIOR", grade: "SEVEN", subject: "MATHEMATICS", sortOrder: 1, enabled: true, revision: 0, children: [] };
const currentVersion = { id: versionId, versionNumber: 2, type: "COMPOSITE", difficulty: "MEDIUM", stage: "JUNIOR",
  grade: "SEVEN", subject: "MATHEMATICS", stem: "已知 $a^2+b^2=c^2$，完成下列问题。", options: [], answers: ["见子题"],
  analysis: "使用 **勾股定理**。<sup>安全上标</sup>", knowledgePoints: [knowledgePoint], media: [], legacySource: null,
  children: [{ questionId: "00000000-0000-4000-8000-000000000102", versionId: "00000000-0000-4000-8000-000000000202",
    versionNumber: 1, sequence: 1, type: "SINGLE_CHOICE", difficulty: "EASY", stage: "JUNIOR", grade: "SEVEN",
    subject: "MATHEMATICS", stem: "当 $a=3,b=4$ 时，$c$ 等于？", options: [{ key: "A", content: "5" }, { key: "B", content: "7" }],
    answers: ["A"], analysis: "$3^2+4^2=5^2$", knowledgePoints: [knowledgePoint], media: [], createdAt: timestamp }], createdAt: timestamp };
const oldVersion = { ...currentVersion, id: oldVersionId, versionNumber: 1, stem: "历史版本：计算 $3^2+4^2$。",
  children: currentVersion.children.map(child => ({ ...child, versionId: "00000000-0000-4000-8000-000000000203" })) };
const question = { id: questionId, status: "DRAFT", revision: 1, currentVersion, createdAt: timestamp, updatedAt: timestamp };
const reviewItem = { id: itemId, revision: 0, externalId: "synthetic/one", outcome: "NEEDS_REVIEW", questionId: null,
  sourceSystem: "LEGACY_BROWSER_V1", originalDifficulty: "中等", pathEdition: "合成版", jsonEdition: "合成版",
  pathGrade: "七年级", jsonGrade: "七年级", issues: [{ code: "EMBEDDED_OPTIONS_AMBIGUOUS", message: "合成样本的内嵌选项需要人工确认。" }],
  resolution: null, createdAt: timestamp, updatedAt: timestamp };
const importTask = { id: taskId, sourceMediaId: null, sourceFormat: "LEGACY_JSON", sourceSystem: "LEGACY_BROWSER_V1",
  dryRun: true, maxItems: 1, status: "PARTIAL_SUCCESS", progress: 100, totalRows: 1, processedRows: 1, successCount: 0,
  reviewCount: 1, resolvedCount: 0, skippedCount: 0, failureCount: 0, errors: [], failureCode: null, failureMessage: null,
  retainUntil: "2026-08-13T12:00:00Z", sourceReleasedAt: null, createdAt: timestamp, updatedAt: timestamp };

createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", "http://127.0.0.1:4012");
  response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:5185");
  response.setHeader("Access-Control-Allow-Credentials", "true");
  response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type");
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
  if (request.method === "OPTIONS") return response.writeHead(204).end();
  if (url.pathname === "/api/v1/auth/refresh") return json(response, { tokenType: "Bearer", accessToken: "fixture-token", expiresIn: 900 });
  if (url.pathname === "/api/v1/users/me") return json(response, { id: "00000000-0000-4000-8000-000000000001",
    displayName: "teacher_test_t10", avatarUrl: null, roles: ["TEACHER"], permissions: ["teaching:access"] });
  if (url.pathname === "/api/v1/teacher/knowledge-points") return json(response, [knowledgePoint]);
  if (url.pathname === "/api/v1/teacher/questions" && request.method === "GET" && url.searchParams.get("keyword") === "rate-limit") {
    return apiError(response, 429, "GATEWAY.RATE_LIMITED", "合成限流响应", url.pathname);
  }
  if (url.pathname === "/api/v1/teacher/questions" && request.method === "GET" && url.searchParams.get("keyword") === "server-error") {
    return apiError(response, 503, "COMMON.SERVICE_UNAVAILABLE", "合成服务不可用响应", url.pathname);
  }
  if (url.pathname === "/api/v1/teacher/questions" && request.method === "GET") return json(response, { items: [{ id: questionId,
    status: "DRAFT", revision: 1, currentVersionId: versionId, versionNumber: 2, type: "COMPOSITE", difficulty: "MEDIUM",
    grade: "SEVEN", stemPreview: "已知勾股关系，完成下列问题。", knowledgePointIds: [pointId], createdAt: timestamp, updatedAt: timestamp }],
    page: Number(url.searchParams.get("page") ?? 1), size: Number(url.searchParams.get("size") ?? 20), total: 1 });
  if (url.pathname === "/api/v1/teacher/questions" && request.method === "POST") return json(response, question, 201);
  if (url.pathname === `/api/v1/teacher/questions/${questionId}` && request.method === "GET") return json(response, question);
  if (url.pathname === `/api/v1/teacher/questions/${questionId}` && request.method === "PUT") {
    const body = await readJson(request);
    if (body?.version?.stem === "触发422") {
      return json(response, errorBody("COMMON.VALIDATION_FAILED", "题干校验失败", url.pathname,
        [{ field: "version.stem", reason: "题干校验失败" }]), 422);
    }
    return apiError(response, 409, "QUESTION.REVISION_CONFLICT", "题目已被更新，请刷新后重试", url.pathname);
  }
  if (url.pathname === `/api/v1/teacher/questions/${questionId}/status`) return json(response, { ...question, status: "ACTIVE", revision: 2 });
  if (url.pathname === `/api/v1/teacher/questions/${questionId}/versions`) return json(response, { items: [
    { id: versionId, versionNumber: 2, type: "COMPOSITE", difficulty: "MEDIUM", stemPreview: "当前版本", childCount: 1, createdAt: timestamp, current: true },
    { id: oldVersionId, versionNumber: 1, type: "COMPOSITE", difficulty: "MEDIUM", stemPreview: "历史版本", childCount: 1, createdAt: "2026-08-11T12:00:00Z", current: false }],
    page: Number(url.searchParams.get("page") ?? 1), size: Number(url.searchParams.get("size") ?? 10), total: 2 });
  if (url.pathname === `/api/v1/teacher/questions/${questionId}/versions/${oldVersionId}`) return json(response, oldVersion);
  if (url.pathname === "/api/v1/teacher/question-imports") return json(response, { items: [importTask], page: 1, size: 20, total: 1 });
  if (url.pathname === `/api/v1/teacher/question-imports/${taskId}`) return json(response, importTask);
  if (url.pathname === `/api/v1/teacher/question-imports/${taskId}/items`) return json(response, { items: [reviewItem], page: 1, size: 20, total: 1 });
  if (url.pathname === `/api/v1/teacher/question-imports/${taskId}/items/${itemId}`) return json(response, reviewItem);
  if (url.pathname === `/api/v1/teacher/question-imports/${taskId}/items/${itemId}/resolution`) return apiError(response, 409,
    "QUESTION_IMPORT.ITEM_REVISION_CONFLICT", "复核项已被更新，请刷新后重试", url.pathname);
  if (url.pathname === `/api/v1/teacher/legacy-question-imports/${taskId}/created-resources` && request.method === "DELETE") {
    return json(response, { importTaskId: taskId, questionsDeleted: 0, sourceReferencesDeleted: 1, mediaReferencesReleased: 0, alreadyCleaned: false });
  }
  return apiError(response, 404, "QUESTION.NOT_FOUND", "题目或任务不存在", url.pathname);
}).listen(4012, "127.0.0.1");

function json(response, body, status = 200) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(body));
}
function apiError(response, status, code, message, path) {
  return json(response, errorBody(code, message, path), status);
}
function errorBody(code, message, path, fieldErrors = []) {
  return { code, message, traceId: "t10-fixture-trace", timestamp, path, fieldErrors };
}
async function readJson(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}
