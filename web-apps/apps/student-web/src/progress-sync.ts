import type { LessonProgress, ReportLessonProgressRequest } from "@edu/api-client";
import type { PlaybackProgressSample } from "@edu/ui";

export interface ProgressSyncController {
  enqueue(sample: PlaybackProgressSample): void;
  dispose(): void;
}

/**
 * 将播放器样本串行写入学习域。每个样本只生成一次 clientEventId，认证刷新和 429 重试会复用同一请求体；
 * 切换课时或离页会中止当前请求并丢弃尚未发送的旧课时样本，避免资源泄漏和跨课串写。
 */
export function createProgressSyncController(
    report: (body: ReportLessonProgressRequest, signal: AbortSignal) => Promise<LessonProgress>,
    onSuccess: (progress: LessonProgress) => void,
    onFailure: (error: unknown) => void,
    createId: () => string = () => crypto.randomUUID(),
    now: () => Date = () => new Date()): ProgressSyncController {
  const controller = new AbortController();
  let disposed = false;
  let queue = Promise.resolve();

  return {
    enqueue(sample) {
      if (disposed) return;
      const body: ReportLessonProgressRequest = {
        clientEventId: createId(),
        occurredAt: now().toISOString(),
        positionSeconds: Math.max(0, Math.floor(sample.positionSeconds)),
        watchedSeconds: Math.min(300, Math.max(0, Math.floor(sample.watchedSeconds))),
        completed: sample.completed
      };
      queue = queue.then(async () => {
        if (disposed) return;
        try {
          const result = await report(body, controller.signal);
          if (!disposed) onSuccess(result);
        } catch (error) {
          if (!disposed && !(error instanceof DOMException && error.name === "AbortError")) onFailure(error);
        }
      });
    },
    dispose() {
      disposed = true;
      controller.abort();
    }
  };
}
