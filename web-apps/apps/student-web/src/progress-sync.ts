import type { LessonProgress, ReportLessonProgressRequest } from "@edu/api-client";
import type { PlaybackProgressSample } from "@edu/ui";

type ProgressEventType = NonNullable<ReportLessonProgressRequest["eventType"]>;
type Wait = (milliseconds: number, signal: AbortSignal) => Promise<void>;

interface ProgressSnapshot {
  eventType: ProgressEventType;
  positionSeconds: number;
  watchedSeconds: number;
  completed: boolean;
  occurredAt: Date;
}

interface ActiveRequest {
  body: ReportLessonProgressRequest;
  snapshot: ProgressSnapshot;
  attempts: number;
  mergedAfterFailure: boolean;
}

export interface ProgressSyncController {
  /** 接收播放器样本；多个未发送样本会在页面内存中合并为一个快照。 */
  enqueue(sample: PlaybackProgressSample): void;
  /** 发送暂停、切课、离页或结束检查点，并在约定等待窗口内返回是否已持久化。 */
  checkpoint(eventType: ProgressEventType, sample: PlaybackProgressSample): Promise<boolean>;
  /** 重新发送最近一次失败但尚未保存的快照；重试耗尽后会生成新的逻辑请求 ID。 */
  retry(): void;
  /** 页面隐藏/卸载时使用带认证头的 fetch keepalive 尽力发送 LEAVE。 */
  keepalive(eventType: ProgressEventType, sample: PlaybackProgressSample): void;
  /** 只有确实存在未保存快照时才返回 true，用于离页警告和状态展示。 */
  hasUnsavedProgress(): boolean;
  dispose(): void;
}

export interface ProgressSyncOptions {
  /** 测试注入的有界等待函数；生产实现由控制器内部使用可取消 setTimeout。 */
  wait?: Wait;
  /** 普通请求（含 429/网络/503）的总尝试次数。 */
  maxAttempts?: number;
  /** 强制检查点向调用页面承诺的最长等待时间。 */
  checkpointTimeoutMs?: number;
  /** 仅用于测试的退避序列，生产默认为 500/1000/2000ms。 */
  retryDelaysMs?: number[];
  /** pagehide 使用的同一 Gateway API，但其底层 fetch 必须设置 keepalive。 */
  reportKeepalive?: (body: ReportLessonProgressRequest) => Promise<LessonProgress>;
}

const MAX_REQUEST_WATCHED_SECONDS = 300;
const MAX_POSITION_SECONDS = 28_800;
const MAX_IN_MEMORY_WATCHED_SECONDS = 86_400;

/**
 * 学生端进度同步控制器。
 * 播放器只提供生命周期样本，本控制器负责映射 OpenAPI eventType、合并失败快照、有限重试和资源清理。
 * 成功响应由页面读取 persistenceStatus：HEARTBEAT 的 BUFFERED 只表示进入 Redis，PERSISTED 才表示检查点已落库。
 */
export function createProgressSyncController(
    report: (body: ReportLessonProgressRequest, signal: AbortSignal) => Promise<LessonProgress>,
    onSuccess: (progress: LessonProgress) => void,
    onFailure: (error: unknown) => void,
    createId: () => string = () => crypto.randomUUID(),
    now: () => Date = () => new Date(),
    options: ProgressSyncOptions = {}): ProgressSyncController {
  const abortController = new AbortController();
  const wait = options.wait ?? abortableDelay;
  const maxAttempts = Math.max(1, options.maxAttempts ?? 3);
  const retryDelaysMs = options.retryDelaysMs ?? [500, 1_000, 2_000];
  const checkpointTimeoutMs = options.checkpointTimeoutMs ?? 3_000;
  let disposed = false;
  let pending: ProgressSnapshot | null = null;
  let active: ActiveRequest | null = null;
  let pumpRunning = false;
  let blockedAfterCheckpointFailure = false;
  let checkpointWaiter: { eventType: ProgressEventType; promise: Promise<boolean>; settled: boolean;
    resolve: (value: boolean) => void; timer: ReturnType<typeof setTimeout> } | null = null;

  function sampleToSnapshot(sample: PlaybackProgressSample, eventType = eventTypeFor(sample.kind)): ProgressSnapshot {
    return {
      eventType,
      positionSeconds: clamp(Math.floor(sample.positionSeconds), 0, MAX_POSITION_SECONDS),
      watchedSeconds: clamp(Math.floor(sample.watchedSeconds), 0, MAX_IN_MEMORY_WATCHED_SECONDS),
      completed: eventType === "ENDED",
      occurredAt: now()
    };
  }

  function mergeSnapshots(left: ProgressSnapshot | null, right: ProgressSnapshot): ProgressSnapshot {
    if (!left) return right;
    const eventType = isCheckpoint(right.eventType) || right.eventType === "ENDED" ? right.eventType : left.eventType;
    return {
      eventType,
      positionSeconds: Math.max(left.positionSeconds, right.positionSeconds),
      watchedSeconds: Math.min(MAX_IN_MEMORY_WATCHED_SECONDS, left.watchedSeconds + right.watchedSeconds),
      completed: left.completed || right.completed || eventType === "ENDED",
      occurredAt: left.occurredAt.getTime() >= right.occurredAt.getTime() ? left.occurredAt : right.occurredAt
    };
  }

  function bodyFrom(snapshot: ProgressSnapshot): ReportLessonProgressRequest {
    return {
      clientEventId: createId(),
      occurredAt: snapshot.occurredAt.toISOString(),
      positionSeconds: snapshot.positionSeconds,
      watchedSeconds: Math.min(MAX_REQUEST_WATCHED_SECONDS, snapshot.watchedSeconds),
      completed: snapshot.eventType === "ENDED",
      eventType: snapshot.eventType
    };
  }

  function snapshotFromBody(body: ReportLessonProgressRequest, eventType: ProgressEventType): ProgressSnapshot {
    return {
      eventType,
      positionSeconds: body.positionSeconds,
      watchedSeconds: body.watchedSeconds,
      completed: eventType === "ENDED",
      occurredAt: new Date(body.occurredAt)
    };
  }

  function takePending(): ActiveRequest | null {
    if (!pending) return null;
    const snapshot: ProgressSnapshot = { ...pending,
      watchedSeconds: Math.min(MAX_REQUEST_WATCHED_SECONDS, pending.watchedSeconds) };
    pending = pending.watchedSeconds > MAX_REQUEST_WATCHED_SECONDS
      ? { ...pending, watchedSeconds: pending.watchedSeconds - MAX_REQUEST_WATCHED_SECONDS } : null;
    const body = bodyFrom(snapshot);
    return { body, snapshot: snapshotFromBody(body, snapshot.eventType), attempts: 0, mergedAfterFailure: false };
  }

  function requeueFailed(activeRequest: ActiveRequest): void {
    if (activeRequest.mergedAfterFailure) return;
    pending = mergeSnapshots(pending, activeRequest.snapshot);
    activeRequest.mergedAfterFailure = true;
  }

  function removeRequeuedSnapshot(activeRequest: ActiveRequest): void {
    if (!activeRequest.mergedAfterFailure || !pending) return;
    const remainingWatchedSeconds = Math.max(0, pending.watchedSeconds - activeRequest.snapshot.watchedSeconds);
    const hasNewerPosition = pending.positionSeconds > activeRequest.snapshot.positionSeconds;
    const hasNewerEvent = pending.eventType !== activeRequest.snapshot.eventType;
    pending = remainingWatchedSeconds === 0 && !hasNewerPosition && !hasNewerEvent
      ? null : { ...pending, watchedSeconds: remainingWatchedSeconds };
  }

  function settleCheckpoint(value: boolean, eventType?: ProgressEventType): void {
    if (!checkpointWaiter || checkpointWaiter.settled
        || eventType !== undefined && checkpointWaiter.eventType !== eventType) return;
    checkpointWaiter.settled = true;
    clearTimeout(checkpointWaiter.timer);
    checkpointWaiter.resolve(value);
    checkpointWaiter = null;
  }

  async function pump(): Promise<void> {
    if (pumpRunning || disposed || active || blockedAfterCheckpointFailure || !pending) return;
    pumpRunning = true;
    active = takePending();
    if (!active) {
      pumpRunning = false;
      return;
    }
    const activeRequest = active;
    let continueAfterFinish = false;
    try {
      for (activeRequest.attempts = 0; activeRequest.attempts < maxAttempts; activeRequest.attempts += 1) {
        if (disposed) return;
        try {
          const result = await report(activeRequest.body, abortController.signal);
          if (disposed) return;
          onSuccess(result);
          removeRequeuedSnapshot(activeRequest);
          const eventType = activeRequest.snapshot.eventType;
          active = null;
          blockedAfterCheckpointFailure = false;
          continueAfterFinish = true;
          settleCheckpoint(true, eventType);
          return;
        } catch (error) {
          if (disposed || isAbortError(error)) return;
          requeueFailed(activeRequest);
          if (!isRetryable(error) || activeRequest.attempts === maxAttempts - 1) {
            active = null;
            const eventType = activeRequest.snapshot.eventType;
            blockedAfterCheckpointFailure = isCheckpoint(eventType);
            onFailure(error);
            settleCheckpoint(false, eventType);
            return;
          }
          const delay = retryDelaysMs[Math.min(activeRequest.attempts, retryDelaysMs.length - 1)] ?? 2_000;
          try {
            await wait(delay, abortController.signal);
          } catch (waitError) {
            if (!disposed && !isAbortError(waitError)) onFailure(waitError);
            return;
          }
        }
      }
    } finally {
      pumpRunning = false;
      if (continueAfterFinish && !disposed && !active && pending && !blockedAfterCheckpointFailure) void pump();
    }
  }

  function enqueue(sample: PlaybackProgressSample): void {
    if (disposed || sample.kind === "FLUSH") return;
    pending = mergeSnapshots(pending, sampleToSnapshot(sample));
    void pump();
  }

  function checkpoint(eventType: ProgressEventType, sample: PlaybackProgressSample): Promise<boolean> {
    if (disposed) return Promise.resolve(false);
    if (checkpointWaiter?.eventType === eventType) return checkpointWaiter.promise;
    if (checkpointWaiter) return Promise.resolve(false);
    pending = mergeSnapshots(pending, sampleToSnapshot(sample, eventType));
    blockedAfterCheckpointFailure = false;
    let resolveCheckpoint!: (value: boolean) => void;
    const promise = new Promise<boolean>(resolve => { resolveCheckpoint = resolve; });
    const timer = setTimeout(() => settleCheckpoint(false, eventType), checkpointTimeoutMs);
    checkpointWaiter = { eventType, promise, settled: false, resolve: resolveCheckpoint, timer };
    void pump();
    return promise;
  }

  function retry(): void {
    if (disposed) return;
    blockedAfterCheckpointFailure = false;
    void pump();
  }

  function keepalive(eventType: ProgressEventType, sample: PlaybackProgressSample): void {
    if (disposed || !options.reportKeepalive) return;
    const snapshot = mergeSnapshots(pending, sampleToSnapshot(sample, eventType));
    // keepalive 不等待、不重试；它只在浏览器仍允许发送时尽力交付当前内存快照。
    void options.reportKeepalive(bodyFrom(snapshot)).catch(() => undefined);
  }

  function hasUnsavedProgress(): boolean {
    return pending !== null || active?.mergedAfterFailure === true;
  }

  function dispose(): void {
    if (disposed) return;
    disposed = true;
    abortController.abort();
    pending = null;
    active = null;
    blockedAfterCheckpointFailure = false;
    settleCheckpoint(false);
  }

  return { enqueue, checkpoint, retry, keepalive, hasUnsavedProgress, dispose };
}

function eventTypeFor(kind: PlaybackProgressSample["kind"]): ProgressEventType {
  if (kind === "PAUSE") return "PAUSE";
  if (kind === "ENDED") return "ENDED";
  return "HEARTBEAT";
}

function isCheckpoint(eventType: ProgressEventType): boolean {
  return eventType !== "HEARTBEAT";
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

function isRetryable(error: unknown): boolean {
  if (error instanceof TypeError) return true;
  if (error instanceof DOMException && (error.name === "TimeoutError" || error.name === "NetworkError")) return true;
  if (typeof error === "object" && error !== null && "status" in error) {
    const status = Number((error as { status?: unknown }).status);
    return status === 408 || status === 429 || status === 500 || status === 502 || status === 503 || status === 504;
  }
  return false;
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Number.isFinite(value) ? Math.min(maximum, Math.max(minimum, value)) : minimum;
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const handleAbort = () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", handleAbort);
      resolve();
    }, milliseconds);
    signal.addEventListener("abort", handleAbort, { once: true });
  });
}
