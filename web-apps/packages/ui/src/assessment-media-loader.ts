export type AssessmentMediaFailureKind =
  | "AUTHORIZATION_FAILED" | "RATE_LIMITED" | "SERVICE_UNAVAILABLE"
  | "NETWORK_FAILED" | "ACCESS_EXPIRED" | "MEDIA_UNAVAILABLE" | "CANCELLED";

export class AssessmentMediaLoadError extends Error {
  constructor(public readonly kind: AssessmentMediaFailureKind, public readonly status?: number,
      public readonly code?: string) {
    super(kind);
  }
}

export type AssessmentMediaGrant = {
  url: string;
  headers: Record<string, string>;
  expiresAt: string;
  /** 不含签名 URL 的稳定资源键，用于合并同一题图的授权与下载。 */
  cacheKey?: string;
};

export type AssessmentMediaMetrics = {
  authorizationMs: number;
  downloadMs: number;
  decodeMs: number;
  status: number;
  bytes: number;
  cacheHit: boolean;
};

const MAX_CACHE_ITEMS = 24;
const MAX_CACHE_BYTES = 32 * 1024 * 1024;
const MAX_CONCURRENT_DOWNLOADS = 3;
type Limiter = { active: number; limit: number; waiters: Array<() => void> };
// 浏览器带 Origin 的领域授权并发会触发 Gateway/Learning 500；授权串行，OSS 下载仍保留三路小并发。
const authorizationLimiter: Limiter = { active: 0, limit: 1, waiters: [] };
const downloadLimiter: Limiter = { active: 0, limit: MAX_CONCURRENT_DOWNLOADS, waiters: [] };
const cache = new Map<string, { blob: Blob; bytes: number }>();
type InFlightEntry = { promise: Promise<{ blob: Blob; metrics: AssessmentMediaMetrics }>; controller: AbortController; subscribers: number };
const inFlight = new Map<string, InFlightEntry>();
let cacheBytes = 0;

/** 清理仅供隔离测试使用；产品运行期缓存只按 LRU 上限自然淘汰。 */
export function resetAssessmentMediaSessionCache() {
  cache.clear(); inFlight.clear(); cacheBytes = 0;
  for (const limiter of [authorizationLimiter, downloadLimiter]) {
    limiter.active = 0; limiter.waiters.splice(0).forEach(resolve => resolve());
  }
}

async function acquire(limiter: Limiter, signal: AbortSignal) {
  if (signal.aborted) throw new AssessmentMediaLoadError("CANCELLED");
  if (limiter.active < limiter.limit) { limiter.active += 1; return; }
  await new Promise<void>((resolve, reject) => {
    const ready = () => { signal.removeEventListener("abort", cancelled); limiter.active += 1; resolve(); };
    const cancelled = () => { const index = limiter.waiters.indexOf(ready); if (index >= 0) limiter.waiters.splice(index, 1); reject(new AssessmentMediaLoadError("CANCELLED")); };
    limiter.waiters.push(ready); signal.addEventListener("abort", cancelled, { once: true });
  });
}

function release(limiter: Limiter) { limiter.active -= 1; limiter.waiters.shift()?.(); }
async function timedLimited<T>(limiter: Limiter, signal: AbortSignal, work: () => Promise<T>) {
  await acquire(limiter, signal);
  const started = performance.now();
  try { return { value: await work(), durationMs: performance.now() - started }; }
  finally { release(limiter); }
}
function retryAfter(response: Response) {
  const value = response.headers.get("Retry-After");
  if (!value) return 1000;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);
  return Math.max(0, new Date(value).getTime() - Date.now());
}
async function delay(ms: number, signal: AbortSignal) {
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(timer); reject(new AssessmentMediaLoadError("CANCELLED")); }, { once: true });
  });
}
function statusOf(error: unknown) {
  return typeof error === "object" && error !== null && "status" in error && typeof error.status === "number"
    ? error.status : undefined;
}
function codeOf(error: unknown) {
  return typeof error === "object" && error !== null && "code" in error && typeof error.code === "string"
    ? error.code : undefined;
}
function retryDelayOf(error: unknown) {
  return typeof error === "object" && error !== null && "retryAfterSeconds" in error
    && typeof error.retryAfterSeconds === "number" ? Math.max(0, error.retryAfterSeconds * 1000) : 0;
}
function classifyAuthorization(error: unknown) {
  if (error instanceof AssessmentMediaLoadError) return error;
  if (error instanceof DOMException && error.name === "AbortError") return new AssessmentMediaLoadError("CANCELLED");
  const status = statusOf(error);
  if (status === 429) return new AssessmentMediaLoadError("RATE_LIMITED", status, codeOf(error));
  if (status && [502, 503, 504].includes(status)) return new AssessmentMediaLoadError("SERVICE_UNAVAILABLE", status, codeOf(error));
  if (error instanceof TypeError) return new AssessmentMediaLoadError("NETWORK_FAILED");
  return new AssessmentMediaLoadError("AUTHORIZATION_FAILED", status, codeOf(error));
}
function classifyDownload(response: Response, expiresAt: string) {
  if (response.status === 429) return new AssessmentMediaLoadError("RATE_LIMITED", 429);
  if ([502, 503, 504].includes(response.status)) return new AssessmentMediaLoadError("SERVICE_UNAVAILABLE", response.status);
  if (response.status === 403 && Date.parse(expiresAt) <= Date.now() + 5000) return new AssessmentMediaLoadError("ACCESS_EXPIRED", 403);
  return new AssessmentMediaLoadError("MEDIA_UNAVAILABLE", response.status);
}
function retryable(error: AssessmentMediaLoadError) {
  return ["NETWORK_FAILED", "SERVICE_UNAVAILABLE", "ACCESS_EXPIRED", "RATE_LIMITED"].includes(error.kind);
}
function remember(key: string, blob: Blob) {
  if (blob.size > MAX_CACHE_BYTES) return;
  cache.set(key, { blob, bytes: blob.size }); cacheBytes += blob.size;
  while (cache.size > MAX_CACHE_ITEMS || cacheBytes > MAX_CACHE_BYTES) {
    const oldest = cache.entries().next().value as [string, { blob: Blob; bytes: number }] | undefined;
    if (!oldest) break;
    cache.delete(oldest[0]); cacheBytes -= oldest[1].bytes;
  }
}

/**
 * 加载一个受保护题图。输入是可重复执行的领域授权函数和稳定资源键；输出只含 Blob 与安全耗时指标。
 * 网络、502/503/504、过期授权及 429 最多自动重试一次，失败不缓存，也不记录或返回签名 URL。
 */
export async function loadAssessmentMedia(cacheKey: string, authorize: (signal: AbortSignal) => Promise<AssessmentMediaGrant>, signal: AbortSignal) {
  const cached = cache.get(cacheKey);
  if (cached) {
    cache.delete(cacheKey); cache.set(cacheKey, cached);
    return { blob: cached.blob, metrics: { authorizationMs: 0, downloadMs: 0, decodeMs: 0,
      status: 200, bytes: cached.bytes, cacheHit: true } };
  }
  const existing = inFlight.get(cacheKey);
  if (existing) return subscribe(existing, signal);
  const sharedController = new AbortController();
  const task = (async () => {
    const sharedSignal = sharedController.signal;
    let last: AssessmentMediaLoadError | undefined;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      let grant: AssessmentMediaGrant;
      let authorizationMs = 0;
      try {
        const authorized = await timedLimited(authorizationLimiter, sharedSignal, () => authorize(sharedSignal));
        grant = authorized.value; authorizationMs = authorized.durationMs;
      } catch (error) {
        last = classifyAuthorization(error);
        if (attempt === 0 && retryable(last)) { await delay(last.kind === "RATE_LIMITED" ? retryDelayOf(error) : 0, sharedSignal); continue; }
        throw last;
      }
      let rateLimitDelay = 0;
      try {
        const downloaded = await timedLimited(downloadLimiter, sharedSignal, async () => {
          const response = await fetch(grant.url, { headers: grant.headers, signal: sharedSignal, cache: "no-store" });
          if (!response.ok) {
            if (response.status === 429) rateLimitDelay = retryAfter(response);
            throw classifyDownload(response, grant.expiresAt);
          }
          return { blob: await response.blob(), status: response.status };
        });
        const result = { blob: downloaded.value.blob, metrics: { authorizationMs, downloadMs: downloaded.durationMs,
          decodeMs: 0, status: downloaded.value.status, bytes: downloaded.value.blob.size, cacheHit: false } };
        remember(cacheKey, result.blob);
        return result;
      } catch (error) {
        last = error instanceof AssessmentMediaLoadError ? error
          : error instanceof DOMException && error.name === "AbortError" ? new AssessmentMediaLoadError("CANCELLED")
            : new AssessmentMediaLoadError("NETWORK_FAILED");
        if (attempt === 0 && retryable(last)) { await delay(last.kind === "RATE_LIMITED" ? rateLimitDelay : 0, sharedSignal); continue; }
        throw last;
      }
    }
    throw last ?? new AssessmentMediaLoadError("MEDIA_UNAVAILABLE");
  })();
  const entry: InFlightEntry = { promise: task, controller: sharedController, subscribers: 0 };
  inFlight.set(cacheKey, entry);
  void task.finally(() => { if (inFlight.get(cacheKey) === entry) inFlight.delete(cacheKey); }).catch(() => {});
  return subscribe(entry, signal);
}

async function subscribe(entry: InFlightEntry, signal: AbortSignal) {
  if (signal.aborted) throw new AssessmentMediaLoadError("CANCELLED");
  entry.subscribers += 1;
  try {
    return await Promise.race([entry.promise, new Promise<never>((_, reject) => signal.addEventListener("abort",
      () => reject(new AssessmentMediaLoadError("CANCELLED")), { once: true }))]);
  } finally {
    entry.subscribers -= 1;
    if (entry.subscribers === 0) entry.controller.abort();
  }
}
