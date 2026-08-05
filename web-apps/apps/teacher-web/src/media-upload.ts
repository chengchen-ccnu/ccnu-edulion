import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { GatewayRequestError } from "@edu/api-client";
import type {
  CompletedMediaPart,
  MediaAsset,
  MediaPurpose,
  MediaUploadGrant,
  MediaUploadSession
} from "@edu/api-client";

import type { TeacherMediaApi } from "./media-api";

export type MediaUploadStage = "IDLE" | "RESTORING" | "NEEDS_FILE" | "HASHING" | "CREATING"
  | "UPLOADING" | "COMPLETING" | "PROCESSING" | "READY" | "FAILED" | "CANCELED";

export interface MediaUploadViewState {
  stage: MediaUploadStage;
  progress: number;
  uploadedBytes: number;
  totalBytes: number;
  statusLabel: string;
  errorMessage: string;
  uploadId?: string;
  mediaId?: string;
  fileName?: string;
}

interface UploadCheckpoint {
  uploadId: string;
  clientRequestId: string;
  purpose: MediaPurpose;
  fileName: string;
  size: number;
  mimeType: string;
  sha256: string;
  expiresAt: string;
  mediaId?: string;
}

export interface MediaUploaderOptions {
  api: TeacherMediaApi;
  purpose: MediaPurpose;
  checkpointKey: string;
  onChange: (state: MediaUploadViewState) => void;
  storage?: Storage;
  putObject?: typeof putObject;
  sleep?: (milliseconds: number, signal: AbortSignal) => Promise<void>;
  now?: () => number;
  pollIntervalMs?: number;
  pollTimeoutMs?: number;
  multipartConcurrency?: number;
}

/**
 * 教师媒体上传状态机；输入是本地 File 和服务端上传会话，输出仅为 READY mediaId 与页面状态。
 * 文件字节只发往授权 URL；检查点只保存稳定会话字段，不保存 OSS URL、请求头或访问令牌。
 * 取消会中断哈希/直传/轮询并精确取消当前服务端会话；dispose 只释放本页资源以保留可恢复会话。
 */
export class MediaUploader {
  private readonly api: TeacherMediaApi;
  private readonly purpose: MediaPurpose;
  private readonly checkpointKey: string;
  private readonly onChange: (state: MediaUploadViewState) => void;
  private readonly storage?: Storage;
  private readonly uploadPut: typeof putObject;
  private readonly sleep: (milliseconds: number, signal: AbortSignal) => Promise<void>;
  private readonly now: () => number;
  private readonly pollIntervalMs: number;
  private readonly pollTimeoutMs: number;
  private readonly concurrency: number;
  private abortController: AbortController | null = null;
  private running: Promise<string> | null = null;
  private checkpoint: UploadCheckpoint | null = null;
  private state: MediaUploadViewState = initialState();

  constructor(options: MediaUploaderOptions) {
    this.api = options.api;
    this.purpose = options.purpose;
    this.checkpointKey = options.checkpointKey;
    this.onChange = options.onChange;
    this.storage = options.storage ?? globalThis.sessionStorage;
    this.uploadPut = options.putObject ?? putObject;
    this.sleep = options.sleep ?? abortableDelay;
    this.now = options.now ?? Date.now;
    this.pollIntervalMs = options.pollIntervalMs ?? 2_000;
    this.pollTimeoutMs = options.pollTimeoutMs ?? 10 * 60_000;
    this.concurrency = Math.max(1, Math.min(4, options.multipartConcurrency ?? 3));
    this.emit();
  }

  get snapshot(): MediaUploadViewState { return { ...this.state }; }

  /** 恢复同一标签页保存的服务端会话；浏览器没有本地 File 时明确要求重新选择，不伪造续传。 */
  async restore(): Promise<void> {
    const checkpoint = readCheckpoint(this.storage, this.checkpointKey, this.purpose);
    if (!checkpoint) return;
    this.checkpoint = checkpoint;
    this.patch({ stage: "RESTORING", statusLabel: "正在恢复服务端上传会话…", fileName: checkpoint.fileName });
    const controller = new AbortController();
    this.abortController = controller;
    try {
      const session = await this.api.getUpload(checkpoint.uploadId, controller.signal);
      this.syncCheckpoint(session);
      if (session.mediaId) {
        const asset = await this.api.getMedia(session.mediaId, controller.signal);
        await this.consumeAsset(asset, controller.signal);
      } else if (isSessionTerminal(session.status)) {
        this.fail(`上传会话已${session.status === "CANCELED" ? "取消" : "失效"}，请重新选择文件。`, true);
      } else {
        this.patch({ stage: "NEEDS_FILE", progress: 0, statusLabel: "已恢复服务端会话，请重新选择同一文件继续。",
          uploadId: session.uploadId, totalBytes: session.size, fileName: session.fileName });
      }
    } catch (error) {
      if (!controller.signal.aborted) this.fail(uploadErrorMessage(error, "上传会话恢复失败，请重新选择文件。"), true);
    } finally {
      if (this.abortController === controller) this.abortController = null;
    }
  }

  /** 上传所选文件；重复点击复用同一 Promise 和 clientRequestId，不创建重复会话。 */
  upload(file: File): Promise<string> {
    if (this.running) return this.running;
    const controller = new AbortController();
    this.abortController = controller;
    this.running = this.runUpload(file, controller.signal).finally(() => {
      this.running = null;
      if (this.abortController === controller) this.abortController = null;
    });
    return this.running;
  }

  /** 用户显式取消当前未完成会话；服务端取消失败仍保留检查点，便于稍后继续或重试取消。 */
  async cancel(): Promise<void> {
    this.abortController?.abort();
    const uploadId = this.checkpoint?.uploadId;
    if (uploadId) {
      try {
        await this.api.cancel(uploadId);
        this.clearCheckpoint();
      } catch (error) {
        this.fail(uploadErrorMessage(error, "本地上传已停止，但服务端会话取消失败，请稍后重试。"), false);
        return;
      }
    }
    this.patch({ ...initialState(), stage: "CANCELED", statusLabel: "上传已取消。" });
  }

  /** 清理页面内请求，不取消仍可恢复的服务端会话。 */
  dispose(): void {
    this.abortController?.abort();
    this.abortController = null;
  }

  clearCheckpoint(): void {
    this.storage?.removeItem(this.checkpointKey);
    this.checkpoint = null;
  }

  private async runUpload(file: File, signal: AbortSignal): Promise<string> {
    this.patch({ stage: "HASHING", progress: 0, uploadedBytes: 0, totalBytes: file.size,
      fileName: file.name, errorMessage: "", statusLabel: "正在分块计算 SHA-256…" });
    try {
      const digest = await hashFile(file, signal, processed => this.patch({ progress: ratio(processed, file.size) }));
      let session = await this.resolveSession(file, digest, signal);
      this.syncCheckpoint(session);
      if (session.mediaId) return await this.pollReady(session.mediaId, signal);
      this.patch({ stage: "UPLOADING", progress: 0, uploadedBytes: 0, totalBytes: file.size,
        statusLabel: session.mode === "MULTIPART" ? "正在分片直传 OSS…" : "正在直传 OSS…" });
      const parts = session.mode === "MULTIPART"
        ? await this.uploadMultipart(file, session, signal)
        : await this.uploadSingle(file, session, signal);
      this.patch({ stage: "COMPLETING", progress: 1, uploadedBytes: file.size, statusLabel: "正在校验上传结果…" });
      const asset = await withFiniteRetry(() => this.api.complete(session.uploadId, { sha256: digest, parts }, signal), signal, this.sleep);
      return await this.consumeAsset(asset, signal);
    } catch (error) {
      if (signal.aborted) {
        this.patch({ stage: "CANCELED", statusLabel: "上传已停止。", errorMessage: "" });
        throw error;
      }
      this.fail(uploadErrorMessage(error, "媒体上传失败，请检查文件后有限重试。"), false);
      throw error;
    }
  }

  private async resolveSession(file: File, digest: string, signal: AbortSignal): Promise<MediaUploadSession> {
    if (this.checkpoint) {
      const sameFile = this.checkpoint.fileName === file.name && this.checkpoint.size === file.size
        && this.checkpoint.mimeType === normalizedMime(file) && this.checkpoint.sha256.toLowerCase() === digest;
      if (!sameFile) throw new Error("RESUME_FILE_MISMATCH");
      const existing = await this.api.getUpload(this.checkpoint.uploadId, signal);
      if (Date.parse(existing.expiresAt) <= this.now() || isSessionTerminal(existing.status)) {
        throw new Error("UPLOAD_SESSION_EXPIRED");
      }
      return existing;
    }
    this.patch({ stage: "CREATING", statusLabel: "正在申请短期上传授权…" });
    const request = { clientRequestId: crypto.randomUUID(), purpose: this.purpose,
      fileName: file.name, size: file.size, mimeType: normalizedMime(file), sha256: digest };
    return await withFiniteRetry(() => this.api.create(request, signal), signal, this.sleep);
  }

  private async uploadSingle(file: File, session: MediaUploadSession, signal: AbortSignal): Promise<CompletedMediaPart[]> {
    const grant = session.uploadGrant;
    if (!grant || Date.parse(grant.expiresAt) <= this.now()) throw new Error("UPLOAD_GRANT_EXPIRED");
    await retryPut(() => this.uploadPut(grant, file, signal, bytes => this.patch({ uploadedBytes: bytes,
      progress: ratio(bytes, file.size) })), signal, this.sleep);
    return [];
  }

  private async uploadMultipart(file: File, session: MediaUploadSession, signal: AbortSignal): Promise<CompletedMediaPart[]> {
    const multipart = session.multipart;
    if (!multipart) throw new Error("MULTIPART_CONTRACT_MISSING");
    const partSize = Number(multipart.partSize);
    const totalParts = Math.ceil(file.size / partSize);
    if (totalParts > multipart.maxParts) throw new Error("MULTIPART_TOO_MANY_PARTS");
    const completed = new Map<number, CompletedMediaPart>();
    const progressByPart = new Map<number, number>();
    for (const part of multipart.uploadedParts) {
      completed.set(part.partNumber, { partNumber: part.partNumber, etag: part.etag });
      progressByPart.set(part.partNumber, Number(part.size));
    }
    const pending = Array.from({ length: totalParts }, (_, index) => index + 1).filter(number => !completed.has(number));
    let cursor = 0;
    const updateProgress = () => {
      const uploaded = [...progressByPart.values()].reduce((sum, value) => sum + value, 0);
      this.patch({ uploadedBytes: Math.min(file.size, uploaded), progress: ratio(uploaded, file.size) });
    };
    updateProgress();
    const worker = async () => {
      while (cursor < pending.length) {
        const partNumber = pending[cursor++];
        const start = (partNumber - 1) * partSize;
        const end = Math.min(file.size, start + partSize);
        const blob = file.slice(start, end);
        let etag = "";
        for (let attempt = 0; attempt < 3; attempt += 1) {
          progressByPart.set(partNumber, 0);
          updateProgress();
          try {
            const grant = await withFiniteRetry(() => this.api.authorizePart(session.uploadId,
              { partNumber, contentLength: blob.size }, signal), signal, this.sleep);
            etag = await this.uploadPut(grant, blob, signal, bytes => {
              progressByPart.set(partNumber, bytes);
              updateProgress();
            });
            if (!etag) throw new Error("MISSING_ETAG");
            progressByPart.set(partNumber, blob.size);
            updateProgress();
            break;
          } catch (error) {
            if (signal.aborted || attempt === 2) throw error;
            await this.sleep(500 * 2 ** attempt, signal);
          }
        }
        completed.set(partNumber, { partNumber, etag });
      }
    };
    await Promise.all(Array.from({ length: Math.min(this.concurrency, pending.length) }, worker));
    return [...completed.values()].sort((left, right) => left.partNumber - right.partNumber);
  }

  private async consumeAsset(asset: MediaAsset, signal: AbortSignal): Promise<string> {
    this.checkpoint = this.checkpoint ? { ...this.checkpoint, mediaId: asset.mediaId } : null;
    this.writeCheckpoint();
    if (asset.status === "READY") {
      this.patch({ stage: "READY", progress: 1, uploadedBytes: asset.size, totalBytes: asset.size,
        mediaId: asset.mediaId, statusLabel: "媒体已就绪，可以绑定到课程。", errorMessage: "" });
      return asset.mediaId;
    }
    if (asset.status === "FAILED" || asset.status === "REJECTED" || asset.status === "EXPIRED"
        || asset.status === "DELETED" || asset.status === "DELETING") {
      const reason = asset.errorMessage ? `：${asset.errorMessage}` : "";
      throw new Error(`MEDIA_${asset.status}${reason}`);
    }
    return await this.pollReady(asset.mediaId, signal);
  }

  private async pollReady(mediaId: string, signal: AbortSignal): Promise<string> {
    const deadline = this.now() + this.pollTimeoutMs;
    this.patch({ stage: "PROCESSING", mediaId, statusLabel: "文件已上传，正在校验或处理…" });
    while (this.now() < deadline) {
      const asset = await this.api.getMedia(mediaId, signal);
      if (asset.status === "READY") return await this.consumeAsset(asset, signal);
      if (["FAILED", "REJECTED", "EXPIRED", "DELETING", "DELETED"].includes(asset.status)) {
        return await this.consumeAsset(asset, signal);
      }
      this.patch({ statusLabel: asset.status === "PROCESSING" ? "视频正在转码为 HLS…"
        : asset.status === "VERIFYING" ? "正在核验文件完整性…" : "文件已接收，等待处理…" });
      await this.sleep(this.pollIntervalMs, signal);
    }
    throw new Error("MEDIA_PROCESSING_TIMEOUT");
  }

  private syncCheckpoint(session: MediaUploadSession) {
    this.checkpoint = { uploadId: session.uploadId, clientRequestId: session.clientRequestId, purpose: session.purpose,
      fileName: session.fileName, size: session.size, mimeType: session.mimeType, sha256: session.sha256,
      expiresAt: session.expiresAt, mediaId: session.mediaId ?? undefined };
    this.writeCheckpoint();
    this.patch({ uploadId: session.uploadId, mediaId: session.mediaId ?? undefined, fileName: session.fileName,
      totalBytes: session.size });
  }

  private writeCheckpoint() {
    if (this.checkpoint) this.storage?.setItem(this.checkpointKey, JSON.stringify(this.checkpoint));
  }

  private fail(message: string, clearCheckpoint: boolean) {
    if (clearCheckpoint) this.clearCheckpoint();
    this.patch({ stage: "FAILED", errorMessage: message, statusLabel: "上传未完成。" });
  }

  private patch(patch: Partial<MediaUploadViewState>) {
    this.state = { ...this.state, ...patch };
    this.emit();
  }

  private emit() { this.onChange({ ...this.state }); }
}

/** 按固定大小切片增量计算 SHA-256，内存上限由单个切片控制，不把整段视频一次性读入内存。 */
export async function hashFile(file: Blob, signal: AbortSignal,
    onProgress: (processedBytes: number) => void, chunkSize = 4 * 1024 * 1024): Promise<string> {
  const hash = sha256.create();
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    const bytes = new Uint8Array(await file.slice(offset, Math.min(file.size, offset + chunkSize)).arrayBuffer());
    hash.update(bytes);
    onProgress(Math.min(file.size, offset + bytes.byteLength));
    await Promise.resolve();
  }
  return bytesToHex(hash.digest());
}

/** 使用 XHR 直传授权对象，以 upload progress 事件报告真实字节并返回 OSS 实际 ETag。 */
export function putObject(grant: MediaUploadGrant, body: Blob, signal: AbortSignal,
    onProgress: (uploadedBytes: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    xhr.open(grant.method, grant.url, true);
    Object.entries(grant.headers ?? {}).forEach(([name, value]) => xhr.setRequestHeader(name, value));
    xhr.upload.addEventListener("progress", event => {
      if (event.lengthComputable) onProgress(Math.min(body.size, event.loaded));
    });
    xhr.addEventListener("load", () => {
      signal.removeEventListener("abort", abort);
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress(body.size);
        resolve(xhr.getResponseHeader("ETag") ?? xhr.getResponseHeader("etag") ?? "");
      } else reject(new Error(`OSS_UPLOAD_${xhr.status}`));
    });
    xhr.addEventListener("error", () => reject(new Error("OSS_UPLOAD_NETWORK")));
    xhr.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
    signal.addEventListener("abort", abort, { once: true });
    xhr.send(body);
  });
}

async function retryPut(operation: () => Promise<string>, signal: AbortSignal,
    sleep: (milliseconds: number, signal: AbortSignal) => Promise<void>): Promise<string> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try { return await operation(); } catch (error) {
      lastError = error;
      if (signal.aborted || attempt === 2) throw error;
      await sleep(500 * 2 ** attempt, signal);
    }
  }
  throw lastError;
}

async function withFiniteRetry<T>(operation: () => Promise<T>, signal: AbortSignal,
    sleep: (milliseconds: number, signal: AbortSignal) => Promise<void>): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try { return await operation(); } catch (error) {
      lastError = error;
      const retryable = error instanceof GatewayRequestError && (error.status === 429 || error.status === 503);
      if (!retryable || signal.aborted || attempt === 2) throw error;
      await sleep(500 * 2 ** attempt, signal);
    }
  }
  throw lastError;
}

function readCheckpoint(storage: Storage | undefined, key: string, purpose: MediaPurpose): UploadCheckpoint | null {
  const raw = storage?.getItem(key);
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as UploadCheckpoint;
    return value.purpose === purpose && typeof value.uploadId === "string" && typeof value.sha256 === "string" ? value : null;
  } catch {
    storage?.removeItem(key);
    return null;
  }
}

function normalizedMime(file: File): string {
  return file.type || "application/octet-stream";
}

function isSessionTerminal(status: MediaUploadSession["status"]): boolean {
  return ["READY", "REJECTED", "FAILED", "EXPIRED", "CANCELED"].includes(status);
}

function uploadErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message === "RESUME_FILE_MISMATCH") return "请选择与恢复会话同名、同大小且内容一致的本地文件。";
  if (error instanceof Error && (error.message === "UPLOAD_SESSION_EXPIRED" || error.message === "UPLOAD_GRANT_EXPIRED")) return "短期上传授权已过期，请取消后重新上传。";
  if (error instanceof Error && error.message === "MISSING_ETAG") return "OSS 未返回分片 ETag，无法安全完成上传。";
  if (error instanceof Error && error.message === "MEDIA_PROCESSING_TIMEOUT") return "媒体处理等待超时，可稍后刷新页面继续查询。";
  if (error instanceof Error && error.message.startsWith("MEDIA_REJECTED")) return `媒体未通过校验${error.message.split(":").slice(1).join(":") ? `：${error.message.split(":").slice(1).join(":")}` : ""}`;
  if (error instanceof Error && error.message.startsWith("MEDIA_FAILED")) return "媒体处理失败，请重新上传或更换文件。";
  if (error instanceof GatewayRequestError && error.status === 429) return "上传请求过于频繁，有限重试后仍未成功。";
  if (error instanceof GatewayRequestError && error.status === 503) return "媒体服务或 OSS 暂时不可用，请稍后重试。";
  return error instanceof GatewayRequestError ? error.message : fallback;
}

function initialState(): MediaUploadViewState {
  return { stage: "IDLE", progress: 0, uploadedBytes: 0, totalBytes: 0, statusLabel: "", errorMessage: "" };
}

function ratio(value: number, total: number): number { return total > 0 ? Math.min(1, Math.max(0, value / total)) : 0; }

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    signal.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); }, { once: true });
  });
}
