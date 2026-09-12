// @vitest-environment jsdom
import { createApp, h, nextTick, ref } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AgentMessage, SystemQuestion } from "@edu/api-client";

import PracticeFloatingChat from "./components/PracticeFloatingChat.vue";
import AgentQuestionContextCard from "./components/AgentQuestionContextCard.vue";

const api = vi.hoisted(() => ({ conversations: vi.fn(), createConversation: vi.fn(), conversation: vi.fn(),
  messages: vi.fn(), events: vi.fn(), cancel: vi.fn(), send: vi.fn(), regenerate: vi.fn(),
  messageQuestionMediaGrant: vi.fn() }));
vi.mock("./agent-api", () => ({ studentAgentApi: api }));
let app: App | undefined; let host: HTMLDivElement;
const question = (id: string): SystemQuestion => ({ id, versionId: `${id}-version`, scope: "SYSTEM", type: "SINGLE_CHOICE",
  difficulty: "EASY", stage: "JUNIOR", grade: "SEVEN", subject: "MATHEMATICS", stem: `题干 ${id}`,
  options: [{ key: "A", content: "选项 A" }], knowledgePoints: [], media: [{ id: `${id}-binding`,
    mediaId: `${id}-media`, purpose: "QUESTION_IMAGE", sequence: 1 }], children: [], createdAt: "2026-09-12T00:00:00Z",
  updatedAt: "2026-09-12T00:00:00Z" });

beforeEach(() => {
  vi.resetAllMocks(); host = document.createElement("div"); document.body.append(host);
  api.conversations.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
  api.createConversation.mockResolvedValue({ id: "conversation-1", entryPoint: "PRACTICE_FLOATING", title: "悬浮答疑",
    status: "ACTIVE", revision: 0, activeRunId: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), lastMessageAt: new Date().toISOString() });
  api.send.mockImplementation(() => new Promise(() => {}));
});
afterEach(() => { app?.unmount(); host.remove(); });

async function mount(current = ref<SystemQuestion | null>(question("question-1"))) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/", component: { template: "<p />" } },
    { path: "/ai/conversations/:conversationId", component: { template: "<p>完整页面</p>" } }] });
  await router.push("/"); await router.isReady();
  app = createApp({ render: () => h(PracticeFloatingChat, { question: current.value }) }); app.use(router); app.mount(host); await nextTick();
  return { router, current };
}
function button(label: string) { return [...host.querySelectorAll<HTMLButtonElement>("button")].find(item => item.textContent?.includes(label))!; }
async function input(text: string) { const textarea = host.querySelector<HTMLTextAreaElement>("textarea")!; textarea.value = text; textarea.dispatchEvent(new Event("input", { bubbles: true })); await nextTick(); }

describe("T15B 普通自主练习悬浮答疑", () => {
  it("打开不创建会话，第一次发送才创建 PRACTICE_FLOATING 并附当前题目", async () => {
    await mount(); button("答疑").click(); await vi.waitFor(() => expect(api.conversations).toHaveBeenCalledOnce());
    expect(api.createConversation).not.toHaveBeenCalled(); await input("这一步怎么想？"); button("发送").click();
    await vi.waitFor(() => expect(api.createConversation).toHaveBeenCalledWith(expect.any(String), "PRACTICE_FLOATING"));
    await vi.waitFor(() => expect(api.send).toHaveBeenCalledWith("conversation-1", expect.objectContaining({
      content: "这一步怎么想？", context: { questionId: "question-1", questionMediaIds: ["question-1-media"] }
    }), expect.any(AbortSignal)));
  });

  it("切题不卸载面板，未发送草稿保持旧题关联并可主动切到当前题", async () => {
    const state = await mount(); button("答疑").click(); await vi.waitFor(() => expect(api.conversations).toHaveBeenCalled());
    await input("保留这段草稿"); state.current.value = question("question-2"); await nextTick();
    expect(host.textContent).toContain("这段提问仍关联上一道题"); button("切换为当前题目").click(); button("发送").click();
    await vi.waitFor(() => expect(api.send).toHaveBeenCalledWith("conversation-1", expect.objectContaining({
      context: { questionId: "question-2", questionMediaIds: ["question-2-media"] }
    }), expect.any(AbortSignal)));
  });

  it("Escape 关闭面板并把焦点还给带可访问名称的入口", async () => {
    await mount(); const trigger = button("答疑"); expect(trigger.getAttribute("aria-label")).toBe("AI 答疑"); trigger.click();
    await vi.waitFor(() => expect(host.querySelector(".practice-chat")).not.toBeNull());
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await vi.waitFor(() => expect(document.activeElement).toBe(host.querySelector(".practice-chat-trigger")));
  });

  it("历史题目 AVAILABLE 展示精确投影，UNAVAILABLE 不回退当前题", async () => {
    app?.unmount();
    const message: AgentMessage = { id: "message-1", conversationId: "conversation-1", role: "USER", content: "问题",
      completeness: "COMPLETE", sequenceNo: 1, createdAt: "2026-09-12T00:00:00Z",
      questionContextProjection: { status: "AVAILABLE", question: { questionId: "historical-question",
        questionVersionId: "historical-version", type: "SINGLE_CHOICE", stem: "历史题干 {{media:11111111-1111-4111-8111-111111111111}}",
        options: [], media: [{ id: "historical-binding", mediaId: "11111111-1111-4111-8111-111111111111", purpose: "QUESTION_IMAGE",
          sequence: 1 }], children: [] } } };
    app = createApp({ render: () => h(AgentQuestionContextCard, { conversationId: "conversation-1", message }) }); app.mount(host); await nextTick();
    expect(host.textContent).toContain("附带题目"); expect(host.textContent).toContain("历史题干");
    await vi.waitFor(() => expect(api.messageQuestionMediaGrant).toHaveBeenCalledWith(
      "conversation-1", "message-1", "11111111-1111-4111-8111-111111111111", expect.any(AbortSignal)));
    app.unmount(); host.replaceChildren();
    const unavailable = { ...message, questionContextProjection: { status: "UNAVAILABLE" as const, question: null } };
    app = createApp({ render: () => h(AgentQuestionContextCard, { conversationId: "conversation-1", message: unavailable }) }); app.mount(host); await nextTick();
    expect(host.textContent).toContain("该消息附带的题目当前无法查看"); expect(host.textContent).not.toContain("历史题干");
  });
});
