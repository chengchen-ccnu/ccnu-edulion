// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayRequestError } from "@edu/api-client";
import Page from "./views/TeacherCourseAnnouncementsPage.vue";
import { authStore } from "./auth";

const api = vi.hoisted(() => ({ list: vi.fn(), revisions: vi.fn(), create: vi.fn(), update: vi.fn(), withdraw: vi.fn(), detail: vi.fn() }));
vi.mock("./announcement-api", () => ({ teacherAnnouncementApi: api }));
vi.mock("./course-api", () => ({ teacherCourseApi: { detail: api.detail } }));
vi.mock("./auth", async () => ({ authStore: { state: (await import("vue")).reactive({ currentUser: {
  id: "owner", roles: ["TEACHER"], permissions: ["teaching:access"]
} }) } }));
const id = "00000000-0000-4000-8000-000000000001";
const item = (revision = 1, status = "ACTIVE") => ({ id, courseId: "course", title: "测试公告", body: "纯文本 <script>alert(1)</script>",
  revision, status, publishedAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z", confirmationRequired: false, confirmed: false });
const page = (revision = 1) => ({ items: [item(revision)], page: 1, size: 20, total: 21, latestUnconfirmed: null, unconfirmedCount: 0 });
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset());
  api.detail.mockResolvedValue({ id: "course", title: "本人课程", publicationStatus: "PUBLISHED" });
  api.list.mockResolvedValue(page()); api.revisions.mockResolvedValue({ items: [], page: 1, size: 20, total: 21 });
  api.create.mockResolvedValue(item()); api.update.mockResolvedValue(item(2)); api.withdraw.mockResolvedValue(item(2, "WITHDRAWN"));
  Object.assign(authStore.state.currentUser!, { id: "owner", roles: ["TEACHER"], permissions: ["teaching:access"] });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks(); });
async function mount(query = "") {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/teacher/courses/:courseId/announcements", name: "teacher-course-announcements", component: Page },
    { path: "/teacher/courses", name: "teacher-courses", component: { template: "<p>课程管理返回页</p>" } },
    { path: "/teacher/courses/:courseId/edit", name: "teacher-course-edit", component: { template: "<p>编辑</p>" } },
    { path: "/teacher/courses/:courseId/curriculum", name: "teacher-course-curriculum", component: { template: "<p>编排</p>" } }
  ] });
  await router.push(`/teacher/courses/course/announcements${query}`); await router.isReady();
  app = createApp({ template: "<RouterView />" }); app.use(router); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).not.toContain("正在读取课程状态")); return router;
}
function button(text: string) { return [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === text)!; }
async function input(title: string, body = "我的正文") {
  for (const [selector, value] of [["input", title], ["textarea", body]]) {
    const node = host.querySelector(selector) as HTMLInputElement; node.value = value; node.dispatchEvent(new Event("input", { bubbles: true }));
  }
  await nextTick();
}
async function submit() { host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await nextTick(); }
function failure(status: number, fieldErrors: { field: string; reason: string }[] = [], headers?: HeadersInit, code = "TEST.ERROR") {
  return new GatewayRequestError(new Response(null, { status, headers }), { code, message: "安全错误", traceId: "test-trace", timestamp: "2026-10-04T00:00:00Z", path: "/test", fieldErrors });
}

describe("teacher course announcements", () => {
  it("loads only the requested page and explains a selected item outside it without audit or single GET", async () => {
    api.list.mockResolvedValue({ ...page(), items: [] });
    await mount(`?page=2&panel=audit&announcementId=${id}&auditPage=2&coursePage=3`);
    expect(api.list).toHaveBeenCalledWith("course", 2, expect.any(AbortSignal)); expect(api.list).toHaveBeenCalledTimes(1);
    expect(api.revisions).not.toHaveBeenCalled(); expect(host.textContent).toContain("选中公告不在当前已载分页");
    expect(host.querySelector("a")?.getAttribute("href")).toBe("/teacher/courses?page=3");
  });
  it("renders plain text and restores list and audit pagination from the URL", async () => {
    const router = await mount(`?panel=audit&announcementId=${id}&auditPage=2`);
    expect(host.querySelector("script")).toBeNull(); expect(host.textContent).toContain("<script>alert(1)</script>");
    await vi.waitFor(() => expect(api.revisions).toHaveBeenCalledWith("course", id, 2, expect.any(AbortSignal)));
    await router.push(`/teacher/courses/course/announcements?panel=audit&announcementId=${id}&auditPage=1`);
    await vi.waitFor(() => expect(api.revisions).toHaveBeenCalledWith("course", id, 1, expect.any(AbortSignal)));
  });
  it("disables editor input until the selected snapshot finishes loading, then initializes exactly once", async () => {
    const router = await mount();
    let resolve: (value: ReturnType<typeof page>) => void = () => undefined;
    api.list.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    await router.push(`/teacher/courses/course/announcements?panel=edit&announcementId=${id}`);
    await nextTick(); expect((host.querySelector("input") as HTMLInputElement).disabled).toBe(true);
    resolve(page()); await vi.waitFor(() => expect((host.querySelector("input") as HTMLInputElement).disabled).toBe(false));
    await input("输入不应被刷新覆盖"); button("刷新").click();
    await vi.waitFor(() => expect(button("刷新").disabled).toBe(false));
    expect((host.querySelector("input") as HTMLInputElement).value).toBe("输入不应被刷新覆盖");
  });
  /** 断网未知结果不生成第二个逻辑发布；输入变化后必须改用新 ID，避免命令幂等与正文错配。 */
  it("keeps a stable ID for bounded uncertain retries and gives changed input a new ID", async () => {
    api.create.mockRejectedValue(new TypeError("offline"));
    await mount("?panel=create"); await input("我的公告");
    for (let n = 0; n < 3; n++) { await submit(); await vi.waitFor(() => expect(button("确认立即发布").disabled).toBe(false)); }
    const ids = api.create.mock.calls.map(call => call[1].clientRequestId); expect(new Set(ids).size).toBe(1);
    await submit(); expect(api.create).toHaveBeenCalledTimes(3); expect(host.textContent).toContain("原命令已尝试三次");
    await input("更改标题"); await submit(); await vi.waitFor(() => expect(api.create).toHaveBeenCalledTimes(4));
    expect(api.create.mock.calls[3][1].clientRequestId).not.toBe(ids[0]);
  });
  it("preserves the draft after 409 and waits for explicit adoption before writing the new revision", async () => {
    api.update.mockRejectedValueOnce(failure(409));
    await mount(`?panel=edit&announcementId=${id}`); await input("我的改版"); api.list.mockResolvedValue(page(2)); await submit();
    await vi.waitFor(() => expect(host.textContent).toContain("采用最新 revision，保留我的输入"));
    expect((host.querySelector("input") as HTMLInputElement).value).toBe("我的改版");
    await vi.waitFor(() => expect(button("确认发布改版")?.disabled).toBe(true)); expect(api.update.mock.calls[0][2].expectedRevision).toBe(1);
    button("采用最新 revision，保留我的输入").click(); await nextTick(); await submit();
    await vi.waitFor(() => expect(api.update).toHaveBeenCalledTimes(2));
    expect(api.update.mock.calls[1][2].expectedRevision).toBe(2);
    expect(api.update.mock.calls[1][2].clientRequestId).not.toBe(api.update.mock.calls[0][2].clientRequestId);
  });
  it("allows a create-state conflict to be retried only after explicitly checking current writable course facts", async () => {
    api.create.mockRejectedValueOnce(failure(409)); await mount("?panel=create"); await input("保留发布输入"); await submit();
    await vi.waitFor(() => expect(button("已核对课程状态，允许重新提交")?.disabled).toBe(false));
    expect(button("确认立即发布").disabled).toBe(true);
    expect((host.querySelector("input") as HTMLInputElement).value).toBe("保留发布输入");
    button("已核对课程状态，允许重新提交").click(); await nextTick(); await submit();
    await vi.waitFor(() => expect(api.create).toHaveBeenCalledTimes(2));
    expect(api.create.mock.calls[1][1].clientRequestId).not.toBe(api.create.mock.calls[0][1].clientRequestId);
  });
  it("requires two separate withdrawal actions and offers no restoration for withdrawn items", async () => {
    await mount(`?announcementId=${id}`); button("撤回这条公告").click(); await nextTick(); expect(api.withdraw).not.toHaveBeenCalled();
    api.list.mockResolvedValue({ ...page(), items: [item(2, "WITHDRAWN")] });
    button("确认撤回").click(); await vi.waitFor(() => expect(api.withdraw).toHaveBeenCalledTimes(1));
    expect(api.withdraw.mock.calls[0][2]).toMatchObject({ expectedRevision: 1 });
    await vi.waitFor(() => expect(button("改版")).toBeUndefined()); expect(host.textContent).toContain("已撤回"); expect(button("选择撤回")).toBeUndefined();
  });
  it.each(["DRAFT", "UNPUBLISHED"])("allows history but disables writes for %s", async publicationStatus => {
    api.detail.mockResolvedValue({ title: "本人课程", publicationStatus }); await mount(`?panel=audit&announcementId=${id}`);
    expect(button("发布公告").disabled).toBe(true); await vi.waitFor(() => expect(api.revisions).toHaveBeenCalled());
  });
  it("keeps history readable when the owner detail reports course suspension", async () => {
    api.detail.mockRejectedValue(failure(409, [], undefined, "COURSE.SUSPENDED")); await mount(`?panel=audit&announcementId=${id}`);
    expect(button("发布公告").disabled).toBe(true); expect(host.textContent).toContain("课程已暂停");
    await vi.waitFor(() => expect(api.revisions).toHaveBeenCalled());
  });
  it.each([401, 403, 404])("clears stale protected text after %s while refreshing", async status => {
    await mount(); api.list.mockRejectedValue(failure(status)); button("刷新").click();
    await vi.waitFor(() => expect(host.textContent).not.toContain("纯文本 <script>")); expect(button("发布公告").disabled).toBe(true);
  });
  it("renders both field and request level validation errors", async () => {
    api.create.mockRejectedValue(failure(422, [{ field: "title", reason: "标题错误" }, { field: "request", reason: "请求错误" }]));
    await mount("?panel=create"); await input("标题"); await submit();
    await vi.waitFor(() => expect(host.textContent).toContain("请求错误")); expect(host.textContent).toContain("标题错误");
    expect(host.querySelector("input")?.getAttribute("aria-invalid")).toBe("true");
  });
  it("respects Retry-After and keeps partial success when audit fails", async () => {
    api.revisions.mockRejectedValue(failure(429, [], { "Retry-After": "1" }));
    await mount(`?panel=audit&announcementId=${id}`); await vi.waitFor(() => expect(host.textContent).toContain("等待 1 秒"));
    expect(host.textContent).toContain("纯文本 <script>"); expect(button("重试审计").disabled).toBe(true);
    await vi.waitFor(() => expect(button("重试审计").disabled).toBe(false), { timeout: 2000 });
    expect(api.revisions).toHaveBeenCalledTimes(1);
  });
  it("shows service failure while retaining previously successful list content", async () => {
    await mount(); api.detail.mockRejectedValue(failure(503)); api.list.mockRejectedValue(new TypeError("offline")); button("刷新").click();
    await vi.waitFor(() => expect(host.textContent).toContain("网络不可用")); expect(host.textContent).toContain("纯文本 <script>");
    expect(button("发布公告").disabled).toBe(true);
  });
  /** 离页取消必须在确认后发生；拒绝离页保留输入，真正离开时旧请求不能再回写正文。 */
  it("guards dirty navigation and beforeunload, then aborts all outstanding page requests on leave", async () => {
    const router = await mount("?panel=create"); await input("未保存"); vi.mocked(window.confirm).mockReturnValueOnce(false);
    await router.push("/teacher/courses?page=3"); expect(router.currentRoute.value.query.panel).toBe("create");
    const event = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true);
    api.create.mockImplementation(() => new Promise(() => undefined)); await submit();
    const writeSignal = api.create.mock.calls[0][2] as AbortSignal;
    await router.push("/teacher/courses?page=3"); expect(writeSignal.aborted).toBe(true);
    expect((api.list.mock.calls[0][2] as AbortSignal).aborted).toBe(true);
    const after = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(after); expect(after.defaultPrevented).toBe(false);
  });
  it("refuses admin substitution and clears state when teaching capability is revoked", async () => {
    await mount("?panel=create"); await input("私有输入");
    authStore.state.currentUser!.roles = ["ADMIN"]; authStore.state.currentUser!.permissions = ["administration:access"];
    await vi.waitFor(() => expect(host.textContent).toContain("管理员不能代发")); expect(host.querySelector("textarea")).toBeNull();
    expect(api.create).not.toHaveBeenCalled();
  });
  it("keeps a draft when token restoration replaces the same current identity snapshot", async () => {
    await mount("?panel=create"); await input("令牌刷新保留输入");
    Reflect.set(authStore.state, "currentUser", { ...authStore.state.currentUser! }); await nextTick();
    expect(api.list).toHaveBeenCalledTimes(1); expect((host.querySelector("input") as HTMLInputElement).value).toBe("令牌刷新保留输入");
  });
});
