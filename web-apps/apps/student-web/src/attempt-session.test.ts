import { GatewayRequestError } from "@edu/api-client";
import type { AttemptAnswerInput, AttemptDraft, SaveAttemptDraftRequest } from "@edu/api-client";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AttemptSaveController, ServerDeadlineClock } from "./attempt-session";

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("attempt draft save controller", () => {
  it("reuses one immutable request ID and body for finite network retries", async () => {
    const calls: SaveAttemptDraftRequest[] = [];
    const save = vi.fn(async (body: SaveAttemptDraftRequest) => {
      calls.push(structuredClone(body));
      if (calls.length < 3) throw new TypeError("offline");
      return draft(2, body.answers);
    });
    const waits: number[] = [];
    const controller = new AttemptSaveController(save, draft(1), vi.fn(), async milliseconds => {
      waits.push(milliseconds);
    }, 800, 60_000);

    controller.change(answers("A"));
    expect(await controller.flush()).toBe(true);

    expect(save).toHaveBeenCalledTimes(3);
    expect(new Set(calls.map(item => item.clientRequestId)).size).toBe(1);
    expect(calls.every(item => JSON.stringify(item) === JSON.stringify(calls[0]))).toBe(true);
    expect(waits).toEqual([500, 1000]);
    expect(controller.getSnapshot()).toMatchObject({ revision: 2, dirty: false, status: "saved" });
    controller.dispose();
  });

  it("serializes a newer edit behind the active request so an older response cannot overwrite it", async () => {
    const first = deferred<AttemptDraft>();
    const second = deferred<AttemptDraft>();
    const bodies: SaveAttemptDraftRequest[] = [];
    const save = vi.fn((body: SaveAttemptDraftRequest) => {
      bodies.push(structuredClone(body));
      return bodies.length === 1 ? first.promise : second.promise;
    });
    const controller = new AttemptSaveController(save, draft(1), vi.fn(), undefined, 800, 60_000);

    controller.change(answers("old"));
    const oldFlush = controller.flush();
    controller.change(answers("new"));
    const newFlush = controller.flush();
    expect(save).toHaveBeenCalledTimes(1);

    first.resolve(draft(2, answers("old")));
    await vi.waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(bodies[1]).toMatchObject({ expectedRevision: 2, answers: answers("new") });
    second.resolve(draft(3, answers("new")));

    expect(await oldFlush).toBe(true);
    expect(await newFlush).toBe(true);
    expect(controller.getSnapshot()).toMatchObject({ revision: 3, dirty: false, status: "saved",
      lastSavedAnswers: answers("new") });
    controller.dispose();
  });

  it("allows the next logical save after a non-retryable failure and cancels active work on dispose", async () => {
    const activeSignals: AbortSignal[] = [];
    let call = 0;
    const save = vi.fn(async (body: SaveAttemptDraftRequest, signal: AbortSignal) => {
      activeSignals.push(signal); call += 1;
      if (call === 1) throw new GatewayRequestError(new Response(null, { status: 409 }));
      if (call === 2) return draft(2, body.answers);
      return new Promise<AttemptDraft>(() => undefined);
    });
    const controller = new AttemptSaveController(save, draft(1), vi.fn(), undefined, 800, 60_000);

    controller.change(answers("first"));
    expect(await controller.flush()).toBe(false);
    expect(controller.getSnapshot()).toMatchObject({ dirty: true, status: "failed" });
    controller.change(answers("second"));
    expect(await controller.flush()).toBe(true);
    controller.change(answers("third"));
    void controller.flush();
    await vi.waitFor(() => expect(save).toHaveBeenCalledTimes(3));
    controller.dispose();

    expect(activeSignals[2].aborted).toBe(true);
  });

  it("debounces edits, keeps a periodic fallback, and clears both timers on leave", async () => {
    vi.useFakeTimers();
    const save = vi.fn(async (body: SaveAttemptDraftRequest) => draft(2, body.answers));
    const controller = new AttemptSaveController(save, draft(1), vi.fn(), undefined, 800, 15_000);
    controller.change(answers("debounced"));
    await vi.advanceTimersByTimeAsync(799);
    expect(save).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(save).toHaveBeenCalledTimes(1);
    controller.change(answers("periodic"));
    await vi.advanceTimersByTimeAsync(15_000);
    expect(save).toHaveBeenCalledTimes(2);
    controller.dispose();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe("server deadline clock", () => {
  it("uses the service calibration plus a monotonic clock and can recalibrate after resume", () => {
    const now = vi.spyOn(performance, "now");
    now.mockReturnValueOnce(1000).mockReturnValueOnce(3500).mockReturnValueOnce(4000).mockReturnValueOnce(5000);
    const clock = new ServerDeadlineClock();
    clock.calibrate("2026-08-28T00:00:00Z", "2026-08-28T00:00:10Z");
    expect(clock.remainingMilliseconds()).toBe(7500);
    clock.calibrate("2026-08-28T00:00:09Z", "2026-08-28T00:00:10Z");
    expect(clock.remainingMilliseconds()).toBe(0);
  });
});

function draft(revision: number, currentAnswers: AttemptAnswerInput[] = []): AttemptDraft {
  return { id: "00000000-0000-4000-8000-000000000501", assessmentId: "00000000-0000-4000-8000-000000000401",
    status: "DRAFT", revision, answers: currentAnswers, startedAt: "2026-08-28T00:00:00Z",
    deadlineAt: "2026-08-28T00:10:00Z", serverTime: "2026-08-28T00:00:00Z",
    attemptSequence: 1, attemptsUsed: 0, maxAttempts: 1, lateSubmission: false,
    createdAt: "2026-08-28T00:00:00Z", updatedAt: "2026-08-28T00:00:00Z" };
}

function answers(value: string): AttemptAnswerInput[] {
  return [{ assessmentQuestionId: "00000000-0000-4000-8000-000000000601", answer: [value] }];
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
