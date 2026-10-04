import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTeacherAnnouncementApi } from "./announcement-api";

afterEach(() => vi.unstubAllGlobals());
const store = { authorizedRequest: (send: (authorization: string) => Promise<unknown>) => send("Bearer test-token") } as unknown as AuthStore;

describe("teacher owner announcement Gateway adapter", () => {
  /** 五操作共用生成的 Gateway 路径；取消信号必须透传，正文和 revision 不得在适配层改写。 */
  it("sends bounded pagination, generated write payloads and abort signals through authorized Gateway requests", async () => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const request = input as Request; requests.push(request);
      expect(request.headers.get("Authorization")).toBe("Bearer test-token");
      return new Response(JSON.stringify({ items: [], page: 2, size: 20, total: 0 }), { headers: { "Content-Type": "application/json" } });
    }));
    const api = createTeacherAnnouncementApi(store, createGatewayClient("http://gateway.test"));
    const controller = new AbortController(); const body = { clientRequestId: "request-uuid", title: "公告", body: "纯文本" };
    await api.list("course", 2, controller.signal); await api.revisions("course", "announcement", 2, controller.signal);
    await api.create("course", body, controller.signal);
    await api.update("course", "announcement", { ...body, expectedRevision: 7 }, controller.signal);
    await api.withdraw("course", "announcement", { clientRequestId: body.clientRequestId, expectedRevision: 7 }, controller.signal);
    expect(requests.map(request => [request.method, new URL(request.url).pathname])).toEqual([
      ["GET", "/api/v1/teacher/courses/course/announcements"], ["GET", "/api/v1/teacher/courses/course/announcements/announcement/revisions"],
      ["POST", "/api/v1/teacher/courses/course/announcements"], ["PUT", "/api/v1/teacher/courses/course/announcements/announcement"],
      ["POST", "/api/v1/teacher/courses/course/announcements/announcement/withdraw"]
    ]);
    expect(new URL(requests[0].url).search).toBe("?page=2&size=20");
    expect(await requests[3].json()).toEqual({ ...body, expectedRevision: 7 });
    controller.abort(); expect(requests.every(request => request.signal.aborted)).toBe(true);
  });
  it("preserves 409 and request-level fields without an adapter retry", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: "OPERATION.CONFLICT", message: "冲突", fieldErrors: [{ field: "request", reason: "命令冲突" }] }), { status: 409, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const api = createTeacherAnnouncementApi(store, createGatewayClient("http://gateway.test"));
    await expect(api.withdraw("course", "announcement", { clientRequestId: "request", expectedRevision: 1 }, new AbortController().signal))
      .rejects.toMatchObject({ status: 409, code: "OPERATION.CONFLICT", fieldErrors: { request: "命令冲突" } });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
