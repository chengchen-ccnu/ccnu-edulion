// @vitest-environment jsdom
import { createApp, h, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayRequestError } from "@edu/api-client";
import AiHomePage from "./views/AiHomePage.vue";
import AiInputMenu from "./components/AiInputMenu.vue";

const api = vi.hoisted(() => ({ conversations: vi.fn(), preference: vi.fn(), createConversation: vi.fn(), updatePreference: vi.fn(),
  conversation: vi.fn(), messages: vi.fn(), events: vi.fn(), cancel: vi.fn(), send: vi.fn(), regenerate: vi.fn(),
  renameConversation: vi.fn(), archiveConversation: vi.fn(),
  messageQuestionMediaGrant: vi.fn(), pendingPracticeActionForConversation: vi.fn(), pendingPracticeAction: vi.fn(),
  confirmPendingPracticeAction: vi.fn(), cancelPendingPracticeAction: vi.fn() }));
vi.mock("./agent-api", () => ({ studentAgentApi: api }));
const upload = vi.hoisted(() => vi.fn());
vi.mock("./ai-image-upload", () => ({ uploadAiQuestionImage: upload }));
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => { vi.resetAllMocks(); Object.defineProperty(window, "matchMedia", { configurable: true, value: vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })) }); host = document.createElement("div"); document.body.append(host); api.conversations.mockResolvedValue({ items: [], page: 1, size: 30, total: 0 }); api.preference.mockResolvedValue({ value: "GUIDED_STEP_BY_STEP", revision: 0, explicitlySet: false }); api.pendingPracticeActionForConversation.mockRejectedValue(new GatewayRequestError(new Response(null, { status: 404 }))); api.cancel.mockResolvedValue({ runId: "run-1", status: "CANCELLED", terminal: true }); upload.mockResolvedValue("media-pasted"); });
afterEach(() => { app?.unmount(); host.remove(); });
async function mount() { const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/ai", component: AiHomePage }, { path: "/ai/conversations/:id", component: { template: "<p>会话页</p>" } }] }); await router.push("/ai"); await router.isReady(); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await nextTick(); return router; }
async function mountWorkspace(path: string) { const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/ai", component: AiHomePage }, { path: "/ai/conversations/:conversationId", component: AiHomePage }] }); await router.push(path); await router.isReady(); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await nextTick(); return router; }
describe("T15A entry component", () => {
  it("supports arrow navigation and Escape focus restoration in the plus menu", async () => {
    app = createApp({ render: () => h(AiInputMenu) }); app.mount(host); const trigger = host.querySelector<HTMLButtonElement>('[aria-label="添加内容"]')!;
    trigger.click(); await nextTick(); const items = [...host.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')];
    expect(document.activeElement).toBe(items[0]); items[0]!.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
    expect(document.activeElement).toBe(items[1]); items[1]!.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await nextTick(); expect(document.activeElement).toBe(trigger); expect(host.querySelector('[role="menu"]')).toBeNull();
  });
  it("renames with revision, confirms archive, and keeps the menu owner scoped", async () => {
    const item = { id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "原标题", status: "ACTIVE", revision: 2, activeRunId: null, createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [item], page: 1, size: 30, total: 1 });
    api.renameConversation.mockResolvedValue({ ...item, title: "新标题", revision: 3 }); api.archiveConversation.mockResolvedValue(undefined);
    vi.spyOn(window, "confirm").mockReturnValue(true); await mount(); await vi.waitFor(() => expect(host.textContent).toContain("原标题"));
    [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.getAttribute("aria-label") === "会话操作")!.click();
    await nextTick();
    [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "重命名")!.click(); await nextTick();
    const input = host.querySelector<HTMLInputElement>(".ai-rename input")!; input.value = "新标题"; input.dispatchEvent(new Event("input", { bubbles: true }));
    host.querySelector<HTMLFormElement>(".ai-rename")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(api.renameConversation).toHaveBeenCalledWith(item.id, "新标题", 2, expect.any(String)));
    await vi.waitFor(() => expect(host.textContent).toContain("新标题"));
    [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.getAttribute("aria-label") === "会话操作")!.click();
    await nextTick();
    [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "删除对话")!.click();
    await vi.waitFor(() => expect(api.archiveConversation).toHaveBeenCalledWith(item.id, 3, expect.any(String)));
    expect(window.confirm).toHaveBeenCalledOnce(); await vi.waitFor(() => expect(host.textContent).not.toContain("新标题"));
  });

  it("keeps stop in the composer, sends one public capability once, and uploads pasted images without blocking text", async () => {
    const item = { id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "测试", status: "ACTIVE", revision: 1, activeRunId: null, createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [item], page: 1, size: 30, total: 1 }); api.conversation.mockResolvedValue(item);
    api.messages.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 }); api.events.mockResolvedValue({ runId: "run-1", status: "CANCELLED", items: [] });
    api.send.mockResolvedValue(new Response(new ReadableStream({ start() {} }), { headers: { "X-Agent-Run-Id": "run-1", "Content-Type": "text/event-stream" } }));
    await mountWorkspace("/ai/conversations/conversation-1"); await vi.waitFor(() => expect(host.querySelector("textarea")).not.toBeNull());
    const textarea = host.querySelector<HTMLTextAreaElement>("textarea")!; const paste = new Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(paste, "clipboardData", { value: { files: [new File(["img"], "题图.png", { type: "image/png" })] } }); textarea.dispatchEvent(paste);
    await vi.waitFor(() => expect(upload).toHaveBeenCalled()); expect(paste.defaultPrevented).toBe(false);
    [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.getAttribute("aria-label") === "添加内容")!.click();
    await nextTick(); [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "生成自主练习")!.click();
    await nextTick(); expect(host.textContent).toContain("下一条：生成自主练习"); textarea.value = "一元一次方程三道题"; textarea.dispatchEvent(new Event("input", { bubbles: true }));
    const lineBreak = new KeyboardEvent("keydown", { key: "Enter", shiftKey: true, bubbles: true, cancelable: true });
    textarea.dispatchEvent(lineBreak); expect(lineBreak.defaultPrevented).toBe(false); expect(api.send).not.toHaveBeenCalled();
    const composing = new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true, cancelable: true });
    textarea.dispatchEvent(composing); expect(composing.defaultPrevented).toBe(false); expect(api.send).not.toHaveBeenCalled();
    const enter = new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true });
    textarea.dispatchEvent(enter); expect(enter.defaultPrevented).toBe(true);
    await vi.waitFor(() => expect(api.send).toHaveBeenCalled()); const body = api.send.mock.calls[0][1];
    expect(body.selectedCapability).toBe("GENERATE_AUTONOMOUS_PRACTICE"); expect(body.context.aiQuestionMediaIds).toEqual(["media-pasted"]);
    const stop = [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "停止生成")!;
    expect(stop.closest(".ai-composer")).not.toBeNull(); expect(host.querySelector(".ai-chat__header")?.textContent).not.toContain("停止生成");
    await vi.waitFor(() => expect(host.textContent).not.toContain("下一条：生成自主练习"));
  });
  it("does not render a legacy zero-candidate action in the independent AI workspace", async () => {
    const item = { id: "conversation-zero", entryPoint: "INDEPENDENT_CHAT", title: "空候选", status: "ACTIVE", revision: 1,
      activeRunId: null, createdAt: "2026-09-14T00:00:00Z", updatedAt: "2026-09-14T00:00:00Z", lastMessageAt: "2026-09-14T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [item], page: 1, size: 30, total: 1 }); api.conversation.mockResolvedValue(item);
    api.messages.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 }); api.events.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    api.pendingPracticeActionForConversation.mockResolvedValue({ actionId: "11111111-1111-4111-8111-111111111111",
      conversationId: item.id, criteria: { topic: "函数", difficulty: "MEDIUM", requestedCount: 5 }, requestedCount: 5,
      actualCount: 0, candidateQuestions: [], status: "PENDING", revision: 0, expiresAt: "2099-01-01T00:00:00Z" });
    await mountWorkspace(`/ai/conversations/${item.id}`);
    await vi.waitFor(() => expect(api.pendingPracticeActionForConversation).toHaveBeenCalled());
    expect(host.querySelector(".practice-suggestion")).toBeNull(); expect(host.textContent).not.toContain("等待你明确确认");
  });
  it("distinguishes loading/empty and creates an independent deep link", async () => { let resolve!: (value: unknown) => void; api.conversations.mockImplementationOnce(() => new Promise(done => { resolve = done; })); const router = await mount(); expect(host.textContent).toContain("正在读取会话"); resolve({ items: [], page: 1, size: 30, total: 0 }); await vi.waitFor(() => expect(host.textContent).toContain("还没有会话")); api.createConversation.mockResolvedValue({ id: "conversation-1" }); [...host.querySelectorAll("button")].find(button => button.textContent?.includes("新建会话"))!.click(); await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/ai/conversations/conversation-1")); expect(api.createConversation).toHaveBeenCalledOnce(); });
  it("saves only the two public preference values with its current revision", async () => { api.updatePreference.mockResolvedValue({ value: "COMPLETE_SOLUTION", revision: 1, explicitlySet: true }); await mount(); await vi.waitFor(() => expect(host.querySelector("select")).not.toBeNull()); const select = host.querySelector("select")!; expect([...select.options].map(option => option.value)).toEqual(["GUIDED_STEP_BY_STEP", "COMPLETE_SOLUTION"]); select.value = "COMPLETE_SOLUTION"; select.dispatchEvent(new Event("change", { bubbles: true })); await vi.waitFor(() => expect(api.updatePreference).toHaveBeenCalledWith("COMPLETE_SOLUTION", 0, expect.any(String))); });
  it("keeps a successful recent list visible and keyboard-focusable", async () => { api.conversations.mockResolvedValue({ items: [{ id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "一次方程", status: "ACTIVE", revision: 1, createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" }], page: 1, size: 30, total: 1 }); await mount(); await vi.waitFor(() => expect(host.textContent).toContain("一次方程")); const link = host.querySelector<HTMLAnchorElement>('a[href="/ai/conversations/conversation-1"]')!; link.focus(); expect(document.activeElement).toBe(link); });
  it("uses the same workspace shell for home and conversation and keeps regeneration on the last AI answer", async () => {
    const conversation = { id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "一次方程", status: "ACTIVE", revision: 1, activeRunId: null, createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [conversation], page: 1, size: 30, total: 1 }); api.conversation.mockResolvedValue(conversation);
    api.messages.mockResolvedValue({ items: [
      { id: "user-1", conversationId: "conversation-1", role: "USER", content: "怎么解？", completeness: "COMPLETE", sequenceNo: 1, createdAt: "2026-09-11T00:00:00Z" },
      { id: "assistant-1", conversationId: "conversation-1", role: "ASSISTANT", content: "先移项。", completeness: "COMPLETE", sequenceNo: 2, createdAt: "2026-09-11T00:00:01Z" }
    ], page: 1, size: 100, total: 2 }); api.events.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    const router = await mountWorkspace("/ai"); await vi.waitFor(() => expect(host.querySelector(".ai-welcome")).not.toBeNull());
    await router.push("/ai/conversations/conversation-1");
    await vi.waitFor(() => expect(host.textContent).toContain("先移项"));
    expect(host.querySelectorAll(".ai-workspace")).toHaveLength(1); expect(host.querySelector(".ai-sidebar")).not.toBeNull();
    const regenerate = [...host.querySelectorAll("button")].find(button => button.textContent?.includes("重新生成回答"));
    expect(regenerate?.closest(".ai-message--assistant")).not.toBeNull(); expect(regenerate?.parentElement?.textContent).toContain("可能消耗提问额度");
  });
  it("returns focus to the history trigger when the compact drawer closes with Escape", async () => {
    Object.defineProperty(window, "matchMedia", { configurable: true, value: vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })) });
    await mountWorkspace("/ai"); await vi.waitFor(() => expect(host.textContent).toContain("开始一次数学讨论"));
    const trigger = [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "会话历史")!; trigger.click();
    await vi.waitFor(() => expect(document.activeElement?.textContent).toContain("新建会话"));
    host.querySelector(".ai-sidebar")!.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await vi.waitFor(() => expect(document.activeElement).toBe(trigger));
  });
  it("hides regeneration when the last question has no AI answer", async () => {
    const conversation = { id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "待回答", status: "ACTIVE", revision: 1, activeRunId: null, createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [conversation], page: 1, size: 30, total: 1 }); api.conversation.mockResolvedValue(conversation);
    api.messages.mockResolvedValue({ items: [{ id: "user-1", conversationId: "conversation-1", role: "USER", content: "还没有回答", completeness: "COMPLETE", sequenceNo: 1, createdAt: "2026-09-11T00:00:00Z" }], page: 1, size: 100, total: 1 });
    await mountWorkspace("/ai/conversations/conversation-1"); await vi.waitFor(() => expect(host.textContent).toContain("还没有回答"));
    expect(host.textContent).not.toContain("重新生成回答");
  });
  it("shows regeneration for an interrupted final answer but hides it while a run is active", async () => {
    const conversation = { id: "conversation-1", entryPoint: "INDEPENDENT_CHAT", title: "中断回答", status: "ACTIVE", revision: 1, activeRunId: "run-active", createdAt: "2026-09-11T00:00:00Z", updatedAt: "2026-09-11T00:00:00Z", lastMessageAt: "2026-09-11T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [conversation], page: 1, size: 30, total: 1 }); api.conversation.mockResolvedValue(conversation);
    api.messages.mockResolvedValue({ items: [{ id: "user-1", conversationId: "conversation-1", role: "USER", content: "继续", completeness: "COMPLETE", sequenceNo: 1, createdAt: "2026-09-11T00:00:00Z" }, { id: "assistant-1", conversationId: "conversation-1", role: "ASSISTANT", content: "部分", completeness: "PARTIAL", sequenceNo: 2, createdAt: "2026-09-11T00:00:01Z" }], page: 1, size: 100, total: 2 });
    api.events.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    await mountWorkspace("/ai/conversations/conversation-1"); await vi.waitFor(() => expect(host.textContent).toContain("部分"));
    expect(host.textContent).not.toContain("重新生成回答");
  });
  it("在完整页面用消息绑定题目卡恢复悬浮会话，不把公共题图误当本人上传图片", async () => {
    const conversation = { id: "conversation-floating", entryPoint: "PRACTICE_FLOATING", title: "新的 AI 对话", status: "ACTIVE", revision: 1,
      activeRunId: null, createdAt: "2026-09-12T00:00:00Z", updatedAt: "2026-09-12T00:00:02Z", lastMessageAt: "2026-09-12T00:00:00Z" };
    api.conversations.mockResolvedValue({ items: [conversation], page: 1, size: 30, total: 1 }); api.conversation.mockResolvedValue(conversation);
    api.messages.mockResolvedValue({ items: [{ id: "user-floating", conversationId: conversation.id, role: "USER", content: "怎么想？",
      completeness: "COMPLETE", context: { questionId: "question-1", questionVersionId: "version-1", mediaIds: ["media-1"] },
      questionContextProjection: { status: "AVAILABLE", question: { questionId: "question-1", questionVersionId: "version-1",
        type: "FILL_BLANK", stem: "历史题干", options: [], media: [], children: [] } }, sequenceNo: 1, createdAt: "2026-09-12T00:00:00Z" }],
      page: 1, size: 100, total: 1 }); api.events.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    await mountWorkspace(`/ai/conversations/${conversation.id}`); await vi.waitFor(() => expect(host.textContent).toContain("附带题目"));
    expect(host.textContent).not.toContain("题图暂时无法读取");
  });
});
