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
    const parts = relativePath.split("/");
    items.push({
      externalId: relativePath.replace(/\.json$/i, "").slice(0, 160),
      relativePath,
      pathEdition: parts.length >= 3 ? parts[0] : "UNKNOWN",
      pathGrade: parts.length >= 3 ? parts[1] : "UNKNOWN",
      contentSha256: bytesToHex(sha256(bytes)),
      json: parsed as Record<string, never>
    });
  }
  return items;
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
