import { GatewayRequestError } from "@edu/api-client";
import type { AgentConversation, SystemQuestion } from "@edu/api-client";
import { afterEach, describe, expect, it, vi } from "vitest";

import { capturePracticeDraftContext, isReusablePracticeConversation,
  PRACTICE_FLOATING_IDLE_MS, selectReusablePracticeConversation } from "./practice-floating-chat";
import { agentError } from "./agent-errors";

const baseConversation: AgentConversation = {
  id: "00000000-0000-4000-8000-000000000001", entryPoint: "PRACTICE_FLOATING", title: "临时答疑",
  status: "ACTIVE", revision: 1, activeRunId: null, createdAt: "2026-09-12T00:00:00Z",
  updatedAt: "2026-09-12T00:10:00Z", lastMessageAt: "2026-09-12T00:10:00Z"
};

afterEach(() => vi.useRealTimers());

describe("T15B 悬浮会话与草稿规则", () => {
  it("在 30 分钟内复用并在边界后换新，活动 run 不被计时中断", () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date("2026-09-12T00:39:59Z"));
    expect(isReusablePracticeConversation(baseConversation)).toBe(true);
    vi.setSystemTime(new Date(new Date(baseConversation.updatedAt).getTime() + PRACTICE_FLOATING_IDLE_MS));
    expect(isReusablePracticeConversation(baseConversation)).toBe(false);
    expect(isReusablePracticeConversation({ ...baseConversation, activeRunId: "00000000-0000-4000-8000-000000000002" })).toBe(true);
  });

  it("有终态事件时从运行结束而非消息创建时间计算完整窗口", () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date("2026-09-12T00:50:00Z"));
    const completedAt = Date.parse("2026-09-12T00:21:00Z");
    expect(isReusablePracticeConversation(baseConversation, Date.now(), completedAt)).toBe(true);
    vi.setSystemTime(new Date("2026-09-12T00:51:00Z"));
    expect(isReusablePracticeConversation(baseConversation, Date.now(), completedAt)).toBe(false);
  });

  it("只选择 PRACTICE_FLOATING，不把独立 AI 会话纳入 30 分钟规则", () => {
    const independent = { ...baseConversation, id: "independent", entryPoint: "INDEPENDENT_CHAT" as const };
    expect(selectReusablePracticeConversation([independent, baseConversation], Date.parse("2026-09-12T00:20:00Z"))?.id)
      .toBe(baseConversation.id);
  });

  it("草稿快照只携带当前题目和最多八个去重题图，不含答案或解析", () => {
    const media = Array.from({ length: 10 }, (_, index) => ({ id: `binding-${index}`, mediaId: `media-${index}`,
      purpose: "QUESTION_IMAGE" as const, sequence: index + 1 }));
    const question = { id: "question-1", versionId: "version-1", type: "SINGLE_CHOICE", stem: "题干",
      options: [{ key: "A", content: "选项" }], media, children: [], answers: ["A"], analysis: "不能泄露" } as unknown as SystemQuestion;
    const result = capturePracticeDraftContext(question);
    expect(result.questionId).toBe("question-1"); expect(result.mediaIds).toHaveLength(8);
    expect(JSON.stringify(result)).not.toContain("不能泄露"); expect(JSON.stringify(result)).not.toContain("answers");
  });

  it("为离线和主要 Gateway 状态提供稳定可理解反馈", () => {
    expect(agentError(new TypeError("offline"))).toContain("离线");
    for (const status of [401, 403, 404, 409, 422, 429, 503]) {
      expect(agentError(new GatewayRequestError(new Response(null, { status })))).not.toContain("COMMON");
    }
  });
});
