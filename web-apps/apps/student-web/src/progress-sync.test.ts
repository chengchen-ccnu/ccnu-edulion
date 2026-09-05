import type { LessonProgress } from "@edu/api-client";
import { describe, expect, it, vi } from "vitest";

import { createProgressSyncController } from "./progress-sync";

describe("lesson progress sync queue", () => {
  it("serializes out-of-order positions and assigns one stable id to each request", async () => {
    const calls: Array<{ clientEventId: string; positionSeconds: number }> = [];
    const successes: LessonProgress[] = [];
    const ids = ["00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"];
    const report = vi.fn(async body => {
      calls.push({ clientEventId: body.clientEventId, positionSeconds: body.positionSeconds });
      return progress(Math.max(80, body.positionSeconds), body.positionSeconds === 20);
    });
    const controller = createProgressSyncController(report, value => successes.push(value), vi.fn(),
      () => ids.shift()!, () => new Date("2026-08-26T00:00:00Z"));

    controller.enqueue({ positionSeconds: 80.9, watchedSeconds: 16.4, completed: false });
    controller.enqueue({ positionSeconds: 20.1, watchedSeconds: 2.8, completed: false });
    await vi.waitFor(() => expect(successes).toHaveLength(2));

    expect(calls).toEqual([
      { clientEventId: "00000000-0000-4000-8000-000000000001", positionSeconds: 80 },
      { clientEventId: "00000000-0000-4000-8000-000000000002", positionSeconds: 20 }
    ]);
    expect(successes[1]).toMatchObject({ positionSeconds: 80, replayed: true });
  });

  it("aborts current work and drops queued samples on route disposal", async () => {
    let aborted = false;
    const report = vi.fn((_body, signal: AbortSignal) => new Promise<LessonProgress>((_resolve, reject) => {
      signal.addEventListener("abort", () => { aborted = true; reject(new DOMException("Aborted", "AbortError")); });
    }));
    const failure = vi.fn();
    const controller = createProgressSyncController(report, vi.fn(), failure,
      () => "00000000-0000-4000-8000-000000000001");

    controller.enqueue({ positionSeconds: 15, watchedSeconds: 15, completed: false });
    await vi.waitFor(() => expect(report).toHaveBeenCalledTimes(1));
    controller.dispose();
    await vi.waitFor(() => expect(aborted).toBe(true));
    expect(failure).not.toHaveBeenCalled();
  });
});

function progress(positionSeconds: number, replayed: boolean): LessonProgress {
  return { courseId: "course-1", lessonId: "lesson-1", positionSeconds, watchedSeconds: 30,
    completed: false, lastEventAt: "2026-08-26T00:00:00Z", revision: 2, replayed,
    persistenceStatus: "PERSISTED" };
}
