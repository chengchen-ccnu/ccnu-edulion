import { describe, expect, it, vi } from "vitest";

import { playbackErrorMessage, playbackRefreshDelay, requestPlaybackGrant } from "./playback-runtime";

describe("playback grant runtime", () => {
  it("refreshes before expiry with a bounded lead time", () => {
    const now = Date.parse("2026-08-05T00:00:00Z");
    expect(playbackRefreshDelay("2026-08-05T00:05:00Z", now)).toBe(270_000);
    expect(playbackRefreshDelay("2026-08-05T00:00:20Z", now)).toBe(15_000);
    expect(playbackRefreshDelay("2026-08-04T23:59:59Z", now)).toBeNull();
  });

  it("backs off 429 at most twice and then stops", async () => {
    const provider = vi.fn(async () => { throw { status: 429 }; });
    const wait = vi.fn(async () => undefined);

    await expect(requestPlaybackGrant(provider, new AbortController().signal, wait)).rejects.toEqual({ status: 429 });
    expect(provider).toHaveBeenCalledTimes(3);
    expect(wait).toHaveBeenCalledTimes(2);
    expect(playbackErrorMessage({ status: 429 })).toContain("稍后手动重试");
  });

  it("keeps role-specific authorization semantics out of the shared error mapper", () => {
    expect(playbackErrorMessage({ status: 403 })).toBe("当前身份无权播放该视频。");
    expect(playbackErrorMessage({ status: 503 })).toContain("媒体服务暂时不可用");
  });
});
