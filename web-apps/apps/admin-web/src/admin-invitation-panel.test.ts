// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayRequestError } from "@edu/api-client";
import type { AdminInvitation } from "@edu/api-client";
import Page from "./views/AdminInvitationPanel.vue";
import { authStore } from "./auth";
const api = vi.hoisted(() => ({ create: vi.fn(), get: vi.fn(), resend: vi.fn() }));
vi.mock("./admin-invitations-api", () => ({ adminInvitationsApi: api }));
vi.mock("./auth", async () => ({ authStore: { state: (await import("vue")).reactive({ status: "authenticated", currentUser: { id: "admin", roles: ["ADMIN"], permissions: ["administration:access"] } }) } }));
const id = "00000000-0000-4000-8000-000000000001"; const other = "00000000-0000-4000-8000-000000000002";
const fact = (deliveryStatus: AdminInvitation["deliveryStatus"] = "SENT", acceptanceStatus: AdminInvitation["acceptanceStatus"] = "AWAITING", sendAttempts = 1): AdminInvitation => ({
  invitationId: id, maskedEmail: "sy***@example.invalid", deliveryStatus, acceptanceStatus, revision: 3, sendAttempts,
  expiresAt: new Date(Date.now() + 86400000).toISOString(), resendAfter: new Date(Date.now() - 1000).toISOString(), replayed: false });
function failure(status: number) { return new GatewayRequestError(new Response(null, { status, headers: { "Retry-After": "2" } }), { code: "TEST.ERROR", message: "安全错误", traceId: "test", timestamp: "2026-10-22T00:00:00Z", path: "/synthetic", fieldErrors: [{ field: "reason", reason: "合成字段提示" }] }); }
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset()); api.get.mockResolvedValue(fact()); api.create.mockResolvedValue(fact()); api.resend.mockResolvedValue(fact());
  Object.assign(authStore.state, { status: "authenticated", currentUser: { id: "admin", roles: ["ADMIN"], permissions: ["administration:access"] } });
  Object.defineProperty(document, "hidden", { configurable: true, value: false }); Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
  vi.spyOn(window, "confirm").mockReturnValue(true); host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks(); vi.useRealTimers(); });
async function mount(query = "", maxPolls = 30) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/admin/users", name: "admin-users", component: Page, props: { maxPolls, pollIntervalMs: 500 } }, { path: "/elsewhere", component: { template: "<p>其他页面</p>" } }] });
  await router.push(`/admin/users?panel=invite${query}`); await router.isReady(); app = createApp({ template: "<RouterView />" }); app.use(router); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).not.toContain("正在读取")); return router;
}
function button(label: string) { return [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === label)!; }
async function input(selector: string, value: string) { const node = host.querySelector(selector) as HTMLInputElement; node.value = value; node.dispatchEvent(new Event("input", { bubbles: true })); await nextTick(); }
async function create() { await input("input[type=email]", " SYNTHETIC@EXAMPLE.INVALID "); await input("textarea", "合成测试邀请理由，已核对范围。"); button("确认创建邀请").click(); await nextTick(); }
describe("administrator invitation bounded writes and facts", () => {
  it("opens an invitation without userId, filters sensitive URL and provides configured generic address", async () => {
    const router = await mount(`&invitationId=${id}&userId=${other}&commandId=${other}&email=secret&reason=secret#secret`);
    expect(router.currentRoute.value.fullPath).toBe(`/admin/users?panel=invite&invitationId=${id}`); expect(api.get).toHaveBeenCalledWith(id, expect.any(AbortSignal));
    const address = host.querySelector("a")!.href; expect(new URL(address).pathname).toBe("/accept-admin-invitation"); expect(new URL(address).search + new URL(address).hash).toBe("");
    expect(host.textContent).toContain("SMTP已接受"); expect(host.textContent).toContain("等待接受"); expect(host.textContent).toContain("不代表收件箱送达");
  });
  it("does not enumerate invitations and queries a known ID with refresh and browser back", async () => {
    const router = await mount(); expect(api.get).not.toHaveBeenCalled(); expect(host.textContent).toContain("没有全量列表");
    await input("[aria-label='单条邀请状态'] input", id); button("查询邀请").click(); await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));
    await router.push(`/admin/users?panel=invite&invitationId=${other}`); router.back(); await vi.waitFor(() => expect(router.currentRoute.value.query.invitationId).toBe(id));
  });
  it("confirms and normalizes create with202 then clears private form", async () => {
    const router = await mount(); await create(); await vi.waitFor(() => expect(router.currentRoute.value.query.invitationId).toBe(id));
    expect(api.create.mock.calls[0][0]).toMatchObject({ email: "synthetic@example.invalid" }); expect(api.create.mock.calls[0][0].clientRequestId).toMatch(/^[a-f\d-]{36}$/i);
    expect((host.querySelector("input[type=email]") as HTMLInputElement).value).toBe(""); expect(host.textContent).toContain("202提交回执");
  });
  it("does not write when second confirmation is cancelled", async () => {
    await mount(); vi.mocked(window.confirm).mockReturnValue(false); await create(); expect(api.create).not.toHaveBeenCalled();
  });
  it("allows a separately confirmed create after a bound-email409 without inventing an invitation lookup", async () => {
    api.create.mockRejectedValueOnce(failure(409)).mockResolvedValueOnce(fact()); const router = await mount(); await create();
    await vi.waitFor(() => expect(host.textContent).toContain("邮箱已绑定")); expect(api.get).not.toHaveBeenCalled();
    await input("input[type=email]", "another@example.invalid"); button("确认创建邀请").click();
    await vi.waitFor(() => expect(router.currentRoute.value.query.invitationId).toBe(id));
    expect(api.create).toHaveBeenCalledTimes(2); expect(api.create.mock.calls[1][0].clientRequestId).not.toBe(api.create.mock.calls[0][0].clientRequestId);
  });
  /** 创建响应丢失时修改输入、尝试离页、最多三次手动重放，原ID/载荷必须完全一致。 */
  it("retains the exact unknown snapshot and protects leave with a three-send limit", async () => {
    api.create.mockRejectedValue(new TypeError("lost receipt")); const router = await mount(); await create(); await vi.waitFor(() => expect(host.textContent).toContain("写入结果未知"));
    vi.mocked(window.confirm).mockReturnValue(false); await router.push("/elsewhere"); expect(router.currentRoute.value.name).toBe("admin-users");
    button("重放原请求（1/3）").click(); await vi.waitFor(() => expect(button("重放原请求（2/3）").disabled).toBe(false));
    button("重放原请求（2/3）").click(); await vi.waitFor(() => expect(button("重放原请求（3/3）").disabled).toBe(true));
    expect(api.create.mock.calls.map(call => call[0])).toEqual(Array(3).fill(api.create.mock.calls[0][0])); expect(button("确认创建邀请").disabled).toBe(true);
  });
  it.each(["AWAITING", "EXPIRED"] as const)("resends %s only after explicit reason/confirmation using current revision", async status => {
    api.get.mockResolvedValue(fact("FAILED", status)); api.resend.mockRejectedValue(failure(503)); await mount(`&invitationId=${id}`);
    await input("[aria-label='单条邀请状态'] textarea", "合成测试重发理由，请使用新邮件。"); button("确认重发邀请").click(); await vi.waitFor(() => expect(api.resend).toHaveBeenCalledTimes(1));
    expect(api.resend.mock.calls[0][1]).toMatchObject({ expectedRevision: 3 }); await vi.waitFor(() => expect(button("重放原请求（1/3）").disabled).toBe(false)); button("重放原请求（1/3）").click(); await vi.waitFor(() => expect(api.resend).toHaveBeenCalledTimes(2));
    expect(api.resend.mock.calls[1][1]).toEqual(api.resend.mock.calls[0][1]); expect(api.create).not.toHaveBeenCalled();
  });
  it.each(["accepted", "budget", "cooldown"])("disables resend when %s", async kind => {
    api.get.mockResolvedValue({ ...fact("SENT", kind === "accepted" ? "ACCEPTED" : "AWAITING", kind === "budget" ? 6 : 1), resendAfter: new Date(Date.now() + (kind === "cooldown" ? 60000 : -1000)).toISOString() });
    await mount(`&invitationId=${id}`); expect(button("确认重发邀请").disabled).toBe(true);
  });
  it("requires a fresh read after409 and keeps reason on422", async () => {
    await mount(`&invitationId=${id}`); await input("[aria-label='单条邀请状态'] textarea", "合成测试重发理由，请核对。"); api.resend.mockRejectedValueOnce(failure(409)); button("确认重发邀请").click();
    await vi.waitFor(() => expect(host.textContent).toContain("状态有冲突")); expect(button("确认重发邀请").disabled).toBe(true);
    button("刷新邀请状态").click(); await vi.waitFor(() => expect(button("确认重发邀请").disabled).toBe(false));
    api.resend.mockRejectedValueOnce(failure(422)); button("确认重发邀请").click(); await vi.waitFor(() => expect(host.textContent).toContain("合成字段提示"));
    expect((host.querySelector("[aria-label='单条邀请状态'] textarea") as HTMLTextAreaElement).value).toContain("合成测试");
  });
  it("bounds PENDING polls and never automatically resends", async () => {
    api.get.mockResolvedValue(fact("PENDING")); await mount(`&invitationId=${id}`, 2); vi.useFakeTimers();
    // 下一次手动读取在假时钟下启动一轮查询预算。
    button("刷新邀请状态").click(); await nextTick(); await vi.advanceTimersByTimeAsync(1501); await nextTick();
    expect(host.textContent).toContain("自动查询已达上限"); expect(api.get).toHaveBeenCalledTimes(4); expect(api.resend).not.toHaveBeenCalled();
  });
  it.each([401, 403])("clears private state on %s", async status => {
    api.get.mockRejectedValue(failure(status)); await mount(`&invitationId=${id}`); expect(host.querySelector("input")).toBeNull(); expect(host.textContent).toContain("缺少管理权限");
  });
  it("clears private facts when the shared single401 refresh cannot establish authentication", async () => {
    api.get.mockRejectedValue(new Error("AUTHENTICATION_REQUIRED")); await mount(`&invitationId=${id}`);
    expect(host.querySelector("input")).toBeNull(); expect(host.textContent).toContain("缺少管理权限");
  });
  it.each([404, 422, 429, 503])("shows query error %s separately from the create area", async status => {
    api.get.mockRejectedValue(failure(status)); await mount(`&invitationId=${id}`); expect(host.querySelector("input[type=email]")).not.toBeNull(); expect(host.querySelector("dl")).toBeNull();
    if (status === 429) expect(button("确认创建邀请").disabled).toBe(true);
  });
  it.each(["hidden", "offline", "identity", "unmount", "timeout"])("cancels pending writes and cleans resources on %s", async action => {
    api.create.mockReturnValue(new Promise(() => {})); await mount(); vi.useFakeTimers(); await create();
    if (action === "hidden") { Object.defineProperty(document, "hidden", { configurable: true, value: true }); document.dispatchEvent(new Event("visibilitychange")); }
    if (action === "offline") { Object.defineProperty(navigator, "onLine", { configurable: true, value: false }); window.dispatchEvent(new Event("offline")); }
    if (action === "identity") authStore.state.currentUser!.id = "other";
    if (action === "timeout") await vi.advanceTimersByTimeAsync(45001);
    await nextTick(); await vi.advanceTimersByTimeAsync(1); await nextTick();
    if (action === "unmount") { app!.unmount(); app = undefined; }
    expect(api.create.mock.calls[0][1].aborted).toBe(true); if (app) { app.unmount(); app = undefined; } expect(vi.getTimerCount()).toBe(0);
  });
  it("ignores a stale query response after selecting another invitation", async () => {
    let finish!: (value: AdminInvitation) => void; api.get.mockReturnValueOnce(new Promise<AdminInvitation>(resolve => { finish = resolve; }));
    const router = await mount(); await router.push(`/admin/users?panel=invite&invitationId=${id}`); await nextTick(); const oldSignal = api.get.mock.calls[0][1];
    api.get.mockResolvedValue({ ...fact(), invitationId: other }); await router.push(`/admin/users?panel=invite&invitationId=${other}`); finish(fact());
    await vi.waitFor(() => expect(host.querySelector("dl")?.textContent).toContain(other)); expect(oldSignal.aborted).toBe(true); expect(host.querySelector("dl")?.textContent).not.toContain(id);
  });
  it("does not query with a non-administrator identity", async () => {
    authStore.state.currentUser!.roles = ["STUDENT"] as never; await mount(`&invitationId=${id}`); expect(api.get).not.toHaveBeenCalled(); expect(host.querySelector("form")).toBeNull();
  });
});
