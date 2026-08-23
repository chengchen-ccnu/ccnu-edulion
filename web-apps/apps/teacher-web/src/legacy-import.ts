import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import type { LegacyQuestionImportItem } from "@edu/api-client";

export const LEGACY_BATCH_LIMIT = 20;
export const LEGACY_TASK_LIMIT = 1000;
export const LEGACY_FILE_BYTE_LIMIT = 256 * 1024;

/**
 * 将浏览器显式选择的 JSON 文件转为有界批次项。
 * 只提交规范化相对路径与 SHA-256，不读取或提交客户端绝对路径；单文件超过 256 KiB、非对象 JSON 或越界路径会拒绝。
 */
export async function readLegacyFiles(files: readonly File[], signal: AbortSignal): Promise<LegacyQuestionImportItem[]> {
  if (!files.length) throw new Error("LEGACY_NO_FILES");
  if (files.length > LEGACY_TASK_LIMIT) throw new Error("LEGACY_TASK_LIMIT");
  const items: LegacyQuestionImportItem[] = [];
  for (const file of files) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (file.size > LEGACY_FILE_BYTE_LIMIT) throw new Error(`LEGACY_FILE_TOO_LARGE:${file.name}`);
    const relativePath = normalizeRelativePath(file.webkitRelativePath || file.name);
    const bytes = new Uint8Array(await file.arrayBuffer());
    const parsed = JSON.parse(new TextDecoder().decode(bytes)) as unknown;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(`LEGACY_JSON_OBJECT_REQUIRED:${file.name}`);
    const json = parsed as Record<string, unknown>;
    const { pathEdition, pathGrade } = legacyPathMetadata(relativePath);
    items.push({
      externalId: legacyExternalId(json, file.name),
      relativePath,
      pathEdition,
      pathGrade,
      contentSha256: bytesToHex(sha256(bytes)),
      json: json as Record<string, never>
    });
  }
  return items;
}

/**
 * 生成后端幂等校验使用的历史题目 ID。
 * 输入优先读取 JSON 的 exerciseID；缺失时才退回文件名。输出必须原样满足 160 字符契约，禁止截断后与 JSON 内 ID 不一致。
 */
function legacyExternalId(json: Record<string, unknown>, fileName: string): string {
  const embedded = json.exerciseID;
  const value = typeof embedded === "string" || typeof embedded === "number" || typeof embedded === "boolean"
    ? String(embedded)
    : "";
  const fallback = fileName.replace(/\.json$/i, "");
  const externalId = value || fallback;
  if (!externalId || externalId.trim() !== externalId || externalId.length > 160) {
    throw new Error(`LEGACY_EXTERNAL_ID_INVALID:${fileName}`);
  }
  return externalId;
}

/**
 * 从浏览器提供的相对路径识别教材版本和年级。
 * 输入可从“题库”、教材版本、年级目录或单文件任一层级开始；输出只含目录原文，不上传本机绝对路径。
 */
function legacyPathMetadata(relativePath: string): Pick<LegacyQuestionImportItem, "pathEdition" | "pathGrade"> {
  const directories = relativePath.split("/").slice(0, -1);
  const gradeIndex = directories.findIndex(part => /[七八九]年级/.test(part));
  if (gradeIndex >= 0) {
    const pathGrade = directories[gradeIndex];
    const earlierEdition = directories.slice(0, gradeIndex).reverse()
      .find(part => part.includes("版") && part !== "题库");
    const pathEdition = earlierEdition ?? (pathGrade.includes("版") ? pathGrade : "UNKNOWN");
    return { pathEdition, pathGrade };
  }
  // 保留合成 Fixture 和其他“版本/年级/文件”来源的既有通用语义。
  if (directories.length === 2) return { pathEdition: directories[0], pathGrade: directories[1] };
  return { pathEdition: "UNKNOWN", pathGrade: "UNKNOWN" };
}

/** 生成非路径的根标签指纹；输入是教师填写的普通标签，输出只用于任务幂等。 */
export function rootFingerprint(sourceSystem: string, rootLabel: string): string {
  return bytesToHex(sha256(new TextEncoder().encode(`${sourceSystem}:${rootLabel.trim()}`)));
}

/** 将最多 1000 项严格切为每批最多 20 项，最后一批由调用者显式标记 finalBatch。 */
export function legacyBatches(items: readonly LegacyQuestionImportItem[]): LegacyQuestionImportItem[][] {
  if (items.length > LEGACY_TASK_LIMIT) throw new Error("LEGACY_TASK_LIMIT");
  const batches: LegacyQuestionImportItem[][] = [];
  for (let index = 0; index < items.length; index += LEGACY_BATCH_LIMIT) {
    batches.push(items.slice(index, index + LEGACY_BATCH_LIMIT));
  }
  return batches;
}

function normalizeRelativePath(value: string): string {
  const normalized = value.replace(/\\/g, "/").replace(/^\/+/, "");
  if (!normalized || normalized.length > 500 || normalized.split("/").some(part => !part || part === ".." || part === ".")) {
    throw new Error("LEGACY_RELATIVE_PATH_INVALID");
  }
  return normalized;
}
