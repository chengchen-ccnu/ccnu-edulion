<script setup lang="ts">
/**
 * 教师课程编排页；输入来自教师课程草稿，输出为携带 revision 的整目录替换与发布命令。
 * ARTICLE 正文、图片绑定和学习资料均显式保存；离页、类型互转、409/422 都不会静默覆盖未保存输入。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { ChapterInput, CourseMediaAccessGrant, LessonInput, LessonMediaResource,
  LessonMediaResourceInput, TeacherArticleContent, TeacherCourse } from "@edu/api-client";
import { createResourceActionController, HlsVideoPlayer, playbackErrorMessage } from "@edu/ui";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute } from "vue-router";

import { teacherCourseApi } from "../course-api";
import ArticleMarkdownEditor from "../components/ArticleMarkdownEditor.vue";
import MediaUploadField from "../components/MediaUploadField.vue";
import { publicationControls } from "../publication-controls";

type ResourceSnapshot = Pick<LessonMediaResource, "id" | "displayName" | "presentation" | "originalFileName" | "mimeType" | "size">;
type EditableResource = LessonMediaResourceInput & Partial<ResourceSnapshot> & { localUrl?: string | null };
type EditableLesson = Omit<LessonInput, "resources"> & { id?: string; uiKey: string; resources: EditableResource[];
  articleContent?: TeacherArticleContent };
type EditableChapter = Omit<ChapterInput, "lessons"> & { id?: string; uiKey: string; lessons: EditableLesson[] };
type TeacherVideoSelection = { lessonId: string; resourceId: string; title: string; posterGrant: CourseMediaAccessGrant | null };
type ReadyUpload = { mediaId: string; fileName: string; mimeType: string; size: number; previewUrl: string | null };
type ArticleContentState = "idle" | "loading" | "ready" | "error";

const route = useRoute();
const course = ref<TeacherCourse | null>(null);
const chapters = ref<EditableChapter[]>([]);
const loading = ref(true);
const saving = ref(false);
const publishing = ref(false);
const dirty = ref(false);
const errorMessage = ref("");
const successMessage = ref("");
const busyKeys = ref<Set<string>>(new Set());
const resourceError = ref<Record<string, string>>({});
const articleContentState = ref<Record<string, ArticleContentState>>({});
const articleContentError = ref<Record<string, string>>({});
const selectedVideo = ref<TeacherVideoSelection | null>(null);
const resourceActions = createResourceActionController();
let pageController: AbortController | null = null;
let loadGeneration = 0;
const articleContentRequests = new Map<string, Promise<TeacherArticleContent>>();

const publishBlocker = computed(() => {
  if (!course.value) return "课程尚未加载。";
  if (dirty.value) return "请先保存当前目录，再发布这个版本。";
  if (chapters.value.length === 0) return "至少添加一个章节后才能发布。";
  if (chapters.value.some(chapter => chapter.lessons.length === 0)) return "每个章节至少需要一个课时。";
  return "";
});
const canPublish = computed(() => publishBlocker.value === "");
const publication = computed(() => course.value ? publicationControls(course.value) : null);

onMounted(() => { window.addEventListener("beforeunload", warnBeforeUnload); void load(); });
onBeforeUnmount(() => { window.removeEventListener("beforeunload", warnBeforeUnload); disposePageRuntime(); });
onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm("目录还有未保存修改，确认离开并放弃这些修改？")) return false;
  disposePageRuntime();
  return true;
});
watch(() => String(route.params.courseId), (next, previous) => {
  if (next !== previous) void load();
});

async function load() {
  pageController?.abort();
  articleContentRequests.clear();
  resourceActions.dispose();
  const controller = new AbortController();
  pageController = controller;
  const currentGeneration = ++loadGeneration;
  loading.value = true;
  errorMessage.value = "";
  try {
    const snapshot = await teacherCourseApi.detail(String(route.params.courseId), controller.signal);
    if (!isCurrentPageRequest(controller, currentGeneration)) return;
    course.value = snapshot;
    syncChapters(snapshot);
    dirty.value = false;
    loading.value = false;
    hydrateSavedArticleContent(controller, currentGeneration);
  } catch (error) {
    if (isCurrentPageRequest(controller, currentGeneration)) errorMessage.value = messageFor(error, "课程目录加载失败");
  } finally {
    if (isCurrentPageRequest(controller, currentGeneration)) loading.value = false;
  }
}

function disposePageRuntime() {
  loadGeneration += 1;
  pageController?.abort();
  pageController = null;
  articleContentRequests.clear();
  resourceActions.dispose();
}

function isCurrentPageRequest(controller: AbortController, generation: number): boolean {
  return pageController === controller && loadGeneration === generation && !controller.signal.aborted;
}

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return;
  event.preventDefault();
  event.returnValue = "";
}

function addChapter() {
  chapters.value.push({ uiKey: crypto.randomUUID(), title: `第 ${chapters.value.length + 1} 章`,
    sequence: chapters.value.length + 1, lessons: [] });
  dirty.value = true;
}

function removeChapter(index: number) {
  if (!window.confirm("确认删除该章节及其全部课时？保存后才会提交到服务端。")) return;
  chapters.value.splice(index, 1); resequence(); dirty.value = true;
}

function moveChapter(index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= chapters.value.length) return;
  const [chapter] = chapters.value.splice(index, 1);
  chapters.value.splice(target, 0, chapter); resequence(); dirty.value = true;
}

function addLesson(chapter: EditableChapter) {
  chapter.lessons.push({ uiKey: crypto.randomUUID(), title: `第 ${chapter.lessons.length + 1} 课`, type: "VIDEO",
    sequence: chapter.lessons.length + 1, previewable: false, articleMarkdown: null, resources: [] });
  dirty.value = true;
}

function removeLesson(chapter: EditableChapter, index: number) {
  if (!window.confirm("确认删除该课时？保存后会释放不再引用的媒体绑定。")) return;
  chapter.lessons.splice(index, 1); resequenceLessons(chapter); dirty.value = true;
}

function moveLesson(chapter: EditableChapter, index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= chapter.lessons.length) return;
  const [lesson] = chapter.lessons.splice(index, 1);
  chapter.lessons.splice(target, 0, lesson); resequenceLessons(chapter); dirty.value = true;
}

function resequence() {
  chapters.value.forEach((chapter, index) => { chapter.sequence = index + 1; resequenceLessons(chapter); });
}

function resequenceLessons(chapter: EditableChapter) {
  chapter.lessons.forEach((lesson, index) => { lesson.sequence = index + 1; });
}

/** 保存时正文引用集合决定 ARTICLE_IMAGE 绑定集合；删除最后一个引用会由课程事务释放原绑定。 */
async function save() {
  if (!course.value) return;
  saving.value = true; errorMessage.value = ""; successMessage.value = ""; resequence();
  try {
    const updated = await teacherCourseApi.replaceCurriculum(course.value.id, {
      expectedRevision: course.value.revision,
      lessonIdentityMode: "EXPLICIT",
      chapters: chapters.value.map(chapter => ({ title: chapter.title, sequence: chapter.sequence,
        lessons: chapter.lessons.map(lesson => ({ id: lesson.id ?? null, title: lesson.title, type: lesson.type,
          sequence: lesson.sequence, previewable: lesson.previewable,
          articleMarkdown: lesson.type === "ARTICLE" ? lesson.articleMarkdown ?? "" : null,
          resources: writableResources(lesson) })) }))
    });
    course.value = updated; syncChapters(updated); dirty.value = false;
    if (pageController && !pageController.signal.aborted) hydrateSavedArticleContent(pageController, loadGeneration);
    successMessage.value = updated.publishedVersion
      ? `目录已保存到 ${updated.draftVersion ?? updated.version}，线上版本尚未改变。` : "课程目录已保存。";
  } catch (error) { errorMessage.value = messageFor(error, "课程目录保存失败"); }
  finally { saving.value = false; }
}

function writableResources(lesson: EditableLesson): LessonMediaResourceInput[] {
  const references = new Set([...String(lesson.articleMarkdown ?? "").matchAll(
    /!\[[^\]\n]+]\(media:([0-9a-fA-F-]{36})\)/g)].map(match => match[1].toLowerCase()));
  const visible = lesson.resources.filter(resource => lesson.type !== "ARTICLE" || resource.purpose !== "ARTICLE_IMAGE"
    || references.has(resource.mediaId.toLowerCase()));
  const nextSequence = new Map<string, number>();
  return visible.map(resource => {
    const sequence = (nextSequence.get(resource.purpose) ?? 0) + 1;
    nextSequence.set(resource.purpose, sequence);
    return { mediaId: resource.mediaId, purpose: resource.purpose, sequence,
      displayName: resource.displayName ?? null, presentation: resource.presentation ?? null };
  });
}

function syncChapters(snapshot: TeacherCourse) {
  chapters.value = snapshot.chapters.map(chapter => ({ id: chapter.id, uiKey: chapter.id, title: chapter.title,
    sequence: chapter.sequence, lessons: chapter.lessons.map(lesson => ({ id: lesson.id, uiKey: lesson.id,
      title: lesson.title, type: lesson.type, sequence: lesson.sequence, previewable: lesson.previewable,
      articleMarkdown: lesson.articleMarkdown ?? null,
      resources: (lesson.resources ?? []).map(resource => ({ ...resource })) })) }));
  articleContentState.value = Object.fromEntries(chapters.value.flatMap(chapter => chapter.lessons
    .filter(lesson => lesson.type === "ARTICLE" && lesson.id)
    .map(lesson => [lesson.uiKey, "idle" as const])));
  articleContentError.value = {};
}

/** 读取当前可编辑版本的已保存 ARTICLE 投影；失败只影响该正文图片，不吞掉其余目录内容。 */
function hydrateSavedArticleContent(controller: AbortController, generation: number) {
  const articleLessons = chapters.value.flatMap(chapter => chapter.lessons)
    .filter(lesson => lesson.type === "ARTICLE" && lesson.id);
  void Promise.all(articleLessons.map(lesson => loadArticleContent(lesson, controller, generation)));
}

/** 每个课程/课时只共享同一份 in-flight 请求，避免安全渲染器和页面状态重复申请授权。 */
function requestArticleContent(courseId: string, lessonId: string, signal: AbortSignal): Promise<TeacherArticleContent> {
  const key = `${courseId}:${lessonId}`;
  const existing = articleContentRequests.get(key);
  if (existing) return existing;
  const request = teacherCourseApi.articleContent(courseId, lessonId, signal);
  articleContentRequests.set(key, request);
  const clear = () => { if (articleContentRequests.get(key) === request) articleContentRequests.delete(key); };
  void request.then(clear, clear);
  return request;
}

async function loadArticleContent(lesson: EditableLesson, controller: AbortController, generation: number) {
  if (!course.value || !lesson.id || lesson.type !== "ARTICLE") return;
  setArticleContentState(lesson.uiKey, "loading", "");
  try {
    const projection = await requestArticleContent(course.value.id, lesson.id, controller.signal);
    if (!isCurrentPageRequest(controller, generation)) return;
    lesson.articleContent = projection;
    if (!dirty.value) lesson.articleMarkdown = projection.articleMarkdown;
    setArticleContentState(lesson.uiKey, "ready", "");
  } catch (error) {
    if (isCurrentPageRequest(controller, generation)) {
      setArticleContentState(lesson.uiKey, "error", articleContentMessage(error));
    }
  }
}

function setArticleContentState(key: string, state: ArticleContentState, message: string) {
  articleContentState.value = { ...articleContentState.value, [key]: state };
  articleContentError.value = { ...articleContentError.value, [key]: message };
}

function retryArticleContent(lesson: EditableLesson) {
  if (!pageController || pageController.signal.aborted || !lesson.id) return;
  void loadArticleContent(lesson, pageController, loadGeneration);
}

function mediaIdFor(lesson: EditableLesson, purpose: EditableResource["purpose"]): string | null {
  return lesson.resources.find(resource => resource.purpose === purpose)?.mediaId ?? null;
}

function setSingleMedia(lesson: EditableLesson, purpose: "LESSON_VIDEO" | "VIDEO_POSTER", mediaId: string | null) {
  const existing = lesson.resources.find(resource => resource.purpose === purpose);
  lesson.resources = lesson.resources.filter(resource => resource.purpose !== purpose);
  if (mediaId) lesson.resources.push({ mediaId, purpose, sequence: 1,
    id: existing?.mediaId === mediaId ? existing.id : undefined });
  selectedVideo.value = null; dirty.value = true;
}

/** 类型选择先确认再落入本地模型；取消时 select 和全部未保存正文/绑定保持原值。 */
function changeLessonType(lesson: EditableLesson, event: Event) {
  const select = event.target as HTMLSelectElement;
  const next = select.value as EditableLesson["type"];
  if (next === lesson.type) return;
  if (!window.confirm(`确认将课时从 ${typeLabel(lesson.type)} 转为 ${typeLabel(next)}？保存时会释放不兼容内容和媒体绑定。`)) {
    select.value = lesson.type; return;
  }
  lesson.type = next;
  if (next !== "ARTICLE") lesson.articleMarkdown = null;
  lesson.resources = lesson.resources.filter(resource => resource.purpose === "COURSEWARE"
    || next === "VIDEO" && ["LESSON_VIDEO", "VIDEO_POSTER", "SUBTITLE"].includes(resource.purpose)
    || next === "ARTICLE" && ["ARTICLE_ATTACHMENT", "ARTICLE_IMAGE"].includes(resource.purpose));
  selectedVideo.value = null; dirty.value = true;
}

function typeLabel(type: EditableLesson["type"]): string {
  return ({ VIDEO: "视频", ARTICLE: "图文", ASSESSMENT: "测评" } as const)[type];
}

function setBusy(key: string, busy: boolean) {
  const next = new Set(busyKeys.value); if (busy) next.add(key); else next.delete(key); busyKeys.value = next;
}

function addResource(lesson: EditableLesson, purpose: "COURSEWARE" | "ARTICLE_ATTACHMENT" | "ARTICLE_IMAGE", value: ReadyUpload) {
  if (lesson.resources.some(item => item.purpose === purpose && item.mediaId === value.mediaId)) return;
  lesson.resources.push({ mediaId: value.mediaId, purpose,
    sequence: lesson.resources.filter(item => item.purpose === purpose).length + 1,
    displayName: value.fileName, presentation: purpose === "ARTICLE_IMAGE" ? "INLINE" : defaultPresentation(value.mimeType),
    originalFileName: value.fileName, mimeType: value.mimeType, size: value.size, localUrl: value.previewUrl });
  dirty.value = true;
}

function removeResource(lesson: EditableLesson, resource: EditableResource) {
  if (!window.confirm(`从当前草稿移除“${resource.displayName ?? resource.originalFileName ?? "学习资料"}”？保存后解除绑定。`)) return;
  lesson.resources = lesson.resources.filter(item => item !== resource);
  if (resource.purpose === "ARTICLE_IMAGE") {
    lesson.articleMarkdown = String(lesson.articleMarkdown ?? "").replace(
      new RegExp(`!\\[[^\\]\\n]+]\\(media:${resource.mediaId}\\)\\n?`, "gi"), "");
  }
  dirty.value = true;
}

function learningResources(lesson: EditableLesson) {
  return lesson.resources.filter(item => item.purpose === "COURSEWARE" || item.purpose === "ARTICLE_ATTACHMENT");
}

function articleImages(lesson: EditableLesson) {
  const grants = new Map((lesson.articleContent?.inlineImages ?? [])
    .map(image => [image.mediaId.toLowerCase(), image.grant] as const));
  return lesson.resources.filter(item => item.purpose === "ARTICLE_IMAGE").map(item => ({
    mediaId: item.mediaId, grant: grants.get(item.mediaId.toLowerCase())
  }));
}

function defaultPresentation(mimeType: string): "INLINE" | "DOWNLOAD" {
  return mimeType === "application/pdf" || mimeType.startsWith("image/") ? "INLINE" : "DOWNLOAD";
}

function inlineAllowed(resource: EditableResource): boolean {
  return resource.mimeType === "application/pdf" || Boolean(resource.mimeType?.startsWith("image/"));
}

/** 新上传图片读取当前页面 Blob；已保存图片只经 owner 正文投影取得短期授权，过期时强制重新申请。 */
async function resolveTeacherImage(lesson: EditableLesson, mediaId: string, signal: AbortSignal,
    refresh = false): Promise<CourseMediaAccessGrant> {
  const resource = lesson.resources.find(item => item.purpose === "ARTICLE_IMAGE" && item.mediaId === mediaId);
  if (!course.value || !lesson.id || !resource) throw new Error("正文图片尚未绑定。");
  if (resource.localUrl) {
    if (!resource.mimeType || resource.size === undefined) throw new Error("正文图片缺少本地媒体元数据。");
    return {
      mediaId: resource.mediaId, mimeType: resource.mimeType, sizeBytes: resource.size,
      method: "GET", url: resource.localUrl, headers: {},
      expiresAt: new Date(Date.now() + 60_000).toISOString() };
  }
  const projected = lesson.articleContent?.inlineImages.find(image =>
    image.mediaId.toLowerCase() === mediaId.toLowerCase());
  if (projected && !refresh) return projected.grant;
  try {
    const projection = await requestArticleContent(course.value.id, lesson.id, signal);
    if (!signal.aborted && pageController && !pageController.signal.aborted) {
      lesson.articleContent = projection;
      setArticleContentState(lesson.uiKey, "ready", "");
    }
    const image = projection.inlineImages.find(item => item.mediaId.toLowerCase() === mediaId.toLowerCase());
    if (!image) throw new Error("正文图片未包含在当前保存投影中。");
    return image.grant;
  } catch (error) {
    if (!signal.aborted) setArticleContentState(lesson.uiKey, "error", articleContentMessage(error));
    throw error;
  }
}

async function runResourceAction(lesson: EditableLesson, resource: EditableResource, action: "PREVIEW" | "DOWNLOAD") {
  if (!course.value || !lesson.id || !resource.id) { resourceError.value[lesson.uiKey] = "新资料需先保存目录再预览或下载。"; return; }
  resourceError.value[lesson.uiKey] = "";
  try {
    await resourceActions.run(signal => teacherCourseApi.resourceGrant(course.value!.id, lesson.id!, resource.id!, action, signal),
      action, resource.displayName ?? resource.originalFileName ?? "course-resource");
  } catch (error) { resourceError.value[lesson.uiKey] = resourceActionMessage(error); }
}

async function selectTeacherVideo(lesson: EditableLesson, resource: EditableResource) {
  if (!course.value || !lesson.id || !resource.id) return;
  let posterGrant: CourseMediaAccessGrant | null = null;
  const poster = lesson.resources.find(item => item.purpose === "VIDEO_POSTER" && item.id);
  if (poster?.id) {
    try { posterGrant = await teacherCourseApi.resourceGrant(course.value.id, lesson.id, poster.id, "PREVIEW"); }
    catch { /* 自定义海报失败时播放器回退固定占位图，不影响 owner 视频预览。 */ }
  }
  selectedVideo.value = { lessonId: lesson.id, resourceId: resource.id, title: lesson.title, posterGrant };
}

function getTeacherGrant(signal: AbortSignal) {
  if (!course.value || !selectedVideo.value) throw new Error("VIDEO_NOT_SELECTED");
  return teacherCourseApi.playbackGrant(course.value.id, selectedVideo.value.lessonId, selectedVideo.value.resourceId, signal);
}

function teacherPlaybackErrorMessage(error: unknown): string {
  return error instanceof GatewayRequestError && error.status === 403 ? "当前教师身份无权预览该课程视频。" : playbackErrorMessage(error);
}

async function updatePublication(status: "PUBLISHED" | "UNPUBLISHED") {
  if (!course.value || dirty.value) return;
  const prompt = status === "PUBLISHED" ? `确认发布“${course.value.title}”的 ${course.value.version} 版本？`
    : `确认下架“${course.value.title}”？学生端将立即不可见。`;
  if (!window.confirm(prompt)) return;
  publishing.value = true; errorMessage.value = ""; successMessage.value = "";
  try {
    await teacherCourseApi.updatePublication(course.value.id, { status, expectedRevision: course.value.revision });
    await load(); successMessage.value = status === "PUBLISHED" ? "课程已经发布到学生端。" : "课程已经下架。";
  } catch (error) { errorMessage.value = messageFor(error, status === "PUBLISHED" ? "课程发布失败" : "课程下架失败"); }
  finally { publishing.value = false; }
}

function articleContentMessage(error: unknown): string {
  if (error instanceof GatewayRequestError && error.status === 401) return "登录状态已失效，请重新登录后读取已保存正文。";
  if (error instanceof Error && error.message === "AUTHENTICATION_REQUIRED") return "登录状态已失效，请重新登录后读取已保存正文。";
  if (error instanceof GatewayRequestError && error.status === 403) return "当前教师身份无权读取这份正文。";
  if (error instanceof GatewayRequestError && error.status === 404) return "课程、课时不存在或正文当前不可见。";
  if (error instanceof GatewayRequestError && error.status === 409) return "已保存正文与图片绑定不一致，请修复正文引用后再保存。";
  if (error instanceof GatewayRequestError && error.status === 429) return "正文授权请求过于频繁，请稍后手动重试。";
  if (error instanceof GatewayRequestError && error.status === 503) return "正文图片授权服务暂时不可用，请稍后重试。";
  if (error instanceof TypeError) return "当前网络不可用，已保存正文仍保留在编辑区，可恢复网络后重试。";
  return "已保存正文暂时无法读取，请手动重试。";
}

function resourceActionMessage(error: unknown): string {
  if (error instanceof GatewayRequestError && error.status === 422) return "该文件类型不允许执行这个动作。";
  if (error instanceof GatewayRequestError && error.status === 429) return "授权请求过于频繁，请稍后手动重试。";
  if (error instanceof TypeError) return "当前网络不可用，资料仍保留在页面中。";
  return error instanceof GatewayRequestError ? error.message : "资料读取失败，请手动重试。";
}

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof GatewayRequestError && error.status === 401) return "登录状态已失效，请重新登录后继续编辑。";
  if (error instanceof Error && error.message === "AUTHENTICATION_REQUIRED") return "登录状态已失效，请重新登录后继续编辑。";
  if (error instanceof GatewayRequestError && error.status === 403) return "当前教师身份无权编辑这门课程。";
  if (error instanceof GatewayRequestError && error.status === 409) return "课程版本已变化。你的输入仍保留，请另行核对后再决定是否重新加载。";
  if (error instanceof GatewayRequestError && error.status === 422) return error.message;
  if (error instanceof GatewayRequestError && error.status === 429) return "请求过于频繁，请稍后手动重试。";
  if (error instanceof GatewayRequestError && error.status === 503) return "课程服务暂时不可用，请稍后重试。";
  if (error instanceof GatewayRequestError && error.status === 404) return "课程不存在或不属于当前教师。";
  if (error instanceof TypeError) return "当前网络不可用，你的未保存输入仍保留。";
  return error instanceof GatewayRequestError ? error.message : fallback;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-courses' }">← 返回课程管理</RouterLink>
  <div v-if="loading" class="teacher-loading" role="status">正在读取章节课时…</div>
  <section v-else-if="course" class="editor-shell curriculum-editor">
    <header class="editor-heading"><div><span class="section-kicker">CURRICULUM EDITOR</span><h2>{{ course.title }}</h2></div>
      <div class="version-stack"><strong>{{ course.version }}</strong><span>revision {{ course.revision }}</span></div></header>
    <div class="editor-subnav"><RouterLink :to="{ name: 'teacher-course-edit', params: { courseId: course.id } }">基本信息</RouterLink>
      <strong>章节与课时</strong><RouterLink :to="{ name: 'teacher-assessments', query: { courseId: course.id, panel: 'basic' } }">管理课程测评</RouterLink>
      <RouterLink :to="{ name: 'teacher-course-announcements', params: { courseId: course.id }, query: { from: 'curriculum' } }">课程公告</RouterLink>
      <span>草稿显式保存；正文预览与学生端共用安全规则</span></div>
    <p v-if="course.publishedVersion && course.hasUnpublishedChanges" class="version-notice">当前线上版本为 {{ course.publishedVersion }}；发布 {{ course.draftVersion }} 前，学生端不会改变。</p>
    <p v-if="dirty" class="feedback feedback--warning" role="status">有尚未保存的修改。保存后才能发布或下架。</p>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>

    <form @submit.prevent="save" @input="dirty = true">
      <ol v-if="chapters.length" class="curriculum-editor__chapters">
        <li v-for="(chapter, chapterIndex) in chapters" :key="chapter.uiKey" class="curriculum-chapter">
          <header><span class="order-number">{{ String(chapter.sequence).padStart(2, "0") }}</span>
            <label><span>章节名称</span><input v-model="chapter.title" required maxlength="120"></label>
            <div class="order-actions"><button type="button" :disabled="chapterIndex === 0" title="章节上移" @click="moveChapter(chapterIndex,-1)">↑</button>
              <button type="button" :disabled="chapterIndex === chapters.length-1" title="章节下移" @click="moveChapter(chapterIndex,1)">↓</button>
              <button class="danger-link" type="button" @click="removeChapter(chapterIndex)">删除章</button></div></header>
          <ol class="curriculum-lessons">
            <li v-for="(lesson, lessonIndex) in chapter.lessons" :key="lesson.uiKey" class="curriculum-lesson">
              <span class="lesson-order">{{ lesson.sequence }}</span>
              <label><span>课时名称</span><input v-model="lesson.title" required maxlength="120"></label>
              <label><span>目录类型</span><select :value="lesson.type" @change="changeLessonType(lesson,$event)">
                <option value="VIDEO">视频</option><option value="ARTICLE">图文</option><option value="ASSESSMENT">测评目录项</option></select></label>
              <label class="preview-toggle"><input v-model="lesson.previewable" type="checkbox"><span>允许试看</span></label>
              <div class="order-actions"><button type="button" :disabled="lessonIndex===0" title="课时上移" @click="moveLesson(chapter,lessonIndex,-1)">↑</button>
                <button type="button" :disabled="lessonIndex===chapter.lessons.length-1" title="课时下移" @click="moveLesson(chapter,lessonIndex,1)">↓</button>
                <button class="danger-link" type="button" @click="removeLesson(chapter,lessonIndex)">删除</button></div>

              <section v-if="lesson.type === 'VIDEO'" class="lesson-content-editor lesson-video-editor">
                <MediaUploadField v-if="lesson.id" :model-value="mediaIdFor(lesson,'LESSON_VIDEO')" purpose="LESSON_VIDEO"
                  accept="video/mp4,video/webm,video/quicktime" label="课时视频" :checkpoint-key="`teacher-video:${course.id}:${lesson.id}`"
                  @update:model-value="setSingleMedia(lesson,'LESSON_VIDEO',$event)" @busy-change="setBusy(`video:${lesson.uiKey}`,$event)" />
                <p v-else>请先保存目录，为新课时生成稳定 ID 后再上传视频。</p>
                <MediaUploadField v-if="lesson.id" :model-value="mediaIdFor(lesson,'VIDEO_POSTER')" purpose="VIDEO_POSTER"
                  accept="image/jpeg,image/png,image/webp" label="自定义视频海报（可选）" :checkpoint-key="`teacher-poster:${course.id}:${lesson.id}`"
                  @update:model-value="setSingleMedia(lesson,'VIDEO_POSTER',$event)" @busy-change="setBusy(`poster:${lesson.uiKey}`,$event)" />
                <button v-if="lesson.id && lesson.resources.find(item=>item.purpose==='LESSON_VIDEO')?.id" class="button button--quiet" type="button"
                  @click="selectTeacherVideo(lesson,lesson.resources.find(item=>item.purpose==='LESSON_VIDEO')!)">owner 预览</button>
              </section>

              <p v-if="lesson.type === 'ARTICLE' && articleContentState[lesson.uiKey] === 'loading'"
                class="feedback feedback--info" role="status">正在读取已保存正文和短期图片授权…</p>
              <p v-if="lesson.type === 'ARTICLE' && articleContentState[lesson.uiKey] === 'error'"
                class="feedback feedback--warning" role="alert">{{ articleContentError[lesson.uiKey] }}
                <button type="button" @click="retryArticleContent(lesson)">重新读取已保存正文</button></p>
              <ArticleMarkdownEditor v-if="lesson.type === 'ARTICLE'" class="lesson-content-editor"
                :model-value="lesson.articleMarkdown ?? ''" :lesson-id="lesson.id" :images="articleImages(lesson)"
                :checkpoint-key="`teacher-article-image:${course.id}:${lesson.id ?? lesson.uiKey}`"
                :resolve-image="(mediaId,signal,refresh)=>resolveTeacherImage(lesson,mediaId,signal,refresh)"
                @update:model-value="lesson.articleMarkdown=$event; dirty=true" @image-ready="addResource(lesson,'ARTICLE_IMAGE',$event)" />

              <section v-if="lesson.type !== 'ASSESSMENT'" class="lesson-content-editor lesson-resources-editor">
                <h4>学习资料</h4><p>PDF/普通图片可预览或下载；PPTX/TXT 仅下载。正文图片不会显示为独立资料。</p>
                <div v-if="lesson.id" class="resource-upload-grid">
                  <MediaUploadField :model-value="null" purpose="COURSEWARE" accept="application/pdf,image/jpeg,image/png,image/webp,text/plain,application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    label="上传通用课件" :checkpoint-key="`teacher-courseware:${course.id}:${lesson.id}`"
                    @ready="addResource(lesson,'COURSEWARE',$event)" @busy-change="setBusy(`courseware:${lesson.uiKey}`,$event)" />
                  <MediaUploadField v-if="lesson.type==='ARTICLE'" :model-value="null" purpose="ARTICLE_ATTACHMENT"
                    accept="application/pdf,image/jpeg,image/png,image/webp,text/plain,application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    label="上传图文附件" :checkpoint-key="`teacher-article-attachment:${course.id}:${lesson.id}`"
                    @ready="addResource(lesson,'ARTICLE_ATTACHMENT',$event)" @busy-change="setBusy(`attachment:${lesson.uiKey}`,$event)" />
                </div>
                <p v-else>请先保存目录，再上传学习资料。</p>
                <ul v-if="learningResources(lesson).length" class="resource-editor-list">
                  <li v-for="resource in learningResources(lesson)" :key="resource.id ?? resource.mediaId">
                    <label><span>显示名称</span><input v-model="resource.displayName" required maxlength="120"></label>
                    <label><span>主要动作</span><select v-model="resource.presentation" :disabled="!inlineAllowed(resource)">
                      <option value="INLINE">预览</option><option value="DOWNLOAD">下载</option></select></label>
                    <small>{{ resource.mimeType || '保存后识别类型' }} · {{ resource.size ?? 0 }} B</small>
                    <div><button v-if="inlineAllowed(resource)" type="button" @click="runResourceAction(lesson,resource,'PREVIEW')">预览</button>
                      <button type="button" @click="runResourceAction(lesson,resource,'DOWNLOAD')">下载</button>
                      <button type="button" class="danger-link" @click="removeResource(lesson,resource)">移除</button></div>
                  </li>
                </ul>
                <p v-if="resourceError[lesson.uiKey]" class="panel-error" role="alert">{{ resourceError[lesson.uiKey] }}</p>
              </section>
            </li>
          </ol>
          <button class="add-row" type="button" @click="addLesson(chapter)">＋ 添加课时</button>
        </li>
      </ol>
      <div v-else class="curriculum-empty"><span>目录还是空的</span><h3>从第一个章节开始</h3><p>发布要求至少一个章节，且每章至少包含一个课时。</p></div>
      <button class="add-chapter" type="button" @click="addChapter">＋ 添加章节</button>
      <p class="scope-note">目录写入仅提交 OpenAPI 生成字段和稳定 mediaId；不提交 Bucket、Object Key 或签名 URL，SUBTITLE 也不会出现任何页面入口。</p>
      <section v-if="selectedVideo" class="teacher-video-preview"><header><strong>{{ selectedVideo.title }}</strong><button type="button" @click="selectedVideo=null">关闭预览</button></header>
        <HlsVideoPlayer :grant-key="`${course.id}:${selectedVideo.lessonId}:${selectedVideo.resourceId}`" :title="`${selectedVideo.title}教师预览`"
          :grant-provider="getTeacherGrant" :poster-grant="selectedVideo.posterGrant" :error-message-provider="teacherPlaybackErrorMessage" /></section>
      <footer class="editor-actions editor-actions--split"><div class="publication-actions">
        <button v-if="publication?.showPublish" class="button button--publish" type="button" :disabled="publishing||!canPublish" :title="publishBlocker||undefined" @click="updatePublication('PUBLISHED')">{{ publication.publishLabel }}</button>
        <button v-if="publication?.showUnpublish" class="button button--danger" type="button" :disabled="publishing||dirty" @click="updatePublication('UNPUBLISHED')">下架课程</button>
        <small v-if="publication?.showPublish&&publishBlocker" class="publication-blocker" role="status">{{ publishBlocker }}</small></div>
        <span v-if="!dirty&&!saving" class="editor-save-confirmation" role="status">✓ 当前目录已保存</span>
        <button v-else class="button button--primary" type="submit" :disabled="saving||busyKeys.size>0">{{ saving ? '保存中…' : '保存目录' }}</button></footer>
    </form>
  </section>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
