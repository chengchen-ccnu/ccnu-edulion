import { GatewayRequestError } from "@edu/api-client";
import type { AttemptAnswerInput, AttemptDraft, SaveAttemptDraftRequest } from "@edu/api-client";

export type SaveIndicator = "idle" | "saving" | "saved" | "failed";
export type AttemptSaveSnapshot = {
  status: SaveIndicator;
  dirty: boolean;
  revision: number;
  lastSavedAnswers: AttemptAnswerInput[];
  error: unknown | null;
};
type SaveDraft = (body: SaveAttemptDraftRequest, signal: AbortSignal) => Promise<AttemptDraft>;
type SnapshotListener = (snapshot: AttemptSaveSnapshot) => void;
type Wait = (milliseconds: number, signal: AbortSignal) => Promise<void>;

/**
 * 作答自动保存状态机。每次逻辑保存冻结同一请求 ID、revision 和答案体，网络重试复用该请求；
 * 新编辑只会串行排入下一次保存，旧请求不能越过较新状态，失败也不会阻塞后续手动或定时保存。dispose 会取消全部等待和请求。
 */
export class AttemptSaveController {
  private revision: number;
  private answers: AttemptAnswerInput[];
  private savedAnswers: AttemptAnswerInput[];
  private dirty = false;
  private status: SaveIndicator = "idle";
  private error: unknown | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private intervalTimer: ReturnType<typeof setInterval> | null = null;
  private controller: AbortController | null = null;
  private activeSave: Promise<boolean> | null = null;
  private disposed = false;

  constructor(private readonly saveDraft: SaveDraft, initial: AttemptDraft, private readonly listener: SnapshotListener,
      private readonly wait: Wait = abortableDelay, private readonly debounceMs = 800,
      private readonly intervalMs = 15_000) {
    this.revision = initial.revision;
    this.answers = cloneAnswers(initial.answers);
    this.savedAnswers = cloneAnswers(initial.answers);
    this.intervalTimer = setInterval(() => { if (this.dirty) void this.flush(); }, intervalMs);
    this.emit();
  }

  /** 接收整卷当前答案；锁定后的页面不会再调用此方法。 */
  change(answers: AttemptAnswerInput[]) {
    if (this.disposed) return;
    this.answers = cloneAnswers(answers);
    this.dirty = !sameAnswers(this.answers, this.savedAnswers);
    this.error = null;
    if (this.dirty) {
      if (this.debounceTimer) clearTimeout(this.debounceTimer);
      this.debounceTimer = setTimeout(() => void this.flush(), this.debounceMs);
    }
    this.emit();
  }

  /** 立即保存当前未保存答案；调用方可在提交确认前等待它完成。 */
  async flush(): Promise<boolean> {
    if (this.disposed || !this.dirty) return !this.dirty;
    if (this.debounceTimer) { clearTimeout(this.debounceTimer); this.debounceTimer = null; }
    if (this.activeSave) {
      const activeSucceeded = await this.activeSave;
      if (this.disposed || !activeSucceeded) return false;
      return this.dirty ? this.flush() : true;
    }
    const active = this.performLogicalSave();
    this.activeSave = active;
    const succeeded = await active;
    if (this.activeSave === active) this.activeSave = null;
    if (succeeded && this.dirty && !this.disposed) return this.flush();
    return succeeded && !this.dirty;
  }

  private async performLogicalSave(): Promise<boolean> {
    const body: SaveAttemptDraftRequest = {
      clientRequestId: crypto.randomUUID(), expectedRevision: this.revision, answers: cloneAnswers(this.answers)
    };
    this.status = "saving"; this.error = null; this.emit();
    const controller = new AbortController();
    this.controller = controller;
    try {
      const result = await this.withFiniteRetry(body, controller.signal);
      if (controller.signal.aborted || this.disposed) return false;
      this.revision = result.revision;
      this.savedAnswers = cloneAnswers(body.answers);
      this.dirty = !sameAnswers(this.answers, this.savedAnswers);
      this.status = this.dirty ? "idle" : "saved";
      this.error = null;
      this.emit();
      return true;
    } catch (error) {
      if (controller.signal.aborted || this.disposed) return false;
      this.status = "failed"; this.error = error; this.dirty = true; this.emit();
      return false;
    }
  }

  getSnapshot(): AttemptSaveSnapshot {
    return { status: this.status, dirty: this.dirty, revision: this.revision,
      lastSavedAnswers: cloneAnswers(this.savedAnswers), error: this.error };
  }

  dispose() {
    this.disposed = true;
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    if (this.intervalTimer) clearInterval(this.intervalTimer);
    this.controller?.abort();
    this.debounceTimer = null; this.intervalTimer = null; this.controller = null;
    this.activeSave = null;
  }

  private async withFiniteRetry(body: SaveAttemptDraftRequest, signal: AbortSignal): Promise<AttemptDraft> {
    let lastError: unknown;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        return await this.saveDraft(body, signal);
      } catch (error) {
        lastError = error;
        if (!retryable(error) || attempt === 2) throw error;
        const delay = error instanceof GatewayRequestError && error.retryAfterSeconds !== null
          ? Math.min(5_000, error.retryAfterSeconds * 1_000) : 500 * 2 ** attempt;
        await this.wait(delay, signal);
      }
    }
    throw lastError;
  }

  private emit() { this.listener(this.getSnapshot()); }
}

export function cloneAnswers(answers: AttemptAnswerInput[]): AttemptAnswerInput[] {
  return answers.map(item => ({ assessmentQuestionId: item.assessmentQuestionId, answer: [...item.answer] }));
}

function sameAnswers(left: AttemptAnswerInput[], right: AttemptAnswerInput[]): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function retryable(error: unknown): boolean {
  return error instanceof TypeError || error instanceof GatewayRequestError
    && (error.status === 429 || error.status >= 500);
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, milliseconds);
    signal.addEventListener("abort", abort, { once: true });
  });
}

/**
 * 用服务端 serverTime 建立单调展示时钟；重新校准只替换基点，浏览器时间不能延长服务端 deadline。
 */
export class ServerDeadlineClock {
  private serverEpoch = 0;
  private performanceEpoch = 0;
  private deadlineEpoch: number | null = null;

  calibrate(serverTime: string, deadlineAt: string | null) {
    this.serverEpoch = Date.parse(serverTime);
    this.performanceEpoch = performance.now();
    this.deadlineEpoch = deadlineAt ? Date.parse(deadlineAt) : null;
  }

  remainingMilliseconds(): number | null {
    if (this.deadlineEpoch === null) return null;
    const currentServer = this.serverEpoch + (performance.now() - this.performanceEpoch);
    return Math.max(0, this.deadlineEpoch - currentServer);
  }
}
