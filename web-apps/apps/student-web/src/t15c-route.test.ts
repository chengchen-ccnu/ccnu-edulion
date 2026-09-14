// @vitest-environment jsdom
import { GatewayRequestError } from "@edu/api-client";
import type { LearnerAssessment } from "@edu/api-client";
import { createApp, h, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PracticeAttemptPage from "./views/PracticeAttemptPage.vue";

const api = vi.hoisted(() => ({ detail: vi.fn(), start: vi.fn(), draft: vi.fn(), save: vi.fn(), submit: vi.fn(),
  courseTask: vi.fn(), startCourseTask: vi.fn(), assessmentEntry: vi.fn(), mediaGrant: vi.fn() }));
vi.mock("./assessment-api", () => ({ studentAssessmentApi: api }));
let app: App | undefined; let host: HTMLDivElement;
const assessmentId = "44444444-4444-4444-8444-444444444444";

beforeEach(() => {
  vi.resetAllMocks(); host = document.createElement("div"); document.body.append(host);
  api.detail.mockResolvedValue(autonomousAssessment());
  api.assessmentEntry.mockRejectedValue(new GatewayRequestError(new Response(null, { status: 404 })));
  api.start.mockResolvedValue({ id: "attempt-1", assessmentId, status: "DRAFT", revision: 0, answers: [],
    startedAt: "2026-09-14T00:00:00Z", deadlineAt: null, serverTime: "2026-09-14T00:00:00Z",
    createdAt: "2026-09-14T00:00:00Z", updatedAt: "2026-09-14T00:00:00Z", attemptSequence: 1,
    attemptsUsed: 1, maxAttempts: 1, lateSubmission: false });
});
afterEach(() => { app?.unmount(); host.remove(); });

describe("T15C autonomous practice route", () => {
  it("uses the existing attempt page and starts only the owner-bound autonomous assessment", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: "/practice", component: { template: "<p>题库</p>" } },
      { path: "/practice/:assessmentId", name: "practice-autonomous-attempt", component: PracticeAttemptPage },
      { path: "/learn/:courseId/tasks/:assessmentId", name: "course-task-attempt", component: PracticeAttemptPage },
      { path: "/attempt/:attemptId/result", component: { template: "<p>结果</p>" } }
    ] });
    await router.push(`/practice/${assessmentId}`); await router.isReady();
    app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await nextTick();
    await vi.waitFor(() => expect(host.textContent).toContain("函数图像自主练习"));
    expect(api.detail).toHaveBeenCalledWith(assessmentId, expect.any(AbortSignal));
    expect(api.assessmentEntry).toHaveBeenCalledWith(assessmentId, expect.any(AbortSignal));
    expect(api.start).toHaveBeenCalledWith(assessmentId, expect.objectContaining({ clientRequestId: expect.any(String) }), expect.any(AbortSignal));
    expect(api.courseTask).not.toHaveBeenCalled();
  });
});

function autonomousAssessment(): LearnerAssessment {
  return { id: assessmentId, courseId: null, lessonId: null, title: "函数图像自主练习", type: "PRACTICE",
    status: "PUBLISHED", closeStatus: "OPEN", settlementStatus: "NOT_STARTED",
    gradePublicationPolicy: "AUTOMATIC_AFTER_GRADING", answerPublicationPolicy: "AFTER_GRADED",
    gradePublicationStatus: "UNPUBLISHED", answerPublicationStatus: "UNPUBLISHED", closedAt: null,
    affectedInProgressAttemptCount: 0, activeAttemptCount: 0, pendingSettlementCount: 0,
    pendingManualGradingAttemptCount: 0, pendingManualGradingAnswerCount: 0, gradePublishedAt: null,
    answerPublishedAt: null, gradePublicationEligible: false, answerPublicationEligible: false, resultsReady: false,
    opensAt: null, closesAt: null, latePolicy: "DISALLOW", lateUntil: null, maxAttempts: 1,
    timeLimitSeconds: null, questionCount: 1, totalPoints: 1, revision: 0,
    publishedAt: "2026-09-14T00:00:00Z", createdAt: "2026-09-14T00:00:00Z", updatedAt: "2026-09-14T00:00:00Z",
    description: "自主练习", serverTime: "2026-09-14T00:00:00Z", currentAttempt: null,
    questions: [{ id: "assessment-question-1", questionVersionId: "version-1", sequence: 1, points: 1,
      type: "SINGLE_CHOICE", stem: "请选择", options: [{ key: "A", content: "答案 A" }], media: [], children: [] }] };
}
