import { describe, expect, it } from "vitest";

import { createPlaybackProgressTracker } from "./playback-progress";

describe("playback progress tracker", () => {
  it("counts wall-clock playback instead of seek distance and flushes on pause", () => {
    let now = 0;
    const samples: Array<{ positionSeconds: number; watchedSeconds: number; completed: boolean }> = [];
    const tracker = createPlaybackProgressTracker(sample => samples.push(sample), () => now);

    tracker.play(0);
    now = 5_400;
    tracker.observe(120);
    tracker.pause(121);

    expect(samples).toEqual([{ positionSeconds: 121, watchedSeconds: 5, completed: false }]);
  });

  it("emits a bounded periodic sample and a completion sample", () => {
    let now = 0;
    const samples: Array<{ positionSeconds: number; watchedSeconds: number; completed: boolean }> = [];
    const tracker = createPlaybackProgressTracker(sample => samples.push(sample), () => now, 15);

    tracker.play(0);
    now = 15_800;
    tracker.observe(16);
    now = 18_200;
    tracker.complete(18);

    expect(samples).toEqual([
      { positionSeconds: 16, watchedSeconds: 15, completed: false },
      { positionSeconds: 18, watchedSeconds: 3, completed: true }
    ]);
  });
});
