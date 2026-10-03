// @vitest-environment jsdom
import { GatewayRequestError } from "@edu/api-client";
import { createApp, h, nextTick, reactive } from "vue";
import type { App, Component } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import HomePage from "./views/HomePage.vue";
import NotificationsPage from "./views/NotificationsPage.vue";
import CourseAnnouncements from "./components/CourseAnnouncements.vue";
import ApplyTeacherPage from "./views/ApplyTeacherPage.vue";
import { notificationRoute, readAfterRender } from "./notification-target";

const api = vi.hoisted(() => ({ hotCourses: vi.fn(), notifications: vi.fn(), readAll: vi.fn(), target: vi.fn(), read: vi.fn(), announcements: vi.fn(), announcement: vi.fn(), confirm: vi.fn() }));
const account = vi.hoisted(() => ({ teacherApplication: vi.fn(), ensureCurrentUser: vi.fn(), createMediaUpload: vi.fn() }));
const course = vi.hoisted(() => ({ categories: vi.fn(), detail: vi.fn() }));
const learning = vi.hoisted(() => ({ enrollments: vi.fn(), progress: vi.fn() }));
vi.mock("./operations-api", () => ({ operationsApi: api }));
vi.mock("./course-api", () => ({ studentCourseApi: course }));
vi.mock("./learning-api", () => ({ studentLearningApi: learning }));
vi.mock("./account-security-api", () => ({ accountSecurityApi: account }));
vi.mock("./auth", () => ({ authStore: { get state() { return state; }, hasPermission: (permission: string) => state.currentUser?.permissions.includes(permission) ?? false, ensureCurrentUser: account.ensureCurrentUser } }));
let state = reactive({ status: "authenticated", currentUser: { id: "student-test", displayName: "student_test_t17a", roles: ["STUDENT"], permissions: ["learning:access"] } });
const id = "11111111-1111-4111-8111-111111111111", courseId = "22222222-2222-4222-8222-222222222222";
const announcement = () => ({ id, courseId, revision: 1, status: "ACTIVE", title: "本周安排", body: "<script>alert(1)</script>\n第二行", publishedAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z", confirmationRequired: true, confirmed: false });
const page = () => ({ items: [announcement()], page: 1, size: 20, total: 1, latestUnconfirmed: announcement(), unconfirmedCount: 1 });
let app: App | undefined, host: HTMLDivElement;
beforeEach(() => {
  vi.resetAllMocks(); state = reactive({ status: "authenticated", currentUser: { id: "student-test", displayName: "student_test_t17a", roles: ["STUDENT"], permissions: ["learning:access"] } });
  host = document.createElement("div"); document.body.append(host);
  course.categories.mockResolvedValue([]); learning.enrollments.mockResolvedValue({ items: [] });
  api.hotCourses.mockResolvedValue({ status: "EMPTY", items: [], validUntil: null });
  api.announcements.mockResolvedValue(page()); api.announcement.mockResolvedValue(announcement());
  api.notifications.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 });
  account.teacherApplication.mockResolvedValue(null); account.ensureCurrentUser.mockResolvedValue(true);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); vi.useRealTimers(); vi.unstubAllGlobals(); });
async function mount(component: Component, path = "/", props = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/:all(.*)*", component: { template: "<p />" } }] });
  await router.push(path); await router.isReady(); app = createApp({ render: () => h(component, props) }); app.use(router); app.mount(host); await nextTick(); return router;
}
function button(text: string) { return [...host.querySelectorAll<HTMLButtonElement>("button")].find(item => item.textContent?.includes(text))!; }
function failure(status: number) { return new GatewayRequestError(new Response(null, { status, headers: status === 429 ? { "Retry-After": "60" } : {} })); }

describe("T17A real contract consumers", () => {
  it("aborts qualification upload session creation when leaving the application page", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => id, subtle: { digest: async () => new ArrayBuffer(32) } });
    let observed: AbortSignal | undefined;
    account.createMediaUpload.mockImplementation((_body: unknown, signal: AbortSignal) => { observed = signal; return new Promise(() => {}); });
    await mount(ApplyTeacherPage, "/apply-teacher");
    await vi.waitFor(() => expect(host.querySelector('input[type="file"]')).not.toBeNull());
    const file = new File(["test-only"], "qualification_test.png", { type: "image/png" });
    Object.defineProperty(file, "arrayBuffer", { value: async () => new ArrayBuffer(9) });
    const input = host.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, "files", { value: [file] }); input.dispatchEvent(new Event("change"));
    await vi.waitFor(() => expect(account.createMediaUpload).toHaveBeenCalledOnce());
    app?.unmount(); app = undefined; expect(observed?.aborted).toBe(true);
  });
  it.each(["EMPTY", "UNINITIALIZED", "UNAVAILABLE"])("distinguishes %s without a fallback course query", async status => {
    api.hotCourses.mockResolvedValue({ status, items: [], validUntil: null }); await mount(HomePage);
    await vi.waitFor(() => expect(host.textContent).toContain({ EMPTY: "当前没有符合推荐条件", UNINITIALIZED: "正在准备中", UNAVAILABLE: "暂时不可用" }[status]!));
    expect(course.detail).not.toHaveBeenCalled(); expect(api.hotCourses).toHaveBeenCalledOnce();
  });
  it("keeps anonymous home public without reading enrollments", async () => {
    state.status = "anonymous"; await mount(HomePage); await vi.waitFor(() => expect(api.hotCourses).toHaveBeenCalledOnce());
    expect(learning.enrollments).not.toHaveBeenCalled(); expect(host.textContent).toContain("登录账号");
  });
  it("does not show empty learning while enrollments are loading", async () => {
    learning.enrollments.mockReturnValue(new Promise(() => {}));
    await mount(HomePage);
    expect(host.textContent).toContain("正在读取学习记录");
    expect(host.textContent).not.toContain("还没有报名课程");
  });
  it("shows empty learning only after a successful empty response", async () => {
    await mount(HomePage);
    await vi.waitFor(() => expect(host.textContent).toContain("还没有报名课程"));
    expect(host.textContent).not.toContain("正在读取学习记录");
    expect(button("重试学习记录")).toBeUndefined();
  });
  it("does not show empty learning alongside a normal enrollment", async () => {
    learning.enrollments.mockResolvedValue({ items: [{ id, courseId, status: "ACTIVE" }] });
    course.detail.mockResolvedValue({ id: courseId, title: "正常学习课程", chapters: [] });
    learning.progress.mockResolvedValue({ completedLessonCount: 1, lessonCount: 2, completionPercent: 50, lessons: [] });
    await mount(HomePage);
    await vi.waitFor(() => expect(host.textContent).toContain("继续学习 →"));
    expect(host.textContent).toContain("正常学习课程");
    expect(host.textContent).not.toContain("还没有报名课程");
    expect(button("重试学习信息")).toBeUndefined();
  });
  /** 报名和课程成功、进度失败时保留卡片与重试；点击后恢复进度也不能出现空态。 */
  it("keeps partial learning failure retry without showing empty learning", async () => {
    learning.enrollments.mockResolvedValue({ items: [{ id, courseId, status: "ACTIVE" }] });
    course.detail.mockResolvedValue({ id: courseId, title: "部分失败课程", chapters: [] });
    learning.progress.mockRejectedValueOnce(failure(503)).mockResolvedValue({ completedLessonCount: 1, lessonCount: 2, completionPercent: 50, lessons: [] });
    await mount(HomePage);
    await vi.waitFor(() => expect(button("重试学习信息")).toBeDefined());
    expect(host.textContent).toContain("部分失败课程");
    expect(host.textContent).toContain("进度暂不可用");
    expect(host.textContent).not.toContain("还没有报名课程");
    button("重试学习信息").click();
    await nextTick();
    expect(host.textContent).not.toContain("还没有报名课程");
    await vi.waitFor(() => expect(host.textContent).toContain("继续学习 →"));
    expect(learning.enrollments).toHaveBeenCalledTimes(2);
    expect(button("重试学习信息")).toBeUndefined();
    expect(host.textContent).not.toContain("还没有报名课程");
  });
  it("expires the server projection and isolates failed covers", async () => {
    api.hotCourses.mockResolvedValue({ status: "READY", validUntil: new Date(Date.now() + 10000).toISOString(), items: [{ courseId, title: "真实投影课", summary: "课程摘要", instructorDisplayName: "teacher_test", level: "BEGINNER", accessType: "PUBLIC", label: "NEW", coverStatus: "READY", coverUrl: "/cover.png", coverExpiresAt: "2099-01-01T00:00:00Z" }] });
    await mount(HomePage); await vi.waitFor(() => expect(host.querySelector("img")).not.toBeNull());
    host.querySelector("img")!.dispatchEvent(new Event("error")); await nextTick(); expect(host.textContent).toContain("封面暂不可用"); expect(host.textContent).toContain("真实投影课");
    vi.useFakeTimers(); vi.setSystemTime(Date.now() + 20000); document.dispatchEvent(new Event("visibilitychange")); await nextTick();
    // 可见恢复仅刷新一次；没有列表重算或持续定时请求。
    expect(api.hotCourses.mock.calls.length).toBeLessThanOrEqual(2);
  });
  it.each([401,403,404,409,422,429,503])("announcements show %s safely with finite requests", async status => {
    api.announcements.mockRejectedValue(failure(status)); await mount(CourseAnnouncements, "/", { courseId });
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull()); expect(api.confirm).not.toHaveBeenCalled(); expect(api.announcements).toHaveBeenCalledOnce();
    if (status === 429) { button("重新加载公告").click(); await nextTick(); expect(api.announcements).toHaveBeenCalledOnce(); }
    expect(host.textContent).not.toContain("本周安排"); app?.unmount(); app = undefined; expect(api.announcements.mock.calls[0][2].aborted).toBe(true);
  });
  it("renders text and deep-link focus without confirming on open", async () => {
    await mount(CourseAnnouncements, "/", { courseId, announcementId: id });
    await vi.waitFor(() => expect(document.activeElement?.id).toBe(`announcement-${id}`));
    expect(host.querySelector("script")).toBeNull(); expect(host.textContent).toContain("<script>"); expect(api.confirm).not.toHaveBeenCalled();
  });
  it("requires a second explicit click after revision conflict", async () => {
    api.confirm.mockRejectedValueOnce(failure(409)).mockResolvedValueOnce({}); await mount(CourseAnnouncements, "/", { courseId });
    await vi.waitFor(() => expect(button("我知道了")).toBeDefined());
    api.announcements.mockResolvedValue({ ...page(), latestUnconfirmed: { ...announcement(), revision: 2, body: "新版正文" } }); button("我知道了").click();
    await vi.waitFor(() => expect(host.textContent).toContain("新版正文")); expect(api.confirm).toHaveBeenCalledOnce();
    await vi.waitFor(() => expect(button("我知道了").disabled).toBe(false)); button("我知道了").click();
    await vi.waitFor(() => expect(api.confirm).toHaveBeenCalledTimes(2)); expect(api.confirm.mock.calls[1][2].expectedRevision).toBe(2);
  });
  it("clears protected text after withdrawal/permission loss", async () => {
    api.confirm.mockRejectedValue(failure(404)); await mount(CourseAnnouncements, "/", { courseId });
    await vi.waitFor(() => expect(button("我知道了")).toBeDefined()); button("我知道了").click(); await vi.waitFor(() => expect(host.textContent).not.toContain("<script>"));
  });
  it("read-all retains the server unconfirmed count and skips local confirmation", async () => {
    api.readAll.mockResolvedValue({ unreadCount: 2, unconfirmedAnnouncementCount: 2 }); await mount(NotificationsPage, "/notifications?page=2");
    await vi.waitFor(() => expect(api.notifications).toHaveBeenCalledWith(2, expect.any(AbortSignal))); await nextTick(); button("全部标为已读").click();
    await vi.waitFor(() => expect(host.textContent).toContain("仍有 2 条公告")); expect(api.confirm).not.toHaveBeenCalled();
  });
  it("does not read a message merely by resolving and navigating to its target", async () => {
    api.notifications.mockResolvedValue({ items: [{ id, kind: "COURSE_TASK", action: "OPENED", title: "任务通知", unread: true, confirmationRequired: false, occurredAt: "2026-10-01T00:00:00Z" }], page: 1, size: 20, total: 1 });
    api.target.mockResolvedValue({ targetType: "COURSE_TASK", targetId: id, courseId, revision: 3 }); const router = await mount(NotificationsPage, "/notifications");
    await vi.waitFor(() => expect(button("查看")).toBeDefined()); button("查看").click(); await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(`/learn/${courseId}/tasks`)); expect(api.read).not.toHaveBeenCalled();
  });
  it("never reads announcements or aborted targets; ordinary reads keep exact revision", async () => {
    const controller = new AbortController(); const target = { targetType: "COURSE_ANNOUNCEMENT" as const, targetId: id, courseId, revision: 4, paused: false, supportEmail: null };
    await readAfterRender(id, target, controller.signal); controller.abort(); await readAfterRender(id, { ...target, targetType: "COURSE_TASK" }, controller.signal); expect(api.read).not.toHaveBeenCalled();
    await readAfterRender(id, { ...target, targetType: "COURSE_TASK" }, new AbortController().signal); expect(api.read).toHaveBeenCalledWith(id, 4, expect.any(AbortSignal));
    expect(() => notificationRoute({ ...target, courseId: "https://external.invalid" }, id)).toThrow();
  });
  it.each([false,true])("APPROVED only enables teaching when refreshed account permission is %s", async granted => {
    account.teacherApplication.mockResolvedValue({ id, status: "APPROVED", updatedAt: "2026-10-01T00:00:00Z", statement: "申请说明", qualificationMediaId: id });
    account.ensureCurrentUser.mockImplementation(async () => { state.currentUser = { ...state.currentUser, roles: granted ? ["STUDENT", "TEACHER"] : ["STUDENT"], permissions: granted ? ["learning:access", "teaching:access"] : ["learning:access"] }; return true; });
    await mount(ApplyTeacherPage, "/apply-teacher"); await vi.waitFor(() => expect(host.textContent).toContain("审核通过"));
    expect(host.textContent?.includes("进入教学空间")).toBe(granted); expect(account.ensureCurrentUser).toHaveBeenCalledOnce();
  });
  it("offline status is unknown and must not become an empty application", async () => {
    account.teacherApplication.mockRejectedValue(new TypeError("offline")); await mount(ApplyTeacherPage, "/apply-teacher");
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull()); expect(host.querySelector("form")).toBeNull(); expect(account.ensureCurrentUser).not.toHaveBeenCalled();
  });
});
