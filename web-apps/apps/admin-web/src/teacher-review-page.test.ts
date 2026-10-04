// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayRequestError } from "@edu/api-client";
import Page from "./views/TeacherReviewPage.vue";
import { authStore } from "./auth";

const api = vi.hoisted(() => ({ list: vi.fn(), detail: vi.fn(), material: vi.fn(), decide: vi.fn() }));
vi.mock("./teacher-review-api", () => ({ teacherReviewApi: api }));
vi.mock("./auth", async () => ({ authStore: { state: (await import("vue")).reactive({ currentUser: {
  id: "admin", roles: ["ADMIN"], permissions: ["administration:access"]
} }) } }));
const id = "00000000-0000-4000-8000-000000000001";
const otherId = "00000000-0000-4000-8000-000000000002";
const round = (available = true) => ({ id: "round", roundNumber: 1, qualificationMediaId: "asset", statement: "纯文本 <script>不能执行</script>",
  submittedAt: "2026-10-04T00:00:00Z", status: "UNDER_REVIEW", materialAvailable: available, firstViewedBy: "first-admin", firstViewedAt: "2026-10-04T01:00:00Z" });
const detail = (revision = 1, status = "UNDER_REVIEW") => ({ id, userId: "student", account: "test_student", displayName: "测试申请人", status, revision, rounds: [round()] });
const list = () => ({ items: [{ ...detail(), submittedAt: "2026-10-04T00:00:00Z", rounds: undefined }], page: 1, size: 20, total: 21 });
function failure(status: number, fieldErrors: { field: string; reason: string }[] = [], headers?: HeadersInit) {
  return new GatewayRequestError(new Response(null, { status, headers }), { code: "TEST.ERROR", message: "安全错误", traceId: "test",
    timestamp: "2026-10-04T00:00:00Z", path: "/test", fieldErrors });
}
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset()); api.list.mockResolvedValue(list()); api.detail.mockResolvedValue(detail());
  api.decide.mockResolvedValue(detail(2, "REJECTED"));
  api.material.mockResolvedValue({ mediaId: "asset", mimeType: "image/png", sizeBytes: 3, method: "GET", url: "https://private.test/temporary", headers: {}, expiresAt: new Date(Date.now() + 60000).toISOString() });
  Object.assign(authStore.state.currentUser!, { id: "admin", roles: ["ADMIN"], permissions: ["administration:access"] });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => new Response("png")));
  URL.createObjectURL = vi.fn().mockReturnValue("blob:private-test"); URL.revokeObjectURL = vi.fn();
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
async function mount(query = "") {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/admin/teacher-review", name: "admin-teacher-review", component: Page },
    { path: "/elsewhere", component: { template: "<p>其他页面</p>" } }
  ] });
  await router.push(`/admin/teacher-review${query}`); await router.isReady();
  app = createApp({ template: "<RouterView />" }); app.use(router); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).not.toContain("正在读取")); return router;
}
function button(text: string) { return [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === text)!; }
async function input(selector: string, value: string) {
  const node = host.querySelector(selector) as HTMLInputElement; node.value = value; node.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
}
async function reject() {
  await input("textarea", "请补充可验证的正式教学资质材料。"); button("驳回申请").click(); await nextTick();
}
async function preview() {
  button("预览第 1 轮材料").click(); await nextTick(); await vi.waitFor(() => expect(host.querySelector("img")).not.toBeNull());
  host.querySelector("img")!.dispatchEvent(new Event("load")); await nextTick();
  const check = host.querySelector('input[type="checkbox"]') as HTMLInputElement; check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
}

describe("administrator teacher qualification review", () => {
  it("revokes expired private preview and its approval confirmation", async () => {
    await mount(`?applicationId=${id}`); await preview(); expect(button("批准申请").disabled).toBe(false);
    // 使用真实Blob加载后改为短授权再读，过期计时器必须撤销DOM和批准资格。
    api.material.mockResolvedValue({ mediaId: "asset", mimeType: "image/png", sizeBytes: 3, method: "GET", url: "https://private.test/temporary", headers: {}, expiresAt: new Date(Date.now() + 1000).toISOString() });
    vi.useFakeTimers(); await preview(); await vi.advanceTimersByTimeAsync(1100); await nextTick();
    expect(host.querySelector("img")).toBeNull(); expect(button("批准申请").disabled).toBe(true); expect(URL.revokeObjectURL).toHaveBeenCalled();
  });
  it("handles a fetched PDF as a private Blob and blocks an invalid PDF file header", async () => {
    api.material.mockResolvedValue({ mediaId: "asset", mimeType: "application/pdf", sizeBytes: 10, method: "GET", url: "https://private.test/temporary", headers: {}, expiresAt: new Date(Date.now() + 60000).toISOString() });
    vi.mocked(fetch).mockResolvedValue(new Response("%PDF-1.7 synthetic")); await mount(`?applicationId=${id}`);
    button("预览第 1 轮材料").click(); await vi.waitFor(() => expect(host.querySelector("iframe")).not.toBeNull());
    expect(host.querySelector("iframe")!.src).toBe("blob:private-test");
    vi.mocked(fetch).mockResolvedValue(new Response("invalid bytes")); button("预览第 1 轮材料").click();
    await vi.waitFor(() => expect(host.textContent).toContain("材料无法读取")); expect(host.querySelector("iframe")).toBeNull(); expect(button("批准申请").disabled).toBe(true);
  });
  it("does not fetch private facts for a non administrator", async () => {
    Object.assign(authStore.state.currentUser!, { roles: ["STUDENT"], permissions: ["learning:access"] });
    await mount(`?applicationId=${id}`); expect(api.list).not.toHaveBeenCalled(); expect(api.detail).not.toHaveBeenCalled();
  });
  it("clears the private command during identity change while keeping an in-flight outcome warning", async () => {
    api.decide.mockReturnValue(new Promise(() => {})); const router = await mount(`?applicationId=${id}`); await reject();
    await vi.waitFor(() => expect(api.decide).toHaveBeenCalledTimes(1)); authStore.state.currentUser!.id = "different-admin"; await nextTick();
    expect(button("重试原决定").disabled).toBe(true); expect(host.querySelector("textarea")).toBeNull();
    expect(api.decide.mock.calls[0][2].aborted).toBe(true); vi.mocked(window.confirm).mockReturnValue(false);
    await router.push("/elsewhere"); expect(router.currentRoute.value.name).toBe("admin-teacher-review");
  });
  it("times out a pending decision into unknown state and cleans timer on unmount", async () => {
    const router = await mount(`?applicationId=${id}`); api.decide.mockReturnValue(new Promise(() => {}));
    vi.useFakeTimers(); await reject(); await vi.advanceTimersByTimeAsync(15000); await nextTick();
    expect(host.textContent).toContain("决定请求超时"); expect(api.decide.mock.calls[0][2].aborted).toBe(true);
    vi.mocked(window.confirm).mockReturnValue(false); await router.push("/elsewhere"); expect(router.currentRoute.value.name).toBe("admin-teacher-review");
    app!.unmount(); app = undefined; expect(vi.getTimerCount()).toBe(0);
  });
  it("reads one bounded page without opening or prefetching detail and restores filter/order/page", async () => {
    await mount("?page=2&status=SUBMITTED&order=OLDEST");
    expect(api.list).toHaveBeenCalledWith({ page: 2, size: 20, status: "SUBMITTED", order: "OLDEST", exact: undefined }, expect.any(AbortSignal));
    expect(api.detail).not.toHaveBeenCalled(); expect(api.material).not.toHaveBeenCalled();
  });
  it("starts review only on explicit selection, displays first viewer, supports history and browser back", async () => {
    const router = await mount(); button("打开详情").click();
    await vi.waitFor(() => expect(api.detail).toHaveBeenCalledTimes(1)); expect(host.textContent).toContain("first-admin");
    expect(host.querySelector("script")).toBeNull(); expect(host.textContent).toContain("<script>不能执行</script>");
    button("只读历史").click(); await vi.waitFor(() => expect(router.currentRoute.value.query.panel).toBe("history"));
    expect(api.detail).toHaveBeenCalledTimes(1); expect(host.querySelector("textarea")).toBeNull();
    router.back(); await vi.waitFor(() => expect(host.querySelector("textarea")).not.toBeNull());
  });
  it("opens a deep linked application outside the current page, without scanning pages", async () => {
    api.list.mockResolvedValue({ ...list(), items: [] }); await mount(`?page=2&applicationId=${id}&panel=history`);
    expect(api.detail).toHaveBeenCalledWith(id, expect.any(AbortSignal)); expect(api.list).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain("首次查看者");
  });
  it("keeps exact identity only in memory and removes sensitive deep-link parameters", async () => {
    const router = await mount("?exact=private%40example.invalid&token=secret&page=1");
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).not.toContain("private"));
    await input('.exact-query input', "student_test_01@example.invalid");
    host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ exact: "student_test_01@example.invalid" }), expect.any(AbortSignal)));
    expect(router.currentRoute.value.fullPath).not.toContain("example"); expect(localStorage.length).toBe(0);
  });
  it("requires actual material decode, checkbox and second human confirmation before approving", async () => {
    await mount(`?applicationId=${id}`); expect(button("批准申请").disabled).toBe(true);
    await preview(); expect(button("批准申请").disabled).toBe(false);
    vi.mocked(window.confirm).mockReturnValueOnce(false); button("批准申请").click(); await nextTick(); expect(api.decide).not.toHaveBeenCalled();
    button("批准申请").click(); await vi.waitFor(() => expect(api.decide).toHaveBeenCalledTimes(1));
    expect(api.decide.mock.calls[0][1]).toMatchObject({ expectedRevision: 1, decision: "APPROVED", materialConfirmed: true });
    expect(host.innerHTML).not.toContain("https://private.test");
  });
  it("allows approval after actual authorized download and clears Blob URLs on unmount", async () => {
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    await mount(`?applicationId=${id}`); button("下载第 1 轮材料").click();
    await vi.waitFor(() => expect(host.textContent).toContain("交给浏览器下载"));
    const check = host.querySelector('input[type="checkbox"]') as HTMLInputElement; check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    expect(button("批准申请").disabled).toBe(false); app!.unmount(); app = undefined;
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:private-test"); expect(api.material.mock.calls[0][2].aborted).toBe(true);
  });
  it.each([false, true])("blocks approval for unavailable material or download failure (available=%s)", async available => {
    api.detail.mockResolvedValue({ ...detail(), rounds: [round(available)] }); vi.mocked(fetch).mockRejectedValue(new TypeError("network"));
    await mount(`?applicationId=${id}`);
    if (available) { button("预览第 1 轮材料").click(); await vi.waitFor(() => expect(host.textContent).toContain("材料无法读取")); }
    else expect(button("预览第 1 轮材料").disabled).toBe(true);
    expect(button("批准申请").disabled).toBe(true); expect(api.decide).not.toHaveBeenCalled();
  });
  it("invalid image decode revokes confirmation and prevents approval", async () => {
    await mount(`?applicationId=${id}`); await preview(); host.querySelector("img")!.dispatchEvent(new Event("error")); await nextTick();
    expect(button("批准申请").disabled).toBe(true); expect(host.querySelector("img")).toBeNull();
  });
  it("requires a comprehensible reason and maps 422 fields without losing input", async () => {
    api.decide.mockRejectedValue(failure(422, [{ field: "rejectionReason", reason: "理由无效" }, { field: "request", reason: "命令校验失败" }]));
    await mount(`?applicationId=${id}`); await input("textarea", "太短"); button("驳回申请").click(); await nextTick(); expect(api.decide).not.toHaveBeenCalled();
    await reject(); await vi.waitFor(() => expect(host.textContent).toContain("命令校验失败"));
    expect((host.querySelector("textarea") as HTMLTextAreaElement).value).toContain("正式教学"); expect(host.textContent).toContain("理由无效");
  });
  /** 网络/5xx返回不能证明事务回滚：三次都复用原载荷，修改输入、刷新或离页不能静默消除未决标记。 */
  it.each([new TypeError("offline"), failure(503)])("preserves unknown decision snapshots with three-attempt limit and leave protection", async error => {
    api.decide.mockRejectedValue(error); const router = await mount(`?applicationId=${id}`); await reject();
    await vi.waitFor(() => expect(host.textContent).toContain("结果未知")); const original = JSON.parse(JSON.stringify(api.decide.mock.calls[0][1]));
    expect((host.querySelector("textarea") as HTMLTextAreaElement).disabled).toBe(true);
    button("重试原决定").click(); await vi.waitFor(() => expect(api.decide).toHaveBeenCalledTimes(2)); await nextTick();
    button("重试原决定").click(); await vi.waitFor(() => expect(api.decide).toHaveBeenCalledTimes(3)); await nextTick();
    expect(api.decide.mock.calls.every(call => JSON.stringify(call[1]) === JSON.stringify(original))).toBe(true);
    expect(button("重试原决定").disabled).toBe(true);
    vi.mocked(window.confirm).mockReturnValue(false); await router.push("/elsewhere"); expect(router.currentRoute.value.name).toBe("admin-teacher-review");
    const event = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true);
  });
  it("409 needs explicit refresh and acknowledgement, preserves reason and never auto overwrites", async () => {
    api.decide.mockRejectedValue(failure(409)); await mount(`?applicationId=${id}`); await reject();
    await vi.waitFor(() => expect(host.textContent).toContain("并发冲突")); expect(api.detail).toHaveBeenCalledTimes(1);
    expect(button("已核对，结束旧请求并重新决定").disabled).toBe(true); expect(api.decide).toHaveBeenCalledTimes(1);
    api.detail.mockResolvedValue(detail(4)); button("刷新详情并核对").click();
    await vi.waitFor(() => expect(host.textContent).toContain("版本 4")); expect((host.querySelector("textarea") as HTMLTextAreaElement).value).toContain("正式教学");
    button("已核对，结束旧请求并重新决定").click(); await nextTick(); button("驳回申请").click();
    await vi.waitFor(() => expect(api.decide).toHaveBeenCalledTimes(2));
    expect(api.decide.mock.calls[1][1].expectedRevision).toBe(4); expect(api.decide.mock.calls[1][1].clientRequestId).not.toBe(api.decide.mock.calls[0][1].clientRequestId);
  });
  it.each([401, 403])("clears private content and concurrent reads on %s", async status => {
    await mount(`?applicationId=${id}`); await preview(); api.list.mockRejectedValue(failure(status));
    button("重试列表"); // 手动查询触发列表授权失败，而详情已成功。
    host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(host.textContent).toContain("没有管理权限")); expect(host.textContent).not.toContain("纯文本");
    expect(host.querySelector("img")).toBeNull(); expect(URL.revokeObjectURL).toHaveBeenCalled();
  });
  it("clears private input and preview immediately on identity changes", async () => {
    await mount(`?applicationId=${id}`); await preview(); await input("textarea", "private reason");
    authStore.state.currentUser!.id = "different-admin"; await nextTick();
    expect(host.querySelector("textarea")).toBeNull(); expect(host.textContent).not.toContain("纯文本"); expect(URL.revokeObjectURL).toHaveBeenCalled();
  });
  it.each([404, 500, 429])("keeps successful list for detail failure %s", async status => {
    api.detail.mockRejectedValue(failure(status, [], status === 429 ? { "Retry-After": "1" } : undefined));
    await mount(`?applicationId=${id}`); expect(host.textContent).toContain("test_student"); expect(host.querySelector("textarea")).toBeNull();
    if (status === 429) expect(button("刷新详情并核对").disabled).toBe(true);
  });
  it("shows loading and empty honestly, ignores late replies after changing selection", async () => {
    let resolve: (value: unknown) => void = () => {}; api.detail.mockReturnValueOnce(new Promise(done => { resolve = done; }));
    const router = await mount(); button("打开详情").click(); await vi.waitFor(() => expect(host.textContent).toContain("正在读取审核详情"));
    await router.push(`/admin/teacher-review?applicationId=${otherId}`); await vi.waitFor(() => expect(api.detail).toHaveBeenCalledTimes(2));
    resolve({ ...detail(), displayName: "旧申请不能回来" }); await nextTick(); expect(host.textContent).not.toContain("旧申请不能回来");
    api.list.mockResolvedValue({ ...list(), items: [], total: 0 }); await router.push("/admin/teacher-review?page=2");
    await vi.waitFor(() => expect(host.textContent).toContain("没有符合条件"));
  });
});
