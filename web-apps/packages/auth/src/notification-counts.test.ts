// @vitest-environment jsdom
import { createApp, nextTick, reactive } from "vue";
import type { App } from "vue";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import type { AuthStore } from "./index";
import { invalidateNotificationCounts, useNotificationCounts } from "./notification-counts";
const api = vi.hoisted(() => ({ counts: vi.fn() }));
vi.mock("@edu/api-client", () => ({ createOperationsApi: () => api }));
let app: App, host: HTMLDivElement;
let state: { status: string; currentUser: { id: string; permissions: string[] } | null };
let result: ReturnType<typeof useNotificationCounts>;
beforeEach(() => { vi.resetAllMocks(); state = reactive({ status: "authenticated", currentUser: { id: "test-a", permissions: ["learning:access"] } }); host = document.createElement("div"); document.body.append(host); });
afterEach(() => { app?.unmount(); host.remove(); });
async function mount() { app = createApp({ setup() { result = useNotificationCounts({ state } as unknown as AuthStore); return () => null; } }); app.mount(host); await nextTick(); }
describe("notification count lifecycle", () => {
  it("keeps an unavailable count unknown without polling", async () => {
    api.counts.mockRejectedValue(new TypeError("offline")); await mount(); await vi.waitFor(() => expect(result.unavailable.value).toBe(true));
    expect(result.counts.value).toBeNull(); invalidateNotificationCounts(); expect(api.counts).toHaveBeenCalledOnce();
  });
  it("merges invalidation requests and aborts on leave", async () => {
    api.counts.mockImplementation(() => new Promise(() => {})); await mount(); invalidateNotificationCounts(); invalidateNotificationCounts();
    expect(api.counts).toHaveBeenCalledOnce(); app.unmount(); expect(api.counts.mock.calls[0][0].aborted).toBe(true);
  });
  it("drops the old identity result even when an aborted transport resolves", async () => {
    let finish: (value: { unreadCount: number; unconfirmedAnnouncementCount: number }) => void = () => {};
    api.counts.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValue({ unreadCount: 1, unconfirmedAnnouncementCount: 0 });
    await mount(); state.currentUser = { id: "test-b", permissions: ["learning:access"] }; await nextTick();
    expect(api.counts.mock.calls[0][0].aborted).toBe(true); finish({ unreadCount: 99, unconfirmedAnnouncementCount: 99 });
    await vi.waitFor(() => expect(result.counts.value?.unreadCount).toBe(1));
  });
  it("does not treat an identical current-user replacement as an identity change", async () => {
    api.counts.mockResolvedValue({ unreadCount: 2, unconfirmedAnnouncementCount: 1 }); await mount();
    await vi.waitFor(() => expect(result.counts.value?.unreadCount).toBe(2)); state.currentUser = { id: "test-a", permissions: ["learning:access"] }; await nextTick();
    expect(api.counts).toHaveBeenCalledOnce();
  });
});
