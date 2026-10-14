// @vitest-environment jsdom
import { GatewayRequestError } from "@edu/api-client";
import type { AttemptResult, QuestionMedia } from "@edu/api-client";
import { resetAssessmentMediaSessionCache } from "@edu/ui";
import type { App } from "vue";
import { createApp, h, nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { resultLegacyMedia } from "./assessment-result-media";
import AttemptResultPage from "./views/AttemptResultPage.vue";

const api = vi.hoisted(() => ({ result: vi.fn(), courseTaskResult: vi.fn(), mediaGrant: vi.fn() }));
vi.mock("./assessment-api", () => ({ studentAssessmentApi: api }));
const mediaId = (index: number) => `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`;
const image = (sequence: number): QuestionMedia => ({ id: mediaId(sequence + 100), mediaId: mediaId(sequence),
  purpose: "QUESTION_IMAGE", sequence });

/** 纯内存的已公布自主练习快照，模拟现场旧图片占位；不创建数据库题目、作答或媒体。 */
function fixture(): AttemptResult {
  return { attemptId: "attempt-1", assessmentId: "assessment-1", learnerId: "student-test", status: "GRADED",
    revision: 1, gradeVisible: true, answerVisible: true, score: 1, totalPoints: 1,
    submittedAt: "2026-10-13T12:00:00Z", gradeUpdatedAt: null, attemptSequence: 1, finalAttempt: true,
    lateSubmission: false, assessment: { id: "assessment-1", courseId: null, lessonId: null, title: "图片练习",
      description: "", type: "PRACTICE", gradePublicationPolicy: "AUTOMATIC_AFTER_GRADING",
      answerPublicationPolicy: "AFTER_SUBMISSION", opensAt: null, closesAt: null, timeLimitSeconds: null,
      questions: [{ id: "question-1", questionVersionId: "version-1", sequence: 1, points: 1, type: "FILL_BLANK",
        stem: "方程的根是___", options: [], media: [image(1), image(2), image(3), image(4)], children: [] }] },
    answers: [{ assessmentQuestionId: "question-1", submittedAnswer: ["测试答案"], score: 1, correct: true,
      gradingStatus: "AUTO_GRADED", correctAnswer: ["2;-7"],
      analysis: "由题意可知：[图片1]或[图片2]，故答案为：[图片3]或[图片4]" }] };
}

let app: App | undefined;
let host: HTMLDivElement;
let createUrl: ReturnType<typeof vi.fn>;
let revokeUrl: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.resetAllMocks(); resetAssessmentMediaSessionCache();
  host = document.createElement("div"); document.body.append(host);
  api.result.mockResolvedValue(fixture()); api.courseTaskResult.mockResolvedValue(fixture());
  api.mediaGrant.mockResolvedValue({ url: "https://signed.example.test/image", headers: {}, expiresAt: "2099-01-01T00:00:00Z" });
  vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["test-image"], { type: "image/png" }), { status: 200 })));
  createUrl = vi.fn(() => `blob:result-${createUrl.mock.calls.length}`); revokeUrl = vi.fn();
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeUrl });
});
afterEach(() => {
  app?.unmount(); app = undefined; host.remove(); resetAssessmentMediaSessionCache(); vi.unstubAllGlobals();
});
/** 以真实结果页组件恢复给定深链接；API 与下载显式 Mock，返回路由以验证切题和离页行为。 */
async function mount(path = "/attempt/attempt-1/result") {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/attempt/:attemptId/result", name: "attempt-result", component: AttemptResultPage },
    { path: "/learn/:courseId/tasks/:assessmentId/result", name: "course-task-result", component: AttemptResultPage },
    { path: "/:all(.*)*", component: { template: "<p />" } }
  ] });
  await router.push(path); await router.isReady();
  app = createApp({ render: () => h(AttemptResultPage) }); app.use(router); app.mount(host); await nextTick();
  return router;
}

describe("result legacy image regions", () => {
  /** 题干、解析及两个答案都从图片1重启；必须按导入追加顺序取各区，不能把答案图替换成题干图。 */
  it("separates restarted numbering, repeated markers and unordered image bindings", () => {
    const question = { stem: "题干[图片1][图片1]", options: [{ key: "A", content: "选项[图片2]" }],
      media: [image(7), image(2), image(5), image(1), image(4), image(6), image(3)] };
    const mapped = resultLegacyMedia(question, { analysis: "解析[图片1][图片2]", correctAnswer: ["答案[图片1]", "答案[图片1][图片2]"] });
    expect(mapped.stem.map(item => item.mediaId)).toEqual([mediaId(1), mediaId(2)]);
    expect(mapped.analysis.map(item => item.mediaId)).toEqual([mediaId(3), mediaId(4)]);
    expect(mapped.answers.map(items => items.map(item => item.mediaId))).toEqual([[mediaId(5)], [mediaId(6), mediaId(7)]]);
    expect(question.media[0]?.mediaId).toBe(mediaId(7));
  });
  it("does not fabricate missing image bindings or map hidden references", () => {
    const question = { stem: "题干", options: [], media: [image(1)] };
    expect(resultLegacyMedia(question, undefined)).toEqual({ stem: [], analysis: [], answers: [] });
    expect(resultLegacyMedia(question, { analysis: "[图片2]", correctAnswer: null }).analysis).toHaveLength(1);
    expect(resultLegacyMedia(question, { analysis: `{{media:${mediaId(1)}}}`, correctAnswer: [] }).analysis).toEqual([]);
  });
});

describe("result page image authorization and rendering", () => {
  /** 与现场相同的四张解析图必须实际走授权与下载，结果 DOM 不保留图片占位或签名地址。 */
  it("renders all four analysis images and releases blob URLs on unmount", async () => {
    await mount();
    await vi.waitFor(() => expect(host.querySelectorAll(".result-reference img")).toHaveLength(4));
    expect(host.querySelector(".result-reference")?.textContent).not.toContain("[图片");
    expect(api.mediaGrant.mock.calls.map(call => call.slice(0, 3))).toEqual([1, 2, 3, 4]
      .map(index => ["assessment-1", mediaId(index), "INLINE"]));
    expect(host.innerHTML).not.toContain("signed.example.test");
    const urls = [...host.querySelectorAll<HTMLImageElement>("img")].map(item => item.src);
    app?.unmount(); app = undefined;
    urls.forEach(url => expect(revokeUrl).toHaveBeenCalledWith(url));
    expect(api.mediaGrant.mock.calls.every(call => call[3].aborted)).toBe(true);
  });
  it("maps stem, analysis and multiple answer images to their own regions", async () => {
    const result = fixture(); result.assessment.questions[0]!.stem = "题干[图片1]";
    result.assessment.questions[0]!.media = [1, 2, 3, 4].map(image);
    result.answers[0]!.analysis = "解析[图片1]"; result.answers[0]!.correctAnswer = ["答案甲[图片1]", "答案乙[图片1]"];
    api.result.mockResolvedValue(result); await mount();
    await vi.waitFor(() => expect(host.querySelectorAll("img")).toHaveLength(4));
    expect([...host.querySelectorAll<HTMLElement>(".result-reference [data-assessment-media-id]")]
      .map(item => item.dataset.assessmentMediaId)).toEqual([mediaId(3), mediaId(4), mediaId(2)]);
  });
  it("renders stable media markers through the same course-result authorization", async () => {
    const result = fixture(); result.assessment.courseId = "course-1";
    result.answers[0]!.analysis = `解析{{media:${mediaId(2)}}}`;
    result.answers[0]!.correctAnswer = [`答案{{media:${mediaId(1)}}}`];
    api.courseTaskResult.mockResolvedValue(result); await mount("/learn/course-1/tasks/assessment-1/result");
    await vi.waitFor(() => expect(host.querySelectorAll(".result-reference img")).toHaveLength(2));
    expect(api.courseTaskResult).toHaveBeenCalledWith("course-1", "assessment-1", expect.any(AbortSignal));
    expect(api.result).not.toHaveBeenCalled();
  });
  it("never renders or authorizes reference images before answers are published", async () => {
    const result = fixture(); result.answerVisible = false;
    // 故意保留不可公开的测试载荷，证明 DOM 与图片请求仍严格受 answerVisible 控制。
    api.result.mockResolvedValue(result); await mount();
    await vi.waitFor(() => expect(host.textContent).toContain("教师暂未公布"));
    expect(host.textContent).not.toContain("由题意可知"); expect(host.querySelectorAll("img")).toHaveLength(0);
    expect(api.mediaGrant).not.toHaveBeenCalled(); expect(fetch).not.toHaveBeenCalled();
  });
  it("keeps successful images and offers a manual retry for an authorization failure", async () => {
    let fail = true;
    api.mediaGrant.mockImplementation(async (_assessment, id) => {
      if (id === mediaId(2) && fail) throw new GatewayRequestError(new Response(null, { status: 403 }));
      return { url: "https://signed.example.test/image", headers: {}, expiresAt: "2099-01-01T00:00:00Z" };
    });
    await mount(); await vi.waitFor(() => expect(host.querySelectorAll(".result-reference img")).toHaveLength(3));
    expect(host.textContent).toContain("题图授权失败");
    expect(api.mediaGrant.mock.calls.filter(call => call[1] === mediaId(2))).toHaveLength(1);
    fail = false; host.querySelector<HTMLButtonElement>(".assessment-inline-media__retry")!.click();
    await vi.waitFor(() => expect(host.querySelectorAll(".result-reference img")).toHaveLength(4));
    expect(api.mediaGrant.mock.calls.filter(call => call[1] === mediaId(2))).toHaveLength(2);
  });
  it.each([401, 404, 409, 422, 429, 503, "offline"] as const)("keeps the result readable when reference media fails with %s", async status => {
    const result = fixture(); result.answers[0]!.analysis = "解析[图片1]";
    api.result.mockResolvedValue(result);
    const failure = status === "offline" ? new TypeError("offline") : new GatewayRequestError(new Response(null,
      { status, headers: status === 429 ? { "Retry-After": "0" } : {} }));
    api.mediaGrant.mockRejectedValue(failure); await mount();
    await vi.waitFor(() => expect(host.querySelector(".assessment-inline-media__retry")).not.toBeNull());
    expect(host.textContent).toContain("我的答案"); expect(host.textContent).toContain("2;-7");
    expect(api.mediaGrant).toHaveBeenCalledTimes(status === 429 || status === 503 || status === "offline" ? 2 : 1);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("restores the single-question deep link and revokes old blobs when switching views", async () => {
    const router = await mount("/attempt/attempt-1/result?view=single&question=1");
    await vi.waitFor(() => expect(host.querySelectorAll("img")).toHaveLength(4));
    const urls = [...host.querySelectorAll<HTMLImageElement>("img")].map(item => item.src);
    await router.replace("/attempt/attempt-1/result?view=all");
    await vi.waitFor(() => expect(host.querySelectorAll(".result-question")).toHaveLength(1));
    await vi.waitFor(() => expect(host.querySelectorAll("img")).toHaveLength(4));
    // 路由恢复会重新读取结果；旧 DOM 或真正离页时，其 URL 都必须释放。
    app?.unmount(); app = undefined; urls.forEach(url => expect(revokeUrl).toHaveBeenCalledWith(url));
  });
});
