// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { AssessmentMediaLoadError, loadAssessmentMedia, resetAssessmentMediaSessionCache } from "./assessment-media-loader";

const grant = { url: "https://signed.example.test/object", headers: {}, expiresAt: "2099-01-01T00:00:00Z" };
afterEach(() => { resetAssessmentMediaSessionCache(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("assessment media session loader", () => {
  it("retries one transient download and then serves the bounded session cache", async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce(new Response(new Blob(["image"]), { status: 200 }));
    vi.stubGlobal("fetch", fetcher); const authorize = vi.fn(async () => grant);
    const first = await loadAssessmentMedia("q:m:ORIGINAL:INLINE", authorize, new AbortController().signal);
    const second = await loadAssessmentMedia("q:m:ORIGINAL:INLINE", authorize, new AbortController().signal);
    expect(fetcher).toHaveBeenCalledTimes(2); expect(authorize).toHaveBeenCalledTimes(2);
    expect(first.metrics.cacheHit).toBe(false); expect(second.metrics.cacheHit).toBe(true);
  });

  it("does not automatically retry 403/404/422 and leaves a later manual retry possible", async () => {
    for (const status of [403, 404, 422]) {
      resetAssessmentMediaSessionCache();
      const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status }))
        .mockResolvedValueOnce(new Response(new Blob(["ok"]), { status: 200 }));
      vi.stubGlobal("fetch", fetcher);
      await expect(loadAssessmentMedia(`q:m:${status}`, async () => grant, new AbortController().signal))
        .rejects.toMatchObject({ kind: "MEDIA_UNAVAILABLE", status });
      expect(fetcher).toHaveBeenCalledTimes(1);
      await expect(loadAssessmentMedia(`q:m:${status}`, async () => grant, new AbortController().signal)).resolves.toBeTruthy();
    }
  });

  it("obeys Retry-After once and merges concurrent requests without one cancelled consumer stopping another", async () => {
    let finish!: (value: Response) => void;
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 429, headers: { "Retry-After": "0" } }))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }));
    vi.stubGlobal("fetch", fetcher); const authorize = vi.fn(async () => grant);
    const firstController = new AbortController(); const secondController = new AbortController();
    const first = loadAssessmentMedia("q:m:merged", authorize, firstController.signal);
    const second = loadAssessmentMedia("q:m:merged", authorize, secondController.signal);
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2)); firstController.abort();
    finish(new Response(new Blob(["shared"]), { status: 200 }));
    await expect(first).rejects.toMatchObject({ kind: "CANCELLED" }); await expect(second).resolves.toBeTruthy();
    expect(authorize).toHaveBeenCalledTimes(2); expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("evicts the least recently used Blob after the item limit and never caches failures", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["x"]), { status: 200 })));
    const authorizers = Array.from({ length: 25 }, () => vi.fn(async () => grant));
    for (let index = 0; index < authorizers.length; index += 1) {
      await loadAssessmentMedia(`q:m:${index}`, authorizers[index]!, new AbortController().signal);
    }
    await loadAssessmentMedia("q:m:0", authorizers[0]!, new AbortController().signal);
    expect(authorizers[0]).toHaveBeenCalledTimes(2);
    const failed = vi.fn(async () => { throw new AssessmentMediaLoadError("AUTHORIZATION_FAILED", 404); });
    await expect(loadAssessmentMedia("failed", failed, new AbortController().signal)).rejects.toBeTruthy();
    await expect(loadAssessmentMedia("failed", failed, new AbortController().signal)).rejects.toBeTruthy();
    expect(failed).toHaveBeenCalledTimes(2);
  });

  it("serializes browser authorization while retaining bounded parallel Blob downloads", async () => {
    let activeAuthorizations = 0; let maxAuthorizations = 0;
    let activeDownloads = 0; let maxDownloads = 0;
    const authorize = vi.fn(async () => {
      activeAuthorizations += 1; maxAuthorizations = Math.max(maxAuthorizations, activeAuthorizations);
      await Promise.resolve(); activeAuthorizations -= 1; return grant;
    });
    vi.stubGlobal("fetch", vi.fn(async () => {
      activeDownloads += 1; maxDownloads = Math.max(maxDownloads, activeDownloads);
      await new Promise(resolve => setTimeout(resolve, 10)); activeDownloads -= 1;
      return new Response(new Blob(["image"]), { status: 200 });
    }));
    await Promise.all(Array.from({ length: 7 }, (_, index) => loadAssessmentMedia(
      `question:media-${index}:ORIGINAL:INLINE`, authorize, new AbortController().signal)));
    expect(maxAuthorizations).toBe(1);
    expect(maxDownloads).toBeGreaterThan(1);
    expect(maxDownloads).toBeLessThanOrEqual(3);
  });
});
