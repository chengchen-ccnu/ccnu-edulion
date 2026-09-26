// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TeacherStudentsPage from "./views/TeacherStudentsPage.vue";
import TeacherAnalyticsPage from "./views/TeacherAnalyticsPage.vue";

const api = vi.hoisted(() => ({ students: vi.fn(), studentOverview: vi.fn(), studentLessons: vi.fn(), studentTasks: vi.fn(),
  studentTrend: vi.fn(), courseOverview: vi.fn(), courseLessons: vi.fn(), courseTasks: vi.fn(), courseTrend: vi.fn() }));
vi.mock("./learning-analytics-api", () => ({ learningAnalyticsApi: api }));
vi.mock("./course-api", () => ({ teacherCourseApi: { list: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 20 }) } }));
let app: App | undefined; let host: HTMLDivElement;
beforeEach(() => {
  for (const mock of Object.values(api)) mock.mockReset();
  api.students.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, state: "READY", nicknameDependencyFailed: false, period: period() });
  api.studentOverview.mockResolvedValue({ student: student(), practiceSubmissionCount: 2, nicknameDependencyFailed: false, period: period() });
  api.studentLessons.mockResolvedValue({ items: [], total: 0, page: 1, size: 50, period: period() });
  api.studentTasks.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, period: period() });
  api.studentTrend.mockResolvedValue({ points: [], window: "LAST_7_DAYS", period: period() });
  api.courseOverview.mockResolvedValue({ state: "READY", activeStudentCount: 0, totalLessonCount: 0,
    completedStudentCount: 0, watchedSeconds: 0, actualViewerCount: 0, averageWatchedPerActiveStudent: 0,
    averageWatchedPerViewer: 0, period: period() });
  api.courseLessons.mockResolvedValue({ items: [], total: 0, page: 1, size: 50, period: period() });
  api.courseTasks.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, period: period() });
  api.courseTrend.mockResolvedValue({ points: [], window: "LAST_7_DAYS", period: period() });
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); });
const period = () => ({ periodStart: "2026-09-26", periodEnd: "2026-09-26", zoneId: "Asia/Shanghai", granularity: "LIFETIME",
  generatedAt: "2026-09-26T00:00:00Z", dataThrough: null });
const student = () => ({ learnerId: "learner", courseStudentNumber: "0001", nickname: "测试学生", nicknameAvailable: true,
  completedLessons: 1, totalLessons: 2, progressPercent: 50, watchedSeconds: 60, lastActivityAt: null,
  homeworkAverage: null, examAverage: null, missingCount: 0, pendingCount: 1 });

async function mount(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/teacher/students", component: TeacherStudentsPage }, { path: "/teacher/analytics", component: TeacherAnalyticsPage }
  ] });
  await router.push(path); await router.isReady();
  app = createApp({ template: "<RouterView />" }); app.use(router); app.mount(host);
  await nextTick(); return router;
}

describe("students deep link and on demand detail", () => {
  it("restores tasks without requesting overview or lessons and honors individual score before class ranking", async () => {
    api.studentTasks.mockResolvedValue({ items: [{ assessmentId: "task", lessonId: null, title: "作业", type: "HOMEWORK",
      status: "PUBLISHED", submitted: true, late: false, gradingPending: false, resultsReady: false,
      rawScore: 8, totalPoints: 10, scorePercent: 80, rank: null, rankedStudentCount: null, rankingPercent: null }],
      page: 1, size: 20, total: 1, period: period() });
    await mount("/teacher/students?courseId=course&learnerId=learner&panel=tasks&detailPage=1");
    await vi.waitFor(() => expect(host.textContent).toContain("8 / 10（80.0%）"));
    expect(host.textContent).toContain("班级排名暂不可见");
    expect(api.studentTasks).toHaveBeenCalledWith("course", "learner", 1, expect.any(AbortSignal));
    expect(api.studentOverview).not.toHaveBeenCalled(); expect(api.studentLessons).not.toHaveBeenCalled();
    expect(api.studentTrend).not.toHaveBeenCalled();
  });

  it("preserves nickname degradation and the course student number", async () => {
    api.students.mockResolvedValue({ items: [student()], total: 1, page: 1, size: 20, state: "READY", nicknameDependencyFailed: true, period: period() });
    await mount("/teacher/students?courseId=course");
    await vi.waitFor(() => expect(host.textContent).toContain("昵称服务暂时不可用"));
    expect(host.textContent).toContain("0001");
  });

  it("keeps the list readable when a nullable score is omitted by the live service", async () => {
    const { homeworkAverage, examAverage, ...withoutNullableScores } = student();
    void homeworkAverage; void examAverage;
    api.students.mockResolvedValue({ items: [withoutNullableScores], total: 1, page: 1, size: 20,
      state: "READY", nicknameDependencyFailed: false, period: period() });
    await mount("/teacher/students?courseId=course");
    await vi.waitFor(() => expect(host.textContent).toContain("0001"));
    expect(host.textContent).toContain("成绩暂不可用");
  });

  it("aborts old detail when switching panels and keeps an offline error local to the new panel", async () => {
    api.studentOverview.mockImplementation(() => new Promise(() => undefined));
    api.studentLessons.mockRejectedValue(new TypeError("offline"));
    const router = await mount("/teacher/students?courseId=course&learnerId=learner&panel=overview");
    await vi.waitFor(() => expect(api.studentOverview).toHaveBeenCalled());
    const oldSignal = api.studentOverview.mock.calls[0][2] as AbortSignal;
    await router.push("/teacher/students?courseId=course&learnerId=learner&panel=lessons");
    await vi.waitFor(() => expect(host.textContent).toContain("网络不可用"));
    expect(oldSignal.aborted).toBe(true);
    expect(api.students).toHaveBeenCalledTimes(1);
  });
});

describe("course analytics", () => {
  it("loads generated course facts and restores the ALL window and lesson filter", async () => {
    await mount("/teacher/analytics?courseId=course&window=ALL&lessonId=lesson&lessonPage=2&taskPage=3");
    await vi.waitFor(() => expect(api.courseTrend).toHaveBeenCalled());
    expect(api.courseTrend).toHaveBeenCalledWith("course", "ALL", "lesson", expect.any(AbortSignal));
    expect(api.courseLessons).toHaveBeenCalledWith("course", 2, expect.any(AbortSignal));
    expect(api.courseTasks).toHaveBeenCalledWith("course", 3, expect.any(AbortSignal));
  });

  it("keeps course facts visible when only the trend request fails", async () => {
    api.courseTrend.mockRejectedValue(new TypeError("offline"));
    api.courseOverview.mockResolvedValue({ state: "READY", activeStudentCount: 3, totalLessonCount: 1,
      completedStudentCount: 0, watchedSeconds: 0, actualViewerCount: 0,
      averageWatchedPerActiveStudent: 0, averageWatchedPerViewer: 0, period: period() });
    await mount("/teacher/analytics?courseId=course");
    await vi.waitFor(() => expect(host.textContent).toContain("网络不可用"));
    expect(host.textContent).toContain("在读学生");
    expect(host.textContent).toContain("3");
  });
});
