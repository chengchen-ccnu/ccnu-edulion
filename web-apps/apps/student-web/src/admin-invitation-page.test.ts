// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayRequestError } from "@edu/api-client";
import Page from "./views/AcceptAdminInvitationPage.vue";
import { authStore } from "./auth";
const accept = vi.hoisted(() => vi.fn());
vi.mock("./admin-invitation-api", () => ({ acceptAdminInvitation: accept }));
vi.mock("./auth", async () => ({ authStore: { state: (await import("vue")).reactive({ status: "anonymous", currentUser: null as null | { id: string } }) } }));
let app: App | undefined; let host: HTMLDivElement;
const id = "00000000-0000-4000-8000-000000000001";
beforeEach(() => {
  accept.mockReset().mockResolvedValue({ userId: id }); Object.assign(authStore.state, { status: "anonymous", currentUser: null });
  Object.defineProperty(document, "hidden", { configurable: true, value: false }); Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks(); vi.useRealTimers(); });
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/accept-admin-invitation", component: Page }, { path: "/login", component: { template: "<p>统一登录</p>" } }] });
  await router.push("/accept-admin-invitation?email=must-not-read&secret=must-not-read#must-not-read"); await router.isReady(); app = createApp({ template: "<RouterView />" }); app.use(router); app.mount(host); await nextTick(); return router;
}
async function input(name: string, value: string) { const node = host.querySelector(`[name=${name}]`) as HTMLInputElement; node.value = value; node.dispatchEvent(new Event("input", { bubbles: true })); await nextTick(); }
async function fill() {
  for (const [name, value] of Object.entries({ invitationId: id, email: " SYNTHETIC@EXAMPLE.INVALID ", secret: "a".repeat(43), displayName: "临时管理员", password: "Synthetic!12345", confirmPassword: "Synthetic!12345" })) await input(name, value);
}
async function submit() { host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await nextTick(); }
function failure(status: number) { return new GatewayRequestError(new Response(null, { status, headers: { "Retry-After": "2" } }), { code: "TEST.ERROR", message: "must-not-expose", traceId: "test", timestamp: "2026-10-22T00:00:00Z", path: "/synthetic", fieldErrors: [{ field: "secret", reason: "must-not-expose" }] }); }
describe("invitation acceptance state and security", () => {
  it("starts empty, normalizes email, clears secrets on201 and offers only unified login", async () => {
    const router = await mount(); expect((host.querySelector("[name=email]") as HTMLInputElement).value).toBe(""); await fill(); await submit();
    await vi.waitFor(() => expect(host.textContent).toContain("已开通，请登录")); expect(accept.mock.calls[0][0]).toMatchObject({ email: "synthetic@example.invalid" });
    expect(host.querySelector("input[type=password]")).toBeNull(); expect(authStore.state.status).toBe("anonymous");
    expect(router.currentRoute.value.path).toBe("/accept-admin-invitation"); expect(host.querySelector("a")?.getAttribute("href")).toBe("/login");
  });
  it.each([new TypeError("offline"), failure(503), failure(500)])("locks unknown results without auto retry or declaring failure", async error => {
    accept.mockRejectedValue(error); await mount(); await fill(); await submit(); await vi.waitFor(() => expect(host.textContent).toContain("开户结果未确认"));
    expect(host.textContent).toContain("尝试正常登录"); expect(host.querySelector("form")).toBeNull(); expect(accept).toHaveBeenCalledTimes(1);
    expect(host.textContent).not.toContain("must-not-expose");
  });
  it.each([401, 403, 404, 409, 422, 429])("shows safe rejection %s without leaking credential validation", async status => {
    accept.mockRejectedValue(failure(status)); await mount(); await fill(); await submit(); await vi.waitFor(() => expect(host.querySelector("[role=alert]")).not.toBeNull());
    expect(host.textContent).not.toContain("must-not-expose"); expect(accept).toHaveBeenCalledTimes(1);
    if (status === 409) expect(host.textContent).toContain("不能转换"); if (status === 422) expect(host.textContent).toContain("邀请无法接受");
    if (status === 429) expect((host.querySelector("button") as HTMLButtonElement).disabled).toBe(true);
  });
  it("validates 43-character secret, visible nickname and UTF8 byte length before any call", async () => {
    await mount(); await fill(); await input("secret", "x".repeat(42)); await input("displayName", "\u200b"); await input("password", "密".repeat(25)); await submit();
    expect(accept).not.toHaveBeenCalled(); expect(host.textContent).toContain("43字符"); expect(host.textContent).toContain("可见字符"); expect(host.textContent).toContain("72字节");
  });
  it("never replaces a known logged-in account", async () => {
    Object.assign(authStore.state, { status: "authenticated", currentUser: { id } }); await mount();
    expect(host.textContent).toContain("当前已有账号登录"); expect(host.querySelector("form")).toBeNull(); expect(accept).not.toHaveBeenCalled();
  });
  /** 请求挂起时超时/隐藏/离线/身份变化均不能据取消宣称失败；卸载清除全部HTTP与定时器。 */
  it.each(["timeout", "hidden", "offline", "identity"])("cancels safely on %s and cleans timers", async action => {
    accept.mockReturnValue(new Promise(() => {})); await mount(); await fill(); vi.useFakeTimers(); await submit();
    if (action === "timeout") await vi.advanceTimersByTimeAsync(45001);
    if (action === "hidden") { Object.defineProperty(document, "hidden", { configurable: true, value: true }); document.dispatchEvent(new Event("visibilitychange")); }
    if (action === "offline") { Object.defineProperty(navigator, "onLine", { configurable: true, value: false }); window.dispatchEvent(new Event("offline")); }
    if (action === "identity") Object.assign(authStore.state, { status: "authenticated", currentUser: { id } });
    await nextTick(); await vi.advanceTimersByTimeAsync(1); await nextTick(); expect(accept.mock.calls[0][1].aborted).toBe(true);
    if (action !== "identity") expect(host.textContent).toContain("开户结果未确认");
    app!.unmount(); app = undefined; expect(vi.getTimerCount()).toBe(0); expect(accept).toHaveBeenCalledTimes(1);
  });
});
