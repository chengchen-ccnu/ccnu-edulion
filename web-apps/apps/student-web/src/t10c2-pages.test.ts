// @vitest-environment jsdom
import { GatewayRequestError } from "@edu/api-client";
import type { LearnerCourseTaskSummary, SystemQuestionPage, SystemQuestion } from "@edu/api-client";
import { createApp, h, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import QuestionBankPage from "./views/QuestionBankPage.vue";
import SystemQuestionDetailPage from "./views/SystemQuestionDetailPage.vue";
import CourseTasksPage from "./views/CourseTasksPage.vue";
import PracticeAttemptPage from "./views/PracticeAttemptPage.vue";
import QuestionMediaAction from "./components/QuestionMediaAction.vue";

const api = vi.hoisted(() => ({ list: vi.fn(), detail: vi.fn(), mediaGrant: vi.fn(),
  listCourseTasks: vi.fn(), assessmentEntry: vi.fn(), start: vi.fn(), draft: vi.fn(), save: vi.fn(), submit: vi.fn() }));
vi.mock("./question-api", () => ({ studentQuestionApi: api }));
vi.mock("./assessment-api", () => ({ studentAssessmentApi: api }));
let app: App | undefined;
let host: HTMLDivElement;
const date = "2026-09-08T00:00:00Z";

beforeEach(() => {
  vi.resetAllMocks();
  api.list.mockResolvedValue(bank());
  api.listCourseTasks.mockResolvedValue({ items: [task()], page: 1, size: 20, total: 1, serverTime: date });
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

/** 用真实组件和内存路由验证状态变化；固定 API 输入来自生成类型，不写数据库、不替代后端授权验收。 */
async function mount(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/practice", component: QuestionBankPage },
    { path: "/practice/questions/:questionId", component: SystemQuestionDetailPage },
    { path: "/practice/:assessmentId", name: "practice-autonomous-attempt", component: PracticeAttemptPage },
    { path: "/learn/:courseId/tasks", component: CourseTasksPage },
    { path: "/:pathMatch(.*)*", component: { template: "<p>目标页面</p>" } }
  ] });
  await router.push(path); await router.isReady();
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host);
  await nextTick();
  return router;
}
function bank(): SystemQuestionPage {
  return { items: [{ id: "question-test-1", scope: "SYSTEM", editable: false, status: "ACTIVE", revision: 1,
    currentVersionId: "version-test-1", versionNumber: 1, type: "COMPOSITE", difficulty: "EASY", stage: "PRIMARY",
    grade: "ONE", stemPreview: "公共测试题", knowledgePointIds: [], createdAt: date, updatedAt: date }], page: 1, size: 12, total: 24 };
}
function task(overrides: Partial<LearnerCourseTaskSummary> = {}): LearnerCourseTaskSummary {
  return { id: "task-test-1", courseId: "course-test-1", lessonId: null, title: "本课程练习", type: "PRACTICE",
    timeState: "OPEN", submissionStatus: "NOT_STARTED", gradingStatus: "NOT_REQUIRED", lateSubmission: false,
    attemptsUsed: 0, maxAttempts: 1, opensAt: null, closesAt: null, lateUntil: null, lastSubmittedAt: null,
    finalAttempt: null, questionContentAvailable: true, ...overrides };
}
function button(text: string) {
  const value = [...host.querySelectorAll("button")].find(item => item.textContent?.includes(text));
  if (!value) throw new Error(`Missing button ${text}`);
  return value;
}

describe("T10C.2 page behavior", () => {
  it("restores filters from a deep link and preserves them across pagination", async () => {
    api.list.mockResolvedValue({ ...bank(), page: 2, total: 36 });
    const router = await mount("/practice?page=2&stage=JUNIOR&grade=EIGHT&type=COMPOSITE&difficulty=HARD");
    expect(api.list).toHaveBeenCalledWith({ page: 2, size: 12, stage: "JUNIOR", grade: "EIGHT", type: "COMPOSITE", difficulty: "HARD" }, expect.any(AbortSignal));
    await vi.waitFor(() => expect(host.textContent).toContain("公共测试题"));
    button("下一页").focus(); expect(document.activeElement).toBe(button("下一页"));
    button("下一页").click();
    await vi.waitFor(() => expect(router.currentRoute.value.query.page).toBe("3"));
    expect(router.currentRoute.value.query.grade).toBe("EIGHT");
    expect(host.querySelector('a[href="/practice/questions/question-test-1"]')).not.toBeNull();
    expect(api.listCourseTasks).not.toHaveBeenCalled();
  });

  it("clears an incompatible grade when the learner changes stage", async () => {
    const router = await mount("/practice?stage=JUNIOR&grade=EIGHT&page=3");
    const select = host.querySelector("select")!; select.value = "PRIMARY";
    select.dispatchEvent(new Event("change", { bubbles: true }));
    await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({ stage: "PRIMARY", page: "1" }));
  });

  it.each([401, 403, 404, 409, 422, 429, 503])("reports initial HTTP %s without claiming the bank is empty", async status => {
    api.list.mockRejectedValue(new GatewayRequestError(new Response(null, { status })));
    await mount("/practice");
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull());
    expect(host.textContent).not.toContain("当前筛选下没有公共题目");
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it("distinguishes loading and empty, and preserves successful content after offline failure", async () => {
    let resolve!: (value: SystemQuestionPage) => void;
    api.list.mockImplementationOnce(() => new Promise<SystemQuestionPage>(done => { resolve = done; }));
    await mount("/practice");
    expect(host.querySelector('[role="status"]')?.textContent).toContain("正在读取");
    resolve({ items: [], page: 1, size: 12, total: 0 });
    await vi.waitFor(() => expect(host.textContent).toContain("当前筛选下没有公共题目"));
    button("重新载入").click(); await vi.waitFor(() => expect(host.textContent).toContain("公共测试题"));
    api.list.mockRejectedValueOnce(new TypeError("offline")); button("重新载入").click();
    await vi.waitFor(() => expect(host.textContent).toContain("当前离线"));
    expect(host.textContent).toContain("公共测试题");
  });

  it("requests course PRACTICE on the server and clears old content when switching course", async () => {
    const router = await mount("/learn/course-test-1/tasks?type=PRACTICE");
    await vi.waitFor(() => expect(host.textContent).toContain("本课程练习"));
    expect(api.listCourseTasks).toHaveBeenCalledWith("course-test-1", { page: 1, size: 20, type: "PRACTICE" }, expect.any(AbortSignal));
    api.listCourseTasks.mockRejectedValueOnce(new GatewayRequestError(new Response(null, { status: 404 })));
    await router.push("/learn/course-test-2/tasks?type=PRACTICE");
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull());
    expect(host.textContent).not.toContain("本课程练习");
    expect(host.querySelector(".practice-empty")).toBeNull();
    await router.push("/learn/course-test-1/tasks");
    expect(api.listCourseTasks).toHaveBeenLastCalledWith("course-test-1", { page: 1, size: 20, type: undefined }, expect.any(AbortSignal));
  });

  it("does not offer an unopened course task and preserves PRACTICE when starting an open one", async () => {
    api.listCourseTasks.mockResolvedValueOnce({ items: [task({ timeState: "NOT_OPEN", questionContentAvailable: false })], page: 1, size: 20, total: 1, serverTime: date });
    const router = await mount("/learn/course-test-1/tasks?type=PRACTICE");
    await vi.waitFor(() => expect(button("尚未开放").disabled).toBe(true));
    await router.push("/learn/course-test-1/tasks?type=PRACTICE&page=2");
    await vi.waitFor(() => expect(button("开始作答").disabled).toBe(false)); button("开始作答").click();
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe("/learn/course-test-1/tasks/task-test-1?type=PRACTICE"));
  });

  it("migrates a course practice link only from the server entry projection", async () => {
    api.assessmentEntry.mockResolvedValue(task());
    const router = await mount("/practice/task-test-1?courseId=untrusted");
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe("/learn/course-test-1/tasks/task-test-1?type=PRACTICE"));
    expect(api.detail).not.toHaveBeenCalled();
  });

  it.each([403, 404])("does not redirect a rejected assessment link (%s)", async status => {
    api.assessmentEntry.mockRejectedValue(new GatewayRequestError(new Response(null, { status })));
    api.detail.mockRejectedValue(new GatewayRequestError(new Response(null, { status })));
    const router = await mount("/practice/task-test-1");
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull());
    expect(router.currentRoute.value.path).toBe("/practice/task-test-1");
  });

  it("renders ordered composite children and authorizes their image through the root question", async () => {
    const mediaId = "00000000-0000-4000-8000-000000000001";
    const value: SystemQuestion = { id: "question-test-1", versionId: "root-version-test-1", scope: "SYSTEM", type: "COMPOSITE", stage: "PRIMARY", grade: "ONE", subject: "MATHEMATICS", difficulty: "EASY", stem: "组合测试题", options: [], knowledgePoints: [], media: [], createdAt: date, updatedAt: date,
      children: [{ questionId: "child-test-1", versionId: "version-test-1", sequence: 1, type: "SHORT_ANSWER", stage: "PRIMARY", grade: "ONE", subject: "MATHEMATICS", difficulty: "EASY", stem: `小问一 {{media:${mediaId}}}`, options: [], knowledgePoints: [], media: [{ id: "binding-test-1", mediaId, purpose: "QUESTION_IMAGE", sequence: 1 }], createdAt: date }] };
    api.detail.mockResolvedValue(value); api.mediaGrant.mockResolvedValue({ url: "https://media.test/image", headers: {}, expiresAt: date });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(["test"]) }));
    const create = vi.fn().mockReturnValue("blob:test-image"); const revoke = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: create });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revoke });
    const router = await mount("/practice/questions/question-test-1");
    await vi.waitFor(() => expect(host.querySelector("img")?.getAttribute("src")).toBe("blob:test-image"));
    expect(host.querySelector(".public-question-child h4")?.textContent).toBe("(1)");
    expect(api.mediaGrant).toHaveBeenCalledWith("question-test-1", mediaId, "INLINE", expect.any(AbortSignal));
    await router.push("/practice"); expect(revoke).toHaveBeenCalledWith("blob:test-image");
  });

  it("aborts a pending attachment and never creates a Blob URL after unmount", async () => {
    let resolve!: (value: Blob) => void;
    api.mediaGrant.mockResolvedValue({ url: "https://media.test/attachment", headers: {}, expiresAt: date });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, blob: () => new Promise<Blob>(done => { resolve = done; }) }));
    const create = vi.fn(); Object.defineProperty(URL, "createObjectURL", { configurable: true, value: create });
    app = createApp(QuestionMediaAction, { questionId: "question-test-1", media: { mediaId: "media-test-1", purpose: "QUESTION_ATTACHMENT", sequence: 1 }, label: "测试附件" }); app.mount(host);
    button("授权并下载").click(); await vi.waitFor(() => expect(resolve).toBeDefined());
    const signal = api.mediaGrant.mock.calls[0][3] as AbortSignal;
    app.unmount(); app = undefined; expect(signal.aborted).toBe(true);
    resolve(new Blob(["late"])); await nextTick(); expect(create).not.toHaveBeenCalled();
  });
});
