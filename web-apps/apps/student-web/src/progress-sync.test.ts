import type { LessonProgress, ReportLessonProgressRequest } from "@edu/api-client";
import type { PlaybackProgressSample } from "@edu/ui";
import { describe, expect, it, vi } from "vitest";

import { createProgressSyncController } from "./progress-sync";

const ids = () => {
  const values = ["00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
    "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000004"];
  return () => values.shift()!;
};

describe("lesson progress sync controller", () => {
  it("maps five lifecycle events and keeps only ENDED completed", async () => {
    const calls: ReportLessonProgressRequest[] = [];
    const report = vi.fn(async (body: ReportLessonProgressRequest) => {
      calls.push(body);
      return progress(body.positionSeconds, body.completed, body.eventType === "HEARTBEAT" ? "BUFFERED" : "PERSISTED");
    });
    const controller = createProgressSyncController(report, vi.fn(), vi.fn(), ids(), fixedNow(), {
      contentRevisionId: "00000000-0000-4000-8000-000000000101"
    });

    controller.enqueue(sample(5, 5, "HEARTBEAT"));
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    controller.enqueue(sample(8, 1, "PAUSE"));
    await vi.waitFor(() => expect(calls).toHaveLength(2));
    expect(await controller.checkpoint("LESSON_SWITCH", sample(9, 0, "FLUSH"))).toBe(true);
    expect(await controller.checkpoint("LEAVE", sample(10, 0, "FLUSH"))).toBe(true);
    controller.enqueue(sample(11, 0, "ENDED"));
    await vi.waitFor(() => expect(calls).toHaveLength(5));

    expect(calls.map(item => item.eventType)).toEqual(["HEARTBEAT", "PAUSE", "LESSON_SWITCH", "LEAVE", "ENDED"]);
    expect(calls.every(item => item.contentRevisionId === "00000000-0000-4000-8000-000000000101")).toBe(true);
    expect(calls.slice(0, 4).every(item => item.completed === false)).toBe(true);
    expect(calls[4]?.completed).toBe(true);
  });

  it("merges multiple short samples after a network failure without an unbounded queue", async () => {
    const calls: ReportLessonProgressRequest[] = [];
    const report = vi.fn(async (body: ReportLessonProgressRequest) => {
      calls.push(body);
      if (calls.length === 1) throw new TypeError("offline");
      return progress(body.positionSeconds, false, "BUFFERED");
    });
    const controller = createProgressSyncController(report, vi.fn(), vi.fn(), ids(), fixedNow(), { maxAttempts: 1 });

    controller.enqueue(sample(20, 4, "HEARTBEAT"));
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    controller.enqueue(sample(30, 3, "HEARTBEAT"));
    controller.enqueue(sample(25, 2, "HEARTBEAT"));
    await vi.waitFor(() => expect(calls).toHaveLength(3));

    expect(calls.slice(1).reduce((total, item) => total + item.watchedSeconds, 0)).toBe(9);
    expect(Math.max(...calls.slice(1).map(item => item.positionSeconds))).toBe(30);
    expect(controller.hasUnsavedProgress()).toBe(false);
  });

  it("reuses one clientEventId and body during finite retry, then creates a new id", async () => {
    const calls: ReportLessonProgressRequest[] = [];
    const report = vi.fn(async (body: ReportLessonProgressRequest) => {
      calls.push({ ...body });
      if (calls.length < 3) throw Object.assign(new Error("unavailable"), { status: 503 });
      return progress(body.positionSeconds, false, "BUFFERED");
    });
    const controller = createProgressSyncController(report, vi.fn(), vi.fn(), ids(), fixedNow(), {
      wait: async () => undefined
    });

    controller.enqueue(sample(12, 2, "HEARTBEAT"));
    await vi.waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[1]).toEqual(calls[0]);
    expect(calls[2]).toEqual(calls[0]);

    const reportAfterExhaustion = vi.fn(async (body: ReportLessonProgressRequest) => {
      if (reportAfterExhaustion.mock.calls.length === 1) throw new TypeError("offline");
      return progress(body.positionSeconds, false, "BUFFERED");
    });
    const retryController = createProgressSyncController(reportAfterExhaustion, vi.fn(), vi.fn(), ids(), fixedNow(), {
      maxAttempts: 1
    });
    retryController.enqueue(sample(15, 2, "HEARTBEAT"));
    await vi.waitFor(() => expect(reportAfterExhaustion).toHaveBeenCalledTimes(1));
    retryController.enqueue(sample(16, 1, "HEARTBEAT"));
    await vi.waitFor(() => expect(reportAfterExhaustion).toHaveBeenCalledTimes(2));
    expect(reportAfterExhaustion.mock.calls[1][0].clientEventId)
      .not.toBe(reportAfterExhaustion.mock.calls[0][0].clientEventId);
  });

  it("waits for a PERSISTED forced checkpoint and gives up after a bounded timeout", async () => {
    let resolveRequest!: (value: LessonProgress) => void;
    const report = vi.fn(() => new Promise<LessonProgress>(resolve => { resolveRequest = resolve; }));
    const controller = createProgressSyncController(report, vi.fn(), vi.fn(), ids(), fixedNow(), {
      checkpointTimeoutMs: 20
    });

    const result = controller.checkpoint("PAUSE", sample(40, 5, "PAUSE"));
    await expect(result).resolves.toBe(false);
    expect(report).toHaveBeenCalledWith(expect.objectContaining({ eventType: "PAUSE", completed: false }), expect.anything());
    resolveRequest(progress(40, false, "PERSISTED"));
    controller.dispose();
  });

  it("uses an authenticated keepalive callback for best-effort LEAVE and cleans abort work on dispose", async () => {
    const keepalive = vi.fn(async () => progress(50, false, "PERSISTED"));
    let aborted = false;
    const report = vi.fn((_body: ReportLessonProgressRequest, signal: AbortSignal) => new Promise<LessonProgress>((_resolve, reject) => {
      signal.addEventListener("abort", () => { aborted = true; reject(new DOMException("Aborted", "AbortError")); });
    }));
    const controller = createProgressSyncController(report, vi.fn(), vi.fn(), ids(), fixedNow(), { reportKeepalive: keepalive });

    controller.keepalive("LEAVE", sample(50, 2, "FLUSH"));
    expect(keepalive).toHaveBeenCalledWith(expect.objectContaining({ eventType: "LEAVE", completed: false }));
    controller.enqueue(sample(50, 2, "HEARTBEAT"));
    await vi.waitFor(() => expect(report).toHaveBeenCalledTimes(1));
    controller.dispose();
    await vi.waitFor(() => expect(aborted).toBe(true));
  });
});

function sample(positionSeconds: number, watchedSeconds: number, kind: PlaybackProgressSample["kind"]): PlaybackProgressSample {
  return { positionSeconds, watchedSeconds, completed: kind === "ENDED", kind };
}

function fixedNow(): () => Date {
  return () => new Date("2026-08-26T00:00:00Z");
}

function progress(positionSeconds: number, completed: boolean, persistenceStatus: LessonProgress["persistenceStatus"]): LessonProgress {
  return { courseId: "course-1", lessonId: "lesson-1",
    contentRevisionId: "00000000-0000-4000-8000-000000000101", positionSeconds, watchedSeconds: 30,
    completed, lastEventAt: "2026-08-26T00:00:00Z", revision: 2, replayed: false, persistenceStatus };
}
