// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayRequestError } from "@edu/api-client";
import type { AccountPermissionAction, AccountPermissionCommand, AdminUser } from "@edu/api-client";
import Page from "./views/AccountPermissionsPage.vue";
import { authStore } from "./auth";
const api = vi.hoisted(() => ({ list: vi.fn(), detail: vi.fn(), preview: vi.fn(), change: vi.fn(), latest: vi.fn(), command: vi.fn(), retry: vi.fn() }));
vi.mock("./account-permissions-api", () => ({ accountPermissionsApi: api }));
vi.mock("./auth", async () => ({ authStore: { state: (await import("vue")).reactive({ status: "authenticated", currentUser: { id: "admin", roles: ["ADMIN"], permissions: ["administration:access"] } }) } }));
const id = "00000000-0000-4000-8000-000000000001"; const other = "00000000-0000-4000-8000-000000000002";
const commandId = "00000000-0000-4000-8000-000000000101";
const user = (userId = id): AdminUser => ({ id: userId, account: "synthetic", displayName: "student_test_01", maskedIdentities: ["st***@example.invalid"],
  roles: ["STUDENT", "TEACHER"], permissions: ["learning:access", "teaching:access"], status: "ACTIVE", loginLocked: false,
  revision: 2, sessionVersion: 1, createdAt: "2026-10-13T00:00:00Z", updatedAt: "2026-10-13T00:00:00Z" });
const fact = (status: AccountPermissionCommand["coordinationStatus"] = "NOT_REQUIRED", attempts = 0): AccountPermissionCommand => ({
  commandId, userId: id, action: "REVOKE_TEACHING", coordinationStatus: status, attempts, maxAttempts: 6, affectedCourses: 2, decidedRevision: 3, accessRevision: 1, replayed: false });
function failure(status: number, headers?: HeadersInit) { return new GatewayRequestError(new Response(null, { status, headers }), {
  code: "TEST.ERROR", message: "安全错误", traceId: "synthetic", timestamp: "2026-10-13T00:00:00Z", path: "/synthetic", fieldErrors: [{ field: "reason", reason: "合成字段错误" }] }); }
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset()); api.list.mockResolvedValue({ items: [user()], page: 1, size: 20, total: 21 });
  api.detail.mockImplementation(async (userId: string) => user(userId)); api.latest.mockResolvedValue(null); api.command.mockResolvedValue(fact()); api.change.mockResolvedValue(fact()); api.retry.mockResolvedValue(fact("PENDING", 3));
  api.preview.mockImplementation(async (userId: string, action: AccountPermissionAction) => ({ previewId: commandId, userId, action, expectedRevision: 2,
    affectedCourses: 2, affectedStudents: 3, expiresAt: new Date(Date.now() + 300000).toISOString() }));
  Object.assign(authStore.state.currentUser!, { id: "admin", roles: ["ADMIN"], permissions: ["administration:access"] });
  Object.defineProperty(document, "hidden", { configurable: true, value: false }); Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
  vi.spyOn(window, "confirm").mockReturnValue(true); host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks(); vi.useRealTimers(); });
async function mount(query = "", maxPolls = 30) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/admin/users", name: "admin-users", component: Page, props: { maxPolls, pollIntervalMs: 500 } },
    { path: "/elsewhere", component: { template: "<p>其他页面</p>" } }] });
  await router.push(`/admin/users${query}`); await router.isReady(); app = createApp({ template: "<RouterView />" }); app.use(router); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).not.toMatch(/正在读取|正在查询/)); return router;
}
function button(label: string) { return [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === label)!; }
async function input(selector: string, value: string) { const node = host.querySelector(selector) as HTMLInputElement; node.value = value; node.dispatchEvent(new Event("input", { bubbles: true })); await nextTick(); }
async function prepare(action = "撤销教学能力") {
  button(`预览${action}`).click(); await vi.waitFor(() => expect(host.querySelector("textarea")).not.toBeNull());
  await input("textarea", "合成测试权限变更理由，已核对影响范围。");
  const check = host.querySelector("input[type=checkbox]") as HTMLInputElement; check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
}
describe("account permission page state and safety", () => {
  it("reads one bounded page without prefetch and removes private/invalid URL fields", async () => {
    const router = await mount("?page=2&status=ACTIVE&keyword=secret&reason=secret&sort=roles#secret");
    expect(api.list).toHaveBeenCalledWith({ page: 2, size: 20, status: "ACTIVE", keyword: undefined }, expect.any(AbortSignal));
    expect(api.detail).not.toHaveBeenCalled(); expect(api.latest).not.toHaveBeenCalled(); expect(router.currentRoute.value.fullPath).toBe("/admin/users?page=2&status=ACTIVE");
  });
  it("opens an off-page deep link via detail/latest/id and handles empty latest", async () => {
    api.list.mockResolvedValue({ items: [], page: 2, size: 20, total: 0 }); await mount(`?page=2&userId=${id}&panel=detail`);
    expect(api.detail).toHaveBeenCalledWith(id, expect.any(AbortSignal)); expect(api.latest).toHaveBeenCalledTimes(1);
    expect(api.command).not.toHaveBeenCalled(); expect(host.textContent).toContain("204，无正文"); expect(host.textContent).toContain("本页暂无账号");
  });
  it("keeps keyword in memory and supports selection, panel, browser back and focus return", async () => {
    const router = await mount(); await input(".account-keyword input", "test@example.invalid"); button("查询账号").click();
    await nextTick();
    await vi.waitFor(() => expect(button("查看 student_test_01")).toBeDefined()); expect(api.list).toHaveBeenCalledTimes(2); expect(router.currentRoute.value.fullPath).not.toContain("example");
    button("查看 student_test_01").focus(); button("查看 student_test_01").click(); await vi.waitFor(() => expect(api.detail).toHaveBeenCalledTimes(1));
    button("协调状态").click(); await vi.waitFor(() => expect(router.currentRoute.value.query.panel).toBe("coordination")); router.back();
    await vi.waitFor(() => expect(router.currentRoute.value.query.panel).toBe("detail")); button("关闭详情").click(); await vi.waitFor(() => expect(document.activeElement).toBe(button("查看 student_test_01")));
  });
  it("requires preview, reason, checkbox and second confirmation; 202 remains pending", async () => {
    api.change.mockResolvedValue(fact("PENDING")); api.latest.mockResolvedValue(null); api.command.mockResolvedValue(fact("PENDING"));
    await mount(`?userId=${id}`); await prepare(); vi.mocked(window.confirm).mockReturnValueOnce(false); button("确认并提交撤销教学能力").click();
    await nextTick(); expect(api.change).not.toHaveBeenCalled(); button("确认并提交撤销教学能力").click();
    await vi.waitFor(() => expect(api.change).toHaveBeenCalledTimes(1)); expect(api.change.mock.calls[0][1]).toMatchObject({ expectedRevision: 2, previewId: commandId, confirmed: true });
    await vi.waitFor(() => expect(host.textContent).toContain("课程协调处理中")); expect(host.textContent).not.toContain("Course已确认");
  });
  it("expires the preview and revokes confirmation", async () => {
    await mount(`?userId=${id}`); vi.useFakeTimers(); await prepare(); await vi.advanceTimersByTimeAsync(300001); await nextTick();
    expect(host.textContent).toContain("预览已过期"); expect(button("确认并提交撤销教学能力").disabled).toBe(true);
  });
  /** 丢回执后尝试离页、修改输入和三次重发，断言原ID/原载荷始终相同且不自动创建命令。 */
  it("protects unknown writes and limits retries to the same three snapshots", async () => {
    api.change.mockRejectedValue(new TypeError("network")); const router = await mount(`?userId=${id}`); await prepare(); button("确认并提交撤销教学能力").click();
    await vi.waitFor(() => expect(host.textContent).toContain("写入结果未知")); vi.mocked(window.confirm).mockReturnValue(false); await router.push("/elsewhere"); expect(router.currentRoute.value.name).toBe("admin-users");
    button("重试原请求（1/3）").click(); await vi.waitFor(() => expect(button("重试原请求（2/3）").disabled).toBe(false)); button("重试原请求（2/3）").click();
    await vi.waitFor(() => expect(button("重试原请求（3/3）").disabled).toBe(true)); expect(api.change.mock.calls.map(call => call[1])).toEqual(Array(3).fill(api.change.mock.calls[0][1]));
  });
  it("times out unknown writes and removes requests/timers on unmount", async () => {
    await mount(`?userId=${id}`); await prepare(); api.change.mockReturnValue(new Promise(() => {})); vi.useFakeTimers(); button("确认并提交撤销教学能力").click(); await nextTick();
    await vi.advanceTimersByTimeAsync(15001); await nextTick(); expect(host.textContent).toContain("请求超时"); expect(api.change.mock.calls[0][2].aborted).toBe(true);
    app!.unmount(); app = undefined; expect(vi.getTimerCount()).toBe(0);
  });
  /** latest不包含原请求ID；管理员必须实际重读事实再明确核对，旧详情不能自动解除未知结果。 */
  it("requires a fresh account/latest read before explicitly ending an unknown request", async () => {
    api.change.mockRejectedValue(new TypeError("lost receipt")); await mount(`?userId=${id}`); await prepare(); button("确认并提交撤销教学能力").click();
    await vi.waitFor(() => expect(host.textContent).toContain("写入结果未知")); expect(button("已核对，结束本地未决请求").disabled).toBe(true);
    api.latest.mockResolvedValue(fact()); button("刷新账号与最近命令并核对").click();
    await vi.waitFor(() => expect(button("已核对，结束本地未决请求").disabled).toBe(false)); expect(host.textContent).toContain("写入结果未知");
    button("已核对，结束本地未决请求").click(); await nextTick(); expect(host.textContent).not.toContain("写入结果未知"); expect(api.change).toHaveBeenCalledTimes(1);
  });
  it("recovers another administrator's returned ID and rejects a mismatched account", async () => {
    api.latest.mockResolvedValue(fact("FAILED", 3)); api.command.mockResolvedValue({ ...fact("FAILED", 3), userId: other });
    await mount(`?userId=${id}`); expect(api.command).toHaveBeenCalledWith(commandId, expect.any(AbortSignal)); expect(host.textContent).not.toContain("补偿原命令一次");
    expect(button("预览封禁账号").disabled).toBe(true);
  });
  it("polls finitely and re-reads revision after APPLIED, without claiming downstream delivery", async () => {
    api.latest.mockResolvedValue(fact("PENDING")); api.command.mockResolvedValue(fact("PENDING")); await mount(`?userId=${id}`, 2);
    vi.useFakeTimers(); document.dispatchEvent(new Event("visibilitychange")); await vi.advanceTimersByTimeAsync(1501); await nextTick();
    expect(api.command).toHaveBeenCalledTimes(3); expect(host.textContent).toContain("自动查询已达上限");
    api.command.mockResolvedValue(fact("APPLIED", 1)); api.detail.mockResolvedValue({ ...user(), revision: 4, roles: ["STUDENT"], permissions: ["learning:access"] });
    button("查询当前命令状态").click(); await nextTick(); await vi.advanceTimersByTimeAsync(1); await nextTick();
    expect(host.textContent).toContain("Course已确认"); expect(host.textContent).toContain("不表示全部Learning消费者或通知已完成"); expect(host.textContent).toContain("恢复教学能力");
  });
  it("stops polling while hidden or offline and aborts old responses when selection changes", async () => {
    api.latest.mockResolvedValue(fact("PENDING")); api.command.mockResolvedValue(fact("PENDING")); const router = await mount(`?userId=${id}`);
    vi.useFakeTimers(); Object.defineProperty(document, "hidden", { value: true, configurable: true }); document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(3000); expect(api.command).toHaveBeenCalledTimes(1);
    Object.defineProperty(navigator, "onLine", { value: false, configurable: true }); window.dispatchEvent(new Event("offline")); await nextTick(); expect(host.textContent).toContain("当前离线");
    Object.defineProperty(navigator, "onLine", { value: true, configurable: true }); window.dispatchEvent(new Event("online"));
    let resolve!: (value: AdminUser) => void; api.detail.mockReturnValueOnce(new Promise<AdminUser>(done => { resolve = done; })); button("刷新账号与最近命令并核对").click(); await nextTick();
    const oldSignal = api.detail.mock.calls.at(-1)![1]; api.latest.mockResolvedValue(null); await router.push(`/admin/users?userId=${other}`); resolve(user()); await nextTick();
    expect(oldSignal.aborted).toBe(true); await vi.advanceTimersByTimeAsync(1); await nextTick(); expect(host.querySelector(".account-facts")?.textContent ?? "").not.toContain(id);
  });
  it("compensates only FAILED at 3..5 attempts, retaining stable retry ID/expectedAttempts", async () => {
    api.latest.mockResolvedValue(fact("FAILED", 3)); api.command.mockResolvedValue(fact("FAILED", 3)); api.retry.mockRejectedValue(failure(503)); await mount(`?userId=${id}&panel=coordination`);
    button("补偿原命令一次").click(); await vi.waitFor(() => expect(button("重试原请求（1/3）").disabled).toBe(false)); button("重试原请求（1/3）").click(); await vi.waitFor(() => expect(api.retry).toHaveBeenCalledTimes(2));
    expect(api.retry.mock.calls[0][1]).toEqual(api.retry.mock.calls[1][1]); expect(api.retry.mock.calls[0][1].expectedAttempts).toBe(3);
  });
  it.each([0, 2, 6])("disallows compensation at attempt %s", async attempts => {
    api.latest.mockResolvedValue(fact("FAILED", attempts)); api.command.mockResolvedValue(fact("FAILED", attempts)); await mount(`?userId=${id}`);
    expect(button("补偿原命令一次").disabled).toBe(true); if (attempts === 6) expect(host.textContent).toContain("预算已耗尽");
  });
  it("makes 409 require explicit refresh and a new preview, retains reason on 422", async () => {
    await mount(`?userId=${id}`); await prepare(); api.change.mockRejectedValueOnce(failure(409)); button("确认并提交撤销教学能力").click();
    await vi.waitFor(() => expect(host.textContent).toContain("不能覆盖旧决定")); expect(button("预览撤销教学能力").disabled).toBe(true);
    button("刷新账号与最近命令并核对").click(); await vi.waitFor(() => expect(button("预览撤销教学能力").disabled).toBe(false));
    await prepare(); api.change.mockRejectedValueOnce(failure(422)); button("确认并提交撤销教学能力").click(); await vi.waitFor(() => expect(host.textContent).toContain("合成字段错误"));
    await prepare(); expect((host.querySelector("textarea") as HTMLTextAreaElement).value).toContain("合成测试");
  });
  it.each([401, 403])("clears private state for %s and identity changes", async status => {
    await mount(`?userId=${id}`); await prepare(); api.change.mockRejectedValue(failure(status)); button("确认并提交撤销教学能力").click();
    await vi.waitFor(() => expect(host.querySelector("textarea")).toBeNull()); expect(host.querySelector(".account-rows")).toBeNull();
  });
  it("clears old identity in-flight requests and does not allow retry", async () => {
    await mount(`?userId=${id}`); await prepare(); api.change.mockReturnValue(new Promise(() => {})); button("确认并提交撤销教学能力").click(); await nextTick();
    authStore.state.currentUser!.id = "next-admin"; await nextTick(); expect(api.change.mock.calls[0][2].aborted).toBe(true); expect(host.querySelector("textarea")).toBeNull();
  });
  it.each([404, 422, 429, 503])("shows partial detail/command %s without losing a successful list", async status => {
    api.latest.mockRejectedValue(failure(status, status === 429 ? { "Retry-After": "1" } : undefined)); await mount(`?userId=${id}`);
    expect(host.querySelector(".account-rows")).not.toBeNull(); expect(button("预览封禁账号").disabled).toBe(true);
    if (status === 429) { vi.useFakeTimers(); expect(button("刷新列表").disabled).toBe(true); }
  });
  it("shows admin separately, self ban unavailable, ordinary UNBAN independent of teaching", async () => {
    api.detail.mockResolvedValue({ ...user(), id, roles: ["ADMIN"], permissions: ["administration:access"] }); authStore.state.currentUser!.id = id;
    await mount(`?userId=${id}`); expect(host.textContent).toContain("禁止自封禁"); expect(button("预览封禁账号")).toBeUndefined();
    api.detail.mockResolvedValue({ ...user(), status: "DISABLED", roles: [], permissions: [] }); button("刷新账号与最近命令并核对").click();
    await vi.waitFor(() => expect(button("预览解封账号")).toBeDefined()); expect(button("预览恢复教学能力")).toBeUndefined(); expect(host.textContent).toContain("解封只恢复学生能力");
  });
  it("does not fetch protected facts for a non-administrator", async () => {
    authStore.state.currentUser!.roles = ["STUDENT"] as never; authStore.state.currentUser!.permissions = ["learning:access"];
    await mount(`?userId=${id}`); expect(api.list).not.toHaveBeenCalled(); expect(api.detail).not.toHaveBeenCalled();
  });
});
