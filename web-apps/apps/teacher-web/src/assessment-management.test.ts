import type { TeacherAssessment } from "@edu/api-client";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { publicationBlockReason, shouldPollSettlement } from "./assessment-management";

describe("teacher assessment lifecycle view model", () => {
  it.each([
    ["PENDING", true, 0, true], ["PROCESSING", true, 9, true], ["PROCESSING", true, 10, false],
    ["FAILED", true, 0, false], ["COMPLETED", true, 0, false], ["PROCESSING", false, 0, false]
  ] as const)("polls settlement=%s visible=%s count=%s => %s", (status, visible, count, expected) => {
    expect(shouldPollSettlement(assessment({ settlementStatus: status }), visible, count)).toBe(expected);
  });

  it("explains independent grade and answer publication eligibility from service facts", () => {
    expect(publicationBlockReason("grades", assessment({ pendingSettlementCount: 2 }))).toContain("2 份草稿");
    expect(publicationBlockReason("grades", assessment({ pendingManualGradingAnswerCount: 3 }))).toContain("3 道题");
    expect(publicationBlockReason("answers", assessment({ answerPublicationPolicy: "NEVER" }))).toContain("永不公布");
    expect(publicationBlockReason("answers", assessment({ type: "EXAM", closeStatus: "OPEN" }))).toContain("必须先关闭");
    expect(publicationBlockReason("grades", assessment({ gradePublicationEligible: true }))).toBeNull();
    expect(publicationBlockReason("answers", assessment({ answerPublicationEligible: true }))).toBeNull();
  });

  it("keeps closure confirmation, revision conflicts and grading inputs explicit in the single page", () => {
    const source = readFileSync(new URL("./views/TeacherAssessmentPage.vue", import.meta.url), "utf8");
    expect(source).toContain("activeAttemptCount");
    expect(source).toContain("关闭不可撤销");
    expect(source).toContain("const body = { clientRequestId: crypto.randomUUID(), expectedRevision");
    expect(source).toContain("preserveGradeInputs");
    expect(source).toContain("当前得分和评语输入已保留");
    expect(source).toContain("attemptId && panel.value === \"grading\"");
    expect(source).toContain("getTeacherAttemptResult(attemptId");
    expect(source).toContain("避免用整场 results 分页查找");
    expect(source).toContain("attemptId: value === \"grading\" ? queryString(\"attemptId\") : undefined");
    expect(source).toContain("resetFeedback()");
    expect(source).toContain("document.removeEventListener");
  });

  it("keeps course curriculum as a link and does not copy the assessment editor", () => {
    const source = readFileSync(new URL("./views/TeacherCourseCurriculumPage.vue", import.meta.url), "utf8");
    expect(source).toContain("teacher-assessments");
    expect(source).toContain("lessonId");
    expect(source).not.toContain("TeacherAssessmentPage");
    expect(source).not.toContain("replaceAssessmentQuestions");
  });
});

function assessment(overrides: Partial<TeacherAssessment>): TeacherAssessment {
  return { settlementStatus: "COMPLETED", pendingSettlementCount: 0, pendingManualGradingAnswerCount: 0,
    gradePublicationStatus: "UNPUBLISHED", answerPublicationStatus: "UNPUBLISHED",
    gradePublicationEligible: false, answerPublicationEligible: false, answerPublicationPolicy: "MANUAL_AFTER_CLOSE",
    type: "PRACTICE", closeStatus: "CLOSED", ...overrides } as TeacherAssessment;
}
