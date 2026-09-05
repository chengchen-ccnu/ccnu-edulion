import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const learning = readFileSync(new URL("./views/CourseLearningPage.vue", import.meta.url), "utf8");
const sync = readFileSync(new URL("./progress-sync.ts", import.meta.url), "utf8");
const api = readFileSync(new URL("./learning-api.ts", import.meta.url), "utf8");
const player = readFileSync(new URL("../../../packages/ui/src/HlsVideoPlayer.vue", import.meta.url), "utf8");
const tracker = readFileSync(new URL("../../../packages/ui/src/playback-progress.ts", import.meta.url), "utf8");

describe("T12G.2 playback progress boundaries", () => {
  it("keeps the five event mappings in the existing player and learning page", () => {
    for (const eventType of ["HEARTBEAT", "PAUSE", "ENDED"]) expect(sync).toContain(eventType);
    for (const eventType of ["LESSON_SWITCH", "LEAVE"]) expect(learning).toContain(eventType);
    expect(tracker).toContain("intervalSeconds = 5");
    expect(player).toContain("flushProgress");
    expect(learning).toContain("checkpoint(eventType, latestPlaybackSample)");
    expect(learning).toContain('keepalive("LEAVE", latestPlaybackSample)');
    expect(learning).toContain("LESSON_SWITCH");
  });

  it("does not persist unsaved progress in browser storage and exposes bounded cleanup", () => {
    expect(`${learning}\n${sync}\n${player}`).not.toMatch(/localStorage|sessionStorage|indexedDB/i);
    expect(sync).toContain("maxAttempts");
    expect(sync).toContain("checkpointTimeoutMs");
    expect(sync).toContain("abortController.abort()");
    expect(learning).toContain("beforeunload");
    expect(learning).toContain("pagehide");
  });

  it("uses the generated progress API and authenticated keepalive request", () => {
    expect(api).toContain("reportProgressKeepalive");
    expect(api).toContain("keepalive: true");
    expect(api).toContain('progress-events');
    expect(learning).toContain("当前显示最近已保存进度");
    expect(learning).toContain("进度尚未保存");
    expect(learning).toContain("仍然离开");
    expect(learning).toContain("onBeforeRouteUpdate");
    expect(learning).toContain('to.name !== "course-learning"');
    expect(api).toContain("T12G.2 progress network");
    expect(api).toContain("T12G.2 progress read network");
    expect(api).toContain("persistenceStatuses");
  });

  it("keeps stable 422 diagnostics visible for progress validation failures", () => {
    expect(learning).toContain("Object.entries(error.fieldErrors)");
    expect(learning).toContain("error.code");
    expect(learning).toContain("error.message || \"提交内容不符合要求\"");
  });
});
