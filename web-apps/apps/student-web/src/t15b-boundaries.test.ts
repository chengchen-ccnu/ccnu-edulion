import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const detail = readFileSync(new URL("./views/SystemQuestionDetailPage.vue", import.meta.url), "utf8");
const forbiddenPages = ["CourseLearningPage.vue", "PracticeAttemptPage.vue", "AttemptResultPage.vue", "WrongQuestionsPage.vue"]
  .map(name => readFileSync(new URL(`./views/${name}`, import.meta.url), "utf8"));
const floating = readFileSync(new URL("./components/PracticeFloatingChat.vue", import.meta.url), "utf8");
const context = readFileSync(new URL("./components/AgentQuestionContextCard.vue", import.meta.url), "utf8");

describe("T15B 页面与安全边界", () => {
  it("只在普通公共题详情挂载，不进入课程播放器、正式测评、结果或错题本", () => {
    expect(detail).toContain("PracticeFloatingChat");
    forbiddenPages.forEach(source => expect(source).not.toContain("PracticeFloatingChat"));
  });

  it("复用生成 DTO、SSE 状态机和消息绑定题图授权，不回退当前题详情", () => {
    expect(floating).toContain("consumeAgentSse"); expect(floating).toContain("questionContextProjection");
    expect(floating).toContain("questionMediaIds"); expect(context).toContain("messageQuestionMediaGrant");
    expect(context).not.toContain("studentQuestionApi.detail");
  });

  it("关闭与完整页面跳转先取消运行，清理监听器且不包含答案、评分或提交动作", () => {
    expect(floating).toContain('stop("PAGE_CLOSED")'); expect(floating).toContain("removeEventListener");
    expect(floating).toContain("/ai/conversations/");
    expect(floating).not.toMatch(/studentAssessmentApi|submitAttempt|scoreAnswer/);
  });
});
