import type { TeacherAssessment } from "@edu/api-client";

/** 有界轮询只在页面可见、结算仍进行且未达到十次上限时继续；FAILED/完成/离页都会停止。 */
export function shouldPollSettlement(assessment: TeacherAssessment | null, visible: boolean,
    completedPolls: number, maximumPolls = 10): boolean {
  return Boolean(assessment) && visible && completedPolls < maximumPolls
    && (assessment?.settlementStatus === "PENDING" || assessment?.settlementStatus === "PROCESSING");
}

/** 将服务端资格事实补充为可读原因；按钮仍会调用真实 API，由服务端做最终校验。 */
export function publicationBlockReason(kind: "grades" | "answers", assessment: TeacherAssessment): string | null {
  if (kind === "grades") {
    if (assessment.gradePublicationStatus === "PUBLISHED") return "成绩已经公布且不可撤回。";
    if (assessment.gradePublicationEligible) return null;
    if (assessment.pendingSettlementCount > 0) return `仍有 ${assessment.pendingSettlementCount} 份草稿待结算。`;
    if (assessment.pendingManualGradingAnswerCount > 0) return `仍有 ${assessment.pendingManualGradingAnswerCount} 道题待人工批改。`;
    return "需先关闭整场并完成结算。";
  }
  if (assessment.answerPublicationStatus === "PUBLISHED") return "参考答案已经公布且不可撤回。";
  if (assessment.answerPublicationPolicy === "NEVER") return "当前策略为永不公布。";
  if (assessment.answerPublicationEligible) return null;
  if (assessment.pendingSettlementCount > 0) return `仍有 ${assessment.pendingSettlementCount} 份草稿待结算。`;
  if (assessment.type !== "PRACTICE" && assessment.closeStatus === "OPEN") return "作业和考试必须先关闭整场。";
  return "当前公布策略或整场状态尚不允许此操作。";
}
