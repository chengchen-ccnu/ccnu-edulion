import type { CourseMediaPlaybackGrant } from "@edu/api-client";

/** 对单次授权请求执行有限 429 退避；刷新失败抛给组件后不会自行再次调度。 */
export async function requestPlaybackGrant(
    provider: (signal: AbortSignal) => Promise<CourseMediaPlaybackGrant>,
    signal: AbortSignal,
    wait: (milliseconds: number, signal: AbortSignal) => Promise<void> = abortableDelay): Promise<CourseMediaPlaybackGrant> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try { return await provider(signal); } catch (error) {
      lastError = error;
      if (signal.aborted || statusOf(error) !== 429 || attempt === 2) throw error;
      await wait(500 * 2 ** attempt, signal);
    }
  }
  throw lastError;
}

/** 计算授权到期前刷新等待时间；短授权至少预留 5 秒，长授权最多提前 30 秒。 */
export function playbackRefreshDelay(expiresAt: string, now = Date.now()): number | null {
  const remaining = Date.parse(expiresAt) - now;
  if (!Number.isFinite(remaining) || remaining <= 0) return null;
  const lead = Math.min(30_000, Math.max(5_000, remaining * 0.2));
  return Math.max(1_000, remaining - lead);
}

/** 将课程播放边界的稳定 HTTP 状态转换为不包含教师/学生业务判断的公共提示。 */
export function playbackErrorMessage(error: unknown): string {
  const status = statusOf(error);
  if (status === 401 || error instanceof Error && error.message === "AUTHENTICATION_REQUIRED") return "需要重新登录后才能播放该视频。";
  if (status === 403) return "当前身份无权播放该视频。";
  if (status === 404) return "课程、课时或视频资源不可见，可能已更新或下架。";
  if (status === 409) return "视频仍在处理中或当前状态不允许播放。";
  if (status === 429) return "播放请求过于频繁，请稍后手动重试。";
  if (status === 503) return "媒体服务暂时不可用，请稍后重试。";
  if (error instanceof Error && error.message === "HLS_UNSUPPORTED") return "当前浏览器不支持 HLS 播放，请使用最新版主流浏览器。";
  return "视频播放授权获取失败，请稍后重试。";
}

function statusOf(error: unknown): number | undefined {
  return typeof error === "object" && error !== null && "status" in error
    ? Number((error as { status: unknown }).status) : undefined;
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    signal.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); }, { once: true });
  });
}
