// @vitest-environment jsdom
import { GatewayRequestError } from "@edu/api-client";
import type { PendingPracticeAction, PendingPracticeActionStatus } from "@edu/api-client";
import { createApp, h, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PracticeSuggestionCard from "./components/PracticeSuggestionCard.vue";

const api = vi.hoisted(() => ({ pendingPracticeActionForConversation: vi.fn(), pendingPracticeAction: vi.fn(),
  confirmPendingPracticeAction: vi.fn(), cancelPendingPracticeAction: vi.fn() }));
vi.mock("./agent-api", () => ({ studentAgentApi: api }));
let app: App | undefined; let host: HTMLDivElement; const regenerate = vi.fn();
let caseNumber = 0;

function action(status: PendingPracticeActionStatus = "PENDING"): PendingPracticeAction {
  const candidateQuestions = Array.from({ length: 3 }, (_, index) => ({
    questionId: `55555555-5555-4555-8555-${String(index).padStart(12, "0")}`,
    questionVersionId: `66666666-6666-4666-8666-${String(index).padStart(12, "0")}`,
    questionType: "SINGLE_CHOICE" as const, difficulty: "MEDIUM" as const
  }));
  return { actionId: `11111111-1111-4111-8111-${String(caseNumber).padStart(12, "0")}`, conversationId: "22222222-2222-4222-8222-222222222222",
    criteria: { topic: "一元二次方程", difficulty: "MEDIUM", requestedCount: 5 }, requestedCount: 5,
    actualCount: 3, candidateQuestions, status, revision: status === "PENDING" ? 2 : 3,
    expiresAt: "2099-01-01T00:00:00Z",
    executionResult: status === "SUCCEEDED" ? { autonomousPracticeId: "33333333-3333-4333-8333-333333333333",
      assessmentId: "44444444-4444-4444-8444-444444444444", startRoute: "/practice/44444444-4444-4444-8444-444444444444" } : null };
}

beforeEach(() => {
  vi.resetAllMocks(); regenerate.mockReset(); caseNumber += 1; host = document.createElement("div"); document.body.append(host);
  api.pendingPracticeActionForConversation.mockResolvedValue(action());
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); });

async function mountCard(expectAction = true) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/", component: { template: "<p />" } }, { path: "/practice/:assessmentId", component: { template: "<p>作答器</p>" } }
  ] });
  await router.push("/"); await router.isReady();
  app = createApp({ render: () => h(PracticeSuggestionCard, {
    conversationId: action().conversationId, onRegenerate: regenerate
  }) }); app.use(router); app.mount(host); await nextTick();
  if (expectAction) await vi.waitFor(() => expect(host.textContent).toContain("一元二次方程"));
}
function button(label: string) {
  return [...host.querySelectorAll<HTMLButtonElement>("button")].find(item => item.textContent?.includes(label))!;
}

describe("T15C suggestion and creation card", () => {
  it("shows executable quantity and attached controls without an inert HTML template", async () => {
    await mountCard();
    expect(host.textContent).toContain("目前找到 3 道符合条件的题，少于期望的 5 道");
    expect(host.textContent).toContain("可练习题数");
    expect(host.querySelector("template")).toBeNull();
    expect(host.textContent).toContain("待确认");
    expect(button("确认创建").disabled).toBe(false);
    expect(host.querySelector("details")?.open).toBe(false);
    expect(host.querySelector(".practice-suggestion__message")).toBeNull();
  });

  it("permanently invalidates old confirmation after an edit and cancels before regeneration", async () => {
    api.cancelPendingPracticeAction.mockResolvedValue({ ...action(), status: "CANCELLED", revision: 3 });
    await mountCard(); const input = host.querySelector<HTMLInputElement>('input[autocomplete="off"]')!;
    input.value = "函数图像"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
    expect(button("确认创建").disabled).toBe(true);
    input.value = "一元二次方程"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
    expect(button("确认创建").disabled).toBe(true);
    button("按新条件重新生成").click();
    await vi.waitFor(() => expect(api.cancelPendingPracticeAction).toHaveBeenCalledWith(action().actionId,
      expect.objectContaining({ expectedRevision: 2, requestId: expect.any(String) }), expect.any(AbortSignal)));
    await vi.waitFor(() => expect(regenerate).toHaveBeenCalledOnce());
  });

  it("blocks duplicate clicks while confirmation is in flight", async () => {
    api.confirmPendingPracticeAction.mockImplementation(() => new Promise(() => {}));
    await mountCard(); const confirm = button("确认创建"); confirm.click(); confirm.click(); await nextTick();
    expect(api.confirmPendingPracticeAction).toHaveBeenCalledOnce(); expect(confirm.disabled).toBe(true);
  });

  it("returns focus to the card when a successful confirmation removes its button", async () => {
    api.confirmPendingPracticeAction.mockResolvedValue(action("SUCCEEDED"));
    await mountCard(); button("确认创建").focus(); button("确认创建").click();
    await vi.waitFor(() => expect(document.activeElement).toBe(host.querySelector(".practice-suggestion")));
    expect(host.querySelector("a")?.textContent).toContain("开始练习");
  });

  it("recovers a lost confirmation response by action query and offers a non-forced start link", async () => {
    api.confirmPendingPracticeAction.mockRejectedValue(new TypeError("response lost"));
    api.pendingPracticeAction.mockResolvedValue(action("SUCCEEDED"));
    await mountCard(); button("确认创建").click();
    await vi.waitFor(() => expect(api.pendingPracticeAction).toHaveBeenCalledWith(action().actionId, expect.any(AbortSignal)));
    await vi.waitFor(() => expect(host.textContent).toContain("练习已创建"));
    const link = host.querySelector<HTMLAnchorElement>('a[href="/practice/44444444-4444-4444-8444-444444444444"]');
    expect(link?.textContent).toContain("开始练习"); expect(link?.getAttribute("href")).toBe("/practice/44444444-4444-4444-8444-444444444444");
    expect(api.confirmPendingPracticeAction).toHaveBeenCalledOnce();
  });

  it.each(["EXECUTING", "FAILED", "EXPIRED", "CANCELLED"] as PendingPracticeActionStatus[])(
    "renders recoverable %s server state", async status => {
      api.pendingPracticeActionForConversation.mockResolvedValue(action(status)); await mountCard();
      expect(host.textContent).toContain({ EXECUTING: "正在创建练习", FAILED: "创建失败",
        EXPIRED: "建议已过期", CANCELLED: "建议已取消", PENDING: "待确认",
        CONFIRMED: "已确认", SUCCEEDED: "练习已创建" }[status]);
      if (["FAILED", "EXPIRED", "CANCELLED"].includes(status)) expect(button("按新条件重新生成")).toBeDefined();
      if (status === "EXECUTING") expect(button("刷新状态")).toBeDefined();
    });

  it("treats conversation 404 as a real empty state and aborts reads on leave", async () => {
    let observed: AbortSignal | undefined;
    api.pendingPracticeActionForConversation.mockImplementation((_id: string, signal: AbortSignal) => {
      observed = signal; return Promise.reject(new GatewayRequestError(new Response(null, { status: 404 })));
    });
    await mountCard(false); await vi.waitFor(() => expect(api.pendingPracticeActionForConversation).toHaveBeenCalled());
    expect(host.querySelector(".practice-suggestion")).toBeNull();
    expect(host.textContent).not.toContain("等待建议");
    app?.unmount(); app = undefined; expect(observed?.aborted).toBe(true);
  });

  it.each([
    { actualCount: 0, candidateQuestions: [] },
    { actualCount: 2, candidateQuestions: action().candidateQuestions.slice(0, 1) }
  ])("does not mount a legacy unusable action: $actualCount", async invalid => {
    api.pendingPracticeActionForConversation.mockResolvedValue({ ...action(), ...invalid });
    await mountCard(false);
    await vi.waitFor(() => expect(api.pendingPracticeActionForConversation).toHaveBeenCalled());
    expect(host.querySelector(".practice-suggestion")).toBeNull();
    expect(host.textContent).not.toContain("自主练习建议");
    expect(host.textContent).not.toContain("待确认");
  });
});
