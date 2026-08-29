import type { LearnerAssessmentSummary } from "@edu/api-client";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { clampQuestionIndex, learnerAssessmentAction, resultDisclosure } from "./assessment-view-model";

describe("learner assessment state mapping", () => {
  it.each([
    [{ learnerState: "GRADED", resultAvailable: true, resumeAllowed: false, currentAttempt: { id: "attempt" } }, "result"],
    [{ learnerState: "IN_PROGRESS", resultAvailable: false, resumeAllowed: true, currentAttempt: { id: "attempt" } }, "resume"],
    [{ learnerState: "AVAILABLE", resultAvailable: false, resumeAllowed: false, currentAttempt: null }, "start"],
    [{ learnerState: "NOT_OPEN", resultAvailable: false, resumeAllowed: false, currentAttempt: null }, "disabled"]
  ] as const)("maps server facts to %s", (facts, expected) => {
    expect(learnerAssessmentAction(facts as unknown as LearnerAssessmentSummary)).toBe(expected);
  });
});

describe("result disclosure", () => {
  it.each([
    [false, false, [false, false]],
    [true, false, [true, false]],
    [false, true, [false, true]],
    [true, true, [true, true]]
  ])("keeps the four grade=%s answer=%s states independent", (gradeVisible, answerVisible, expected) => {
    const disclosure = resultDisclosure({ gradeVisible, answerVisible });
    expect([disclosure.showPerQuestionGrade, disclosure.showReferenceAnswer]).toEqual(expected);
    expect(disclosure.showSubmittedAnswer).toBe(true);
  });

  it("clamps URL-restored single-question navigation without splitting root questions", () => {
    expect(clampQuestionIndex(undefined, 4)).toBe(0);
    expect(clampQuestionIndex("3", 4)).toBe(2);
    expect(clampQuestionIndex("99", 4)).toBe(3);
    expect(clampQuestionIndex("bad", 4)).toBe(0);
  });

  it("creates grade and answer DOM only behind the explicit disclosure branches", () => {
    const source = readFileSync(new URL("./views/AttemptResultPage.vue", import.meta.url), "utf8");
    expect(source).toContain('v-if="disclosure?.showTotalScore"');
    expect(source).toContain('v-if="disclosure?.showPerQuestionGrade"');
    expect(source).toContain('v-if="disclosure?.showReferenceAnswer"');
    expect(source).not.toMatch(/display\s*:\s*none/);
    expect(source).not.toContain("result.status ===");
  });

  it("keeps ordered composite children inside one root-question article", () => {
    const source = readFileSync(new URL("./views/PracticeAttemptPage.vue", import.meta.url), "utf8");
    const rootStart = source.indexOf('class="question-block"');
    const children = source.indexOf('class="composite-children"');
    const rootEnd = source.indexOf('class="question-navigation"', children);
    expect(rootStart).toBeGreaterThan(-1);
    expect(children).toBeGreaterThan(rootStart);
    expect(rootEnd).toBeGreaterThan(children);
  });
});
