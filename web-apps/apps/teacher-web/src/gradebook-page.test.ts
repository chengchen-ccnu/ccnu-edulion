// @vitest-environment jsdom
import { GatewayRequestError } from "@edu/api-client";
import type { Gradebook } from "@edu/api-client";
import { createApp, nextTick } from "vue";
import type { App } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TeacherGradebookPage from "./views/TeacherGradebookPage.vue";

const api = vi.hoisted(() => ({ get: vi.fn(), exportCsv: vi.fn() }));
vi.mock("./gradebook-api", () => ({ teacherGradebookApi: api }));
let app: App | undefined;
let host: HTMLDivElement;
const originalCreateUrl = Object.getOwnPropertyDescriptor(URL, "createObjectURL");
const originalRevokeUrl = Object.getOwnPropertyDescriptor(URL, "revokeObjectURL");

beforeEach(() => {
  api.get.mockReset().mockResolvedValue(gradebook());
  api.exportCsv.mockReset();
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => {
  app?.unmount(); app = undefined; host.remove(); vi.restoreAllMocks();
  for (const [name, descriptor] of [["createObjectURL", originalCreateUrl], ["revokeObjectURL", originalRevokeUrl]] as const) {
    if (descriptor) Object.defineProperty(URL, name, descriptor);
    else Reflect.deleteProperty(URL, name);
  }
});

/** 挂载实际页面和路由，API 测试替身仅用于可重复的故障/延时输入，不替代真实浏览器验收。 */
async function mount(query = "courseId=course-1&page=1&size=20&sort=STUDENT_NUMBER&direction=asc") {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/teacher/gradebook", component: TeacherGradebookPage },
    { path: "/teacher/assessments", component: { template: "<p>批改面板</p>" } }
  ] });
  await router.push(`/teacher/gradebook?${query}`); await router.isReady();
  app = createApp(TeacherGradebookPage); app.use(router); app.mount(host);
  await vi.waitFor(() => expect(api.get).toHaveBeenCalled()); await nextTick();
  return router;
}

function button(text: string) {
  const found = [...host.querySelectorAll("button")].find(item => item.textContent?.includes(text));
  if (!found) throw new Error(`Missing button ${text}`);
  return found;
}

function gradebook(overrides: Partial<Gradebook> = {}): Gradebook {
  return { courseId: "course-1", aggregateStatus: "READY", assessments: [], rows: [],
    homeworkAverage: 87.5, examAverage: null, nicknameDependencyFailed: false,
    page: 1, size: 20, total: 0, ...overrides };
}

describe("gradebook page behavior", () => {
  it("restores SCORE ordering after establishing that the selected scope is READY", async () => {
    await mount("courseId=course-1&sort=SCORE&direction=desc");
    await vi.waitFor(() => expect(api.get).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort: "SCORE", direction: "desc" }), expect.any(AbortSignal)));
  });

  it("keeps loaded facts and reports an offline request failure", async () => {
    await mount();
    api.get.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const keyword = host.querySelector<HTMLInputElement>('input[placeholder="课程学生编号或昵称"]')!;
    keyword.value = "0001"; keyword.dispatchEvent(new Event("change", { bubbles: true }));
    await vi.waitFor(() => expect(host.textContent).toContain("当前离线"));
    expect(host.textContent).toContain("87.5");
  });

  it("restores all URL filters and preserves them for CSV", async () => {
    const router = await mount("courseId=course-1&assessmentId=a1&type=HOMEWORK&submissionStatus=SUBMITTED&gradingStatus=GRADED&late=true&keyword=0001&page=2&size=10&sort=LAST_SUBMITTED_AT&direction=desc");
    expect(api.get.mock.calls[0][0]).toMatchObject({ courseId: "course-1", assessmentId: "a1", type: "HOMEWORK",
      submissionStatus: "SUBMITTED", gradingStatus: "GRADED", late: true, keyword: "0001", page: 2, size: 10,
      sort: "LAST_SUBMITTED_AT", direction: "desc" });
    api.exportCsv.mockImplementation(() => new Promise(() => undefined));
    button("下载当前筛选").click(); await nextTick();
    expect(api.exportCsv.mock.calls[0][0]).toMatchObject({ courseId: "course-1", assessmentId: "a1", type: "HOMEWORK",
      submissionStatus: "SUBMITTED", gradingStatus: "GRADED", late: true, keyword: "0001" });
    expect(api.exportCsv.mock.calls[0][0]).not.toHaveProperty("page");
    expect(router.currentRoute.value.query.keyword).toBe("0001");
  });

  it("hides PENDING scores and preserves identity degradation notice", async () => {
    api.get.mockResolvedValue(gradebook({ aggregateStatus: "PENDING", nicknameDependencyFailed: true }));
    const router = await mount("courseId=course-1&sort=SCORE");
    await vi.waitFor(() => expect(router.currentRoute.value.query.sort).toBe("STUDENT_NUMBER"));
    expect(host.textContent).toContain("部分昵称暂时无法加载");
    expect(host.textContent).toContain("处理中，成绩隐藏");
    expect(host.textContent).not.toContain("87.5");
  });

  it.each([401, 403, 404, 409, 422, 429, 503])("shows HTTP %s while retaining already loaded facts", async status => {
    await mount(); await vi.waitFor(() => expect(host.textContent).toContain("87.5"));
    api.get.mockRejectedValueOnce(new GatewayRequestError(new Response(null, { status })));
    const courseInput = host.querySelector("input")!;
    courseInput.dispatchEvent(new Event("change", { bubbles: true }));
    // 相同 URL 不发新请求；通过原有筛选触发真实组件加载逻辑。
    const keyword = host.querySelector<HTMLInputElement>('input[placeholder="课程学生编号或昵称"]')!;
    keyword.value = "0001"; keyword.dispatchEvent(new Event("change", { bubbles: true }));
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull());
    expect(host.textContent).toContain("87.5");
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it("cancels export and never creates a download after a late response", async () => {
    let resolve!: (value: { blob: Blob; filename: string }) => void;
    api.exportCsv.mockImplementation(() => new Promise(done => { resolve = done; }));
    const createUrl = vi.fn(); Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
    await mount(); button("下载当前筛选").click(); await nextTick();
    const signal = api.exportCsv.mock.calls[0][1] as AbortSignal;
    button("取消下载").click(); await nextTick(); expect(signal.aborted).toBe(true);
    resolve({ blob: new Blob(["csv"]), filename: "gradebook.csv" });
    await nextTick(); await nextTick();
    expect(createUrl).not.toHaveBeenCalled();
    expect(host.textContent).toContain("CSV 下载已取消");
  });

  it("aborts active read and export on unmount", async () => {
    api.get.mockImplementation(() => new Promise(() => undefined));
    api.exportCsv.mockImplementation(() => new Promise(() => undefined));
    await mount(); button("下载当前筛选").click(); await nextTick();
    const readSignal = api.get.mock.calls[0][1] as AbortSignal;
    const exportSignal = api.exportCsv.mock.calls[0][1] as AbortSignal;
    app!.unmount(); app = undefined;
    expect(readSignal.aborted).toBe(true); expect(exportSignal.aborted).toBe(true);
  });

  it("revokes the Object URL after starting a successful download", async () => {
    const createUrl = vi.fn(() => "blob:test-gradebook"); const revokeUrl = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeUrl });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    api.exportCsv.mockResolvedValue({ blob: new Blob(["csv"]), filename: "gradebook.csv" });
    await mount(); button("下载当前筛选").click();
    await vi.waitFor(() => expect(host.textContent).toContain("CSV 已开始下载"));
    expect(click).toHaveBeenCalledOnce(); expect(revokeUrl).toHaveBeenCalledWith("blob:test-gradebook");
  });
});
