import type { PendingPracticeAction } from "@edu/api-client";
import { describe, expect, it, vi } from "vitest";

import { practiceRegenerationPrompt, runPracticeCommand } from "./practice-action";

function pending(): PendingPracticeAction {
  return { actionId: "11111111-1111-4111-8111-111111111111", conversationId: "22222222-2222-4222-8222-222222222222",
    criteria: { topic: "一元二次方程", difficulty: "MEDIUM", requestedCount: 5 }, requestedCount: 5,
    actualCount: 3, candidateQuestions: [], status: "PENDING", revision: 2, expiresAt: "2099-01-01T00:00:00Z" };
}

describe("T15C practice action helpers", () => {
  it("merges concurrent confirmation and reuses one server idempotency key", async () => {
    let finish!: (value: PendingPracticeAction) => void;
    const command = vi.fn((_requestId: string, _revision: number) =>
      new Promise<PendingPracticeAction>(resolve => { finish = resolve; }));
    const first = runPracticeCommand(pending(), "confirm", command);
    const second = runPracticeCommand(pending(), "confirm", command);
    expect(first).toBe(second); expect(command).toHaveBeenCalledOnce();
    const [requestId, revision] = command.mock.calls[0]!;
    expect(requestId).toMatch(/^[0-9a-f-]{36}$/); expect(revision).toBe(2);
    finish({ ...pending(), status: "EXECUTING", revision: 3 });
    await expect(first).resolves.toMatchObject({ status: "EXECUTING", revision: 3 });
  });

  it("creates only a new suggestion request and never carries question or assessment identities", () => {
    const prompt = practiceRegenerationPrompt("函数图像", "HARD", 8);
    expect(prompt).toContain("主题“函数图像”"); expect(prompt).toContain("难度困难"); expect(prompt).toContain("数量8道");
    expect(prompt).toContain("不要自动创建、开始、提交或评分");
    expect(prompt).not.toMatch(/questionId|assessmentId|versionId|actionId/);
  });
});
