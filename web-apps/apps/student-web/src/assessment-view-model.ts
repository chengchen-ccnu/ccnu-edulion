import type { AttemptResult, LearnerAssessmentSummary } from "@edu/api-client";

export type LearnerAssessmentAction = "result" | "resume" | "start" | "disabled";

/**
 * 将服务端参与事实投影为唯一动作。输入不包含本机时间；输出只决定导航或调用 start，绝不提前改变 attempt 状态。
 */
export function learnerAssessmentAction(item: LearnerAssessmentSummary): LearnerAssessmentAction {
  if (item.resultAvailable && item.currentAttempt) return "result";
  if (item.resumeAllowed && item.currentAttempt) return "resume";
  if (item.learnerState === "AVAILABLE") return "start";
  return "disabled";
}

export type ResultDisclosure = {
  showTotalScore: boolean;
  showPerQuestionGrade: boolean;
  showReferenceAnswer: boolean;
  showSubmittedAnswer: true;
};

/**
 * 结果可见性只读取 T11B 的两个显式事实。调用方据此决定是否创建敏感 DOM，不能用 status 或 CSS 推断/遮挡。
 */
export function resultDisclosure(result: Pick<AttemptResult, "gradeVisible" | "answerVisible">): ResultDisclosure {
  return { showTotalScore: result.gradeVisible, showPerQuestionGrade: result.gradeVisible,
    showReferenceAnswer: result.answerVisible, showSubmittedAnswer: true };
}

/** URL 的题号是一基序号；非法、越界或空值会稳定回落到第一题或最后一题。 */
export function clampQuestionIndex(value: unknown, questionCount: number): number {
  const requested = Number(value);
  const oneBased = Number.isInteger(requested) && requested > 0 ? requested : 1;
  return Math.max(0, Math.min(Math.max(0, questionCount - 1), oneBased - 1));
}
