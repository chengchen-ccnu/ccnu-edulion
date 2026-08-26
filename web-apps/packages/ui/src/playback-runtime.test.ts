import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";

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
    expect(playbackErrorMessage({ status: 401 })).toContain("重新登录");
    expect(playbackErrorMessage({ status: 403 })).toBe("当前身份无权播放该视频。");
    expect(playbackErrorMessage({ status: 404 })).toContain("资源不可见");
    expect(playbackErrorMessage({ status: 409 })).toContain("仍在处理中");
    expect(playbackErrorMessage({ status: 503 })).toContain("媒体服务暂时不可用");
  });

  it("keeps audio enabled and releases every in-memory player resource on unmount", () => {
    const source = readFileSync(new URL("./HlsVideoPlayer.vue", import.meta.url), "utf8");

    expect(source).not.toMatch(/<video[^>]*\bmuted\b/);
    expect(source).toContain("onBeforeUnmount(cleanup)");
    expect(source).toContain("grantAbort?.abort()");
    expect(source).toContain("clearTimeout(refreshTimer)");
    expect(source).toContain("hls?.destroy()");
    expect(source).toContain('removeAttribute("src")');
    expect(source).toContain("progressTracker.flush()");
    expect(source).toContain('@timeupdate="handleTimeUpdate"');
    expect(source).toContain('@ended="handleEnded"');
  });
});
