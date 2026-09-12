// @vitest-environment jsdom
import { createApp, h, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AiHomePage from "./views/AiHomePage.vue";

const api = vi.hoisted(() => ({ conversations: vi.fn(), preference: vi.fn(), createConversation: vi.fn(), updatePreference: vi.fn() }));
vi.mock("./agent-api", () => ({ studentAgentApi: api }));
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => { vi.resetAllMocks(); host = document.createElement("div"); document.body.append(host); api.conversations.mockResolvedValue({ items: [], page: 1, size: 30, total: 0 }); api.preference.mockResolvedValue({ value: "GUIDED_STEP_BY_STEP", revision: 0, explicitlySet: false }); });
afterEach(() => { app?.unmount(); host.remove(); });
async function mount() { const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/ai", component: AiHomePage }, { path: "/ai/conversations/:id", component: { template: "<p>会话页</p>" } }] }); await router.push("/ai"); await router.isReady(); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await nextTick(); return router; }
describe("T15A entry component", () => {
  it("distinguishes loading/empty and creates an independent deep link", async () => { let resolve!: (value: unknown) => void; api.conversations.mockImplementationOnce(() => new Promise(done => { resolve = done; })); const router = await mount(); expect(host.textContent).toContain("正在读取会话"); resolve({ items: [], page: 1, size: 30, total: 0 }); await vi.waitFor(() => expect(host.textContent).toContain("还没有会话")); api.createConversation.mockResolvedValue({ id: "conversation-1" }); [...host.querySelectorAll("button")].find(button => button.textContent?.includes("新建会话"))!.click(); await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/ai/conversations/conversation-1")); expect(api.createConversation).toHaveBeenCalledOnce(); });
  it("saves only the two public preference values with its current revision", async () => { api.updatePreference.mockResolvedValue({ value: "COMPLETE_SOLUTION", revision: 1, explicitlySet: true }); await mount(); await vi.waitFor(() => expect(host.querySelector("select")).not.toBeNull()); const select = host.querySelector("select")!; expect([...select.options].map(option => option.value)).toEqual(["GUIDED_STEP_BY_STEP", "COMPLETE_SOLUTION"]); select.value = "COMPLETE_SOLUTION"; select.dispatchEvent(new Event("change", { bubbles: true })); await vi.waitFor(() => expect(api.updatePreference).toHaveBeenCalledWith("COMPLETE_SOLUTION", 0, expect.any(String))); });
  it("keeps a successful recent list visible and keyboard-focusable", async () => { api.conversations.mockResolvedValue({ items: [{ id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "一次方程", status: "ACTIVE", revision: 1, createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" }], page: 1, size: 30, total: 1 }); await mount(); await vi.waitFor(() => expect(host.textContent).toContain("一次方程")); const link = host.querySelector<HTMLAnchorElement>('a[href="/ai/conversations/conversation-1"]')!; link.focus(); expect(document.activeElement).toBe(link); });
});
