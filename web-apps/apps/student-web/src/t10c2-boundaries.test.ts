import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const bank = readFileSync(new URL("./views/QuestionBankPage.vue", import.meta.url), "utf8");
const detail = readFileSync(new URL("./views/SystemQuestionDetailPage.vue", import.meta.url), "utf8");
const course = readFileSync(new URL("./views/CourseDetailPage.vue", import.meta.url), "utf8");
const tasks = readFileSync(new URL("./views/CourseTasksPage.vue", import.meta.url), "utf8");
const legacy = readFileSync(new URL("./views/LegacyPracticeRedirectPage.vue", import.meta.url), "utf8");
const media = readFileSync(new URL("./components/QuestionMediaAction.vue", import.meta.url), "utf8");
const styles = readFileSync(new URL("./styles.css", import.meta.url), "utf8");

describe("T10C.2 public question and course practice boundaries", () => {
  it("keeps the top-level bank independent from course assessments and unsupported scoring", () => {
    expect(bank).toContain("studentQuestionApi.list");
    expect(bank).toContain("PUBLIC QUESTION BANK");
    expect(bank).toContain("在线作答与自主组题尚未开放");
    expect(bank).not.toContain("studentAssessmentApi");
    expect(bank).not.toContain("courseId");
  });

  it("renders only the safe SYSTEM detail and authorizes media by root question", () => {
    expect(detail).toContain("studentQuestionApi.detail");
    expect(detail).toContain("studentQuestionApi.mediaGrant");
    expect(detail).toContain("SafeAssessmentContent");
    expect(detail).not.toMatch(/answers|analysis/);
    expect(media).toContain("URL.revokeObjectURL");
    expect(media).toContain("controller?.abort()");
  });

  it("enters server-filtered PRACTICE from its course and migrates legacy links through entry", () => {
    expect(course).toContain("query: { type: 'PRACTICE' }");
    expect(tasks).toContain('type: isPractice.value ? "PRACTICE" : undefined');
    expect(tasks).not.toContain("studentAssessmentApi.list(");
    expect(legacy).toContain("studentAssessmentApi.assessmentEntry");
    expect(legacy).not.toContain("studentAssessmentApi.detail");
  });

  it("keeps partial errors, route restoration, keyboard focus and responsive layouts", () => {
    for (const status of [401, 403, 404, 422, 429, 503]) {
      expect(`${bank}\n${detail}\n${tasks}\n${legacy}`).toContain(`${status}:`);
    }
    expect(bank).toContain("watch(() => route.query");
    expect(styles).toContain("button:focus-visible");
    expect(styles).toContain(".question-bank-grid,.attempt-layout { grid-template-columns: 1fr; }");
  });
});
