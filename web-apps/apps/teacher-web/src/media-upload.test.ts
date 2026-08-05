import { GatewayRequestError } from "@edu/api-client";
import type { MediaAsset, MediaUploadSession } from "@edu/api-client";
import { describe, expect, it, vi } from "vitest";

import type { TeacherMediaApi } from "./media-api";
import { hashFile, MediaUploader } from "./media-upload";

describe("teacher media uploader", () => {
  it("streams one object, submits no invented part DTO, and binds only after READY", async () => {
    let clock = 0;
    const session = singleSession();
    const api = fakeApi({ create: vi.fn(async () => session),
      complete: vi.fn(async (_id, body) => {
        expect(body.parts).toEqual([]);
        return asset("PROCESSING");
      }),
      getMedia: vi.fn().mockResolvedValueOnce(asset("PROCESSING")).mockResolvedValueOnce(asset("READY")) });
    const put = vi.fn(async (_grant, body: Blob, _signal, progress: (bytes: number) => void) => {
      progress(2); progress(body.size); return "actual-etag";
    });
    const states: string[] = [];
    const uploader = new MediaUploader({ api, purpose: "COURSE_COVER", checkpointKey: "cover", storage: memoryStorage(),
      putObject: put, now: () => clock, sleep: async milliseconds => { clock += milliseconds; },
      onChange: state => states.push(state.stage) });

    await expect(uploader.upload(new File(["cover"], "cover.png", { type: "image/png" })))
      .resolves.toBe("00000000-0000-4000-8000-000000000901");
    expect(put).toHaveBeenCalledOnce();
    expect(states).toContain("PROCESSING");
    expect(uploader.snapshot).toMatchObject({ stage: "READY", progress: 1,
      mediaId: "00000000-0000-4000-8000-000000000901" });
  });

  it("resumes uploaded multipart parts, authorizes each missing part, and submits actual ETags in order", async () => {
    const session: MediaUploadSession = { ...singleSession(), mode: "MULTIPART", uploadGrant: null, size: 8,
      sha256: "ef797c8118f02dfb649607dd5d3f8c7623048c9c063d532cc95c5ed7a898a64f",
      multipart: { partSize: 3, maxParts: 10, uploadedParts: [{ partNumber: 1, etag: "server-etag-1", size: 3 }] } };
    const storage = memoryStorage();
    storage.setItem("video", JSON.stringify({ uploadId: session.uploadId, clientRequestId: session.clientRequestId,
      purpose: session.purpose, fileName: session.fileName, size: session.size, mimeType: session.mimeType,
      sha256: session.sha256, expiresAt: session.expiresAt }));
    const authorized: number[] = [];
    const api = fakeApi({ getUpload: vi.fn(async () => session),
      authorizePart: vi.fn(async (_id, body) => {
        authorized.push(body.partNumber);
        return { method: "PUT" as const, url: `https://oss.example.test/part-${body.partNumber}`,
          headers: {}, expiresAt: "2099-01-01T00:00:00Z" };
      }),
      complete: vi.fn(async (_id, body) => {
        expect(body.parts).toEqual([
          { partNumber: 1, etag: "server-etag-1" },
          { partNumber: 2, etag: "actual-etag-2" },
          { partNumber: 3, etag: "actual-etag-3" }
        ]);
        return asset("READY");
      }) });
    const put = vi.fn(async (grant, body: Blob, _signal, progress: (bytes: number) => void) => {
      progress(body.size);
      return `actual-etag-${grant.url.endsWith("2") ? 2 : 3}`;
    });
    const uploader = new MediaUploader({ api, purpose: "COURSE_COVER", checkpointKey: "video", storage,
      putObject: put, now: () => 0, sleep: async () => undefined, onChange: () => undefined });

    await uploader.restore();
    await expect(uploader.upload(new File(["12345678"], "cover.png", { type: "image/png" })))
      .resolves.toBe(asset("READY").mediaId);
    expect(authorized.sort()).toEqual([2, 3]);
    expect(put).toHaveBeenCalledTimes(2);
  });

  it("restores an active server session without pretending the browser still owns the file", async () => {
    const storage = memoryStorage();
    const session = singleSession();
    storage.setItem("resume", JSON.stringify({ uploadId: session.uploadId, clientRequestId: session.clientRequestId,
      purpose: session.purpose, fileName: session.fileName, size: session.size, mimeType: session.mimeType,
      sha256: session.sha256, expiresAt: session.expiresAt }));
    const uploader = new MediaUploader({ api: fakeApi({ getUpload: vi.fn(async () => session) }),
      purpose: "COURSE_COVER", checkpointKey: "resume", storage, onChange: () => undefined });

    await uploader.restore();

    expect(uploader.snapshot).toMatchObject({ stage: "NEEDS_FILE", fileName: "cover.png" });
    expect(storage.getItem("resume")).not.toContain("oss.example.test");
  });

  it("cancels the exact unfinished upload and aborts the active OSS request", async () => {
    const api = fakeApi({ create: vi.fn(async () => singleSession()), cancel: vi.fn(async () => undefined) });
    const put = vi.fn((_grant, _body, signal: AbortSignal) => new Promise<string>((_resolve, reject) => {
      signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
    }));
    const uploader = new MediaUploader({ api, purpose: "COURSE_COVER", checkpointKey: "cancel", storage: memoryStorage(),
      putObject: put, onChange: () => undefined });
    const running = uploader.upload(new File(["cover"], "cover.png", { type: "image/png" }));
    await vi.waitFor(() => expect(put).toHaveBeenCalled());

    await uploader.cancel();
    await expect(running).rejects.toMatchObject({ name: "AbortError" });
    expect(api.cancel).toHaveBeenCalledWith(singleSession().uploadId);
    expect(uploader.snapshot.stage).toBe("CANCELED");
  });

  it("hashes large inputs incrementally and reports each bounded chunk", async () => {
    const progress: number[] = [];
    const digest = await hashFile(new Blob(["abc"]), new AbortController().signal, value => progress.push(value), 1);
    expect(digest).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    expect(progress).toEqual([1, 2, 3]);
  });

  it("reuses one clientRequestId across finite 503 retries", async () => {
    const requestIds: string[] = [];
    let attempts = 0;
    const api = fakeApi({ create: vi.fn(async body => {
      requestIds.push(body.clientRequestId);
      attempts += 1;
      if (attempts < 3) throw new GatewayRequestError(new Response(null, { status: 503 }));
      return singleSession();
    }), complete: vi.fn(async () => asset("READY")) });
    const uploader = new MediaUploader({ api, purpose: "COURSE_COVER", checkpointKey: "retry", storage: memoryStorage(),
      putObject: async (_grant, body, _signal, progress) => { progress(body.size); return "etag"; },
      sleep: async () => undefined, onChange: () => undefined });

    await uploader.upload(new File(["cover"], "cover.png", { type: "image/png" }));
    expect(requestIds).toHaveLength(3);
    expect(new Set(requestIds).size).toBe(1);
  });

  it.each(["FAILED", "REJECTED"] as const)("never returns mediaId when processing ends in %s", async terminal => {
    const api = fakeApi({ create: vi.fn(async () => singleSession()), complete: vi.fn(async () => asset(terminal)) });
    const uploader = new MediaUploader({ api, purpose: "COURSE_COVER", checkpointKey: terminal,
      storage: memoryStorage(), putObject: async () => "etag", onChange: () => undefined });

    await expect(uploader.upload(new File(["cover"], "cover.png", { type: "image/png" }))).rejects.toThrow();
    expect(uploader.snapshot.stage).toBe("FAILED");
  });

  it("stops polling after the configured processing timeout", async () => {
    let clock = 0;
    const api = fakeApi({ create: vi.fn(async () => singleSession()), complete: vi.fn(async () => asset("PROCESSING")),
      getMedia: vi.fn(async () => asset("PROCESSING")) });
    const uploader = new MediaUploader({ api, purpose: "COURSE_COVER", checkpointKey: "timeout",
      storage: memoryStorage(), putObject: async () => "etag", now: () => clock,
      pollIntervalMs: 10, pollTimeoutMs: 25, sleep: async milliseconds => { clock += milliseconds; },
      onChange: () => undefined });

    await expect(uploader.upload(new File(["cover"], "cover.png", { type: "image/png" }))).rejects.toThrow("MEDIA_PROCESSING_TIMEOUT");
    expect(api.getMedia).toHaveBeenCalledTimes(3);
  });
});

function singleSession(): MediaUploadSession {
  return { uploadId: "00000000-0000-4000-8000-000000000801",
    clientRequestId: "00000000-0000-4000-8000-000000000802", purpose: "COURSE_COVER", mode: "SINGLE_PUT",
    status: "CREATED", fileName: "cover.png", size: 5, mimeType: "image/png",
    sha256: "d15690f08a15a7aebc9b4f5d8a43d693617d3f9f0d9dd63d5bcdd0f34c3b9657",
    uploadGrant: { method: "PUT", url: "https://oss.example.test/signed", headers: {}, expiresAt: "2099-01-01T00:00:00Z" },
    multipart: null, mediaId: null, expiresAt: "2099-01-01T00:00:00Z", createdAt: "2026-08-05T00:00:00Z" };
}

function asset(status: MediaAsset["status"]): MediaAsset {
  return { mediaId: "00000000-0000-4000-8000-000000000901", purpose: "COURSE_COVER", status,
    processingStatus: status === "READY" ? "SUCCEEDED" : "RUNNING", originalFileName: "cover.png",
    mimeType: "image/png", size: 5,
    sha256: "d15690f08a15a7aebc9b4f5d8a43d693617d3f9f0d9dd63d5bcdd0f34c3b9657",
    variants: [], errorCode: null, errorMessage: null, createdAt: "2026-08-05T00:00:00Z", updatedAt: "2026-08-05T00:00:00Z" };
}

function fakeApi(overrides: Partial<TeacherMediaApi>): TeacherMediaApi {
  const unexpected = async () => { throw new Error("unexpected API call"); };
  return { create: unexpected, getUpload: unexpected, cancel: unexpected, authorizePart: unexpected,
    complete: unexpected, getMedia: unexpected, ...overrides } as TeacherMediaApi;
}

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return { get length() { return values.size; }, clear: () => values.clear(),
    getItem: key => values.get(key) ?? null, key: index => [...values.keys()][index] ?? null,
    removeItem: key => { values.delete(key); }, setItem: (key, value) => { values.set(key, value); } };
}
