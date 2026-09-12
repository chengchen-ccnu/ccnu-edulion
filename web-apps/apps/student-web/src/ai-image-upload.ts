import type { MediaAsset, MediaUploadGrant } from "@edu/api-client";
import { accountSecurityApi } from "./account-security-api";

const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const MAX_BYTES = 10 * 1024 * 1024;

/**
 * 复用学生端既有 Media 上传会话完成 AI 题图直传；输入只接受三种图片且不超过 10 MiB，
 * 输出 READY 的稳定 mediaId。短期 OSS 地址仅交给 XHR，离页中止不会被保存或写入消息。
 */
export async function uploadAiQuestionImage(file: File, signal: AbortSignal,
    onStatus: (message: string) => void): Promise<string> {
  if (!ALLOWED_TYPES.has(file.type)) throw new Error("只支持 JPG、PNG 或 WebP 题图。");
  if (file.size > MAX_BYTES) throw new Error("题图不能超过 10 MiB。");
  onStatus("正在计算图片校验值…");
  const digest = await sha256(file, signal);
  let uploadId = "";
  try {
    const session = await accountSecurityApi.createMediaUpload({ clientRequestId: crypto.randomUUID(),
      purpose: "AI_QUESTION_IMAGE", fileName: file.name, size: file.size, mimeType: file.type, sha256: digest });
    uploadId = session.uploadId;
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (session.mediaId) return await waitReady(await accountSecurityApi.getMedia(session.mediaId, signal), signal, onStatus);
    if (session.mode !== "SINGLE_PUT" || !session.uploadGrant) throw new Error("当前题图上传模式不受支持。");
    onStatus("正在通过短期授权直传题图…");
    await put(session.uploadGrant, file, signal);
    const asset = await accountSecurityApi.completeUpload(session.uploadId, { sha256: digest, parts: [] });
    const mediaId = await waitReady(asset, signal, onStatus);
    uploadId = "";
    return mediaId;
  } catch (error) {
    if (uploadId) await accountSecurityApi.cancelUpload(uploadId).catch(() => {});
    throw error;
  }
}

async function waitReady(asset: MediaAsset, signal: AbortSignal,
    onStatus: (message: string) => void): Promise<string> {
  let current = asset;
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (current.purpose !== "AI_QUESTION_IMAGE" || current.scope !== "USER_PRIVATE") throw new Error("题图用途或归属校验失败。");
    if (current.status === "READY") return current.mediaId;
    if (["FAILED", "REJECTED", "EXPIRED", "DELETING", "DELETED"].includes(current.status)) throw new Error("题图未通过媒体校验。");
    onStatus("题图已上传，正在等待安全校验…");
    await delay(750, signal); current = await accountSecurityApi.getMedia(current.mediaId, signal);
  }
  throw new Error("题图处理超时，请稍后重试。");
}

async function sha256(file: File, signal: AbortSignal): Promise<string> {
  if (signal.aborted) throw new DOMException("Aborted", "AbortError");
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, "0")).join("");
}

function put(grant: MediaUploadGrant, file: File, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest(); const abort = () => xhr.abort();
    xhr.open(grant.method, grant.url); Object.entries(grant.headers).forEach(([key, value]) => xhr.setRequestHeader(key, value));
    xhr.onload = () => { signal.removeEventListener("abort", abort); xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error("题图直传失败。")); };
    xhr.onerror = () => reject(new TypeError("题图上传网络中断。")); xhr.onabort = () => reject(new DOMException("Aborted", "AbortError"));
    signal.addEventListener("abort", abort, { once: true }); xhr.send(file);
  });
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => { const timer = setTimeout(resolve, ms); signal.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); }, { once: true }); });
}
