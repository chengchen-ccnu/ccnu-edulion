<script setup lang="ts">
/**
 * 既有课程学习工作区：Course 提供发布目录与媒体授权，Learning 提供本人报名、单调进度和课时笔记。
 * 输入为受保护的 courseId/lessonId 深链接；页面按 learning:access 工作，不绑定 STUDENT 角色。
 * 切换课时或离页会中止目录/学习请求、丢弃旧进度队列；播放器继续销毁 HLS、定时器和短期 URL。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseDetail, CourseProgress, Enrollment, LearnerAssessmentSummary, LearningNote, LessonContent,
  LessonContentResource, LessonProgress } from "@edu/api-client";
import { createResourceActionController, HlsVideoPlayer, playbackErrorMessage, SafeArticleContent } from "@edu/ui";
import type { PlaybackProgressSample } from "@edu/ui";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { studentCourseApi } from "../course-api";
import { studentAssessmentApi } from "../assessment-api";
import { courseLearningEntries, findLearningEntry } from "../course-learning";
import { mergeCourseProgress, videoProgressPercent as calculateVideoProgressPercent } from "../learning-model";
import { isAuthenticationRequired, studentLearningApi } from "../learning-api";
import { createProgressSyncController } from "../progress-sync";
import type { ProgressSyncController } from "../progress-sync";

const route = useRoute();
const course = ref<CourseDetail | null>(null);
const content = ref<LessonContent | null>(null);
const enrollment = ref<Enrollment | null>(null);
const progress = ref<CourseProgress | null>(null);
const reportedProgress = ref<LessonProgress | null>(null);
const notes = ref<LearningNote[]>([]);
const lessonAssessments = ref<LearnerAssessmentSummary[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const enrollmentMessage = ref("");
const enrollmentError = ref("");
const progressError = ref("");
const notesError = ref("");
const assessmentsError = ref("");
const contentError = ref("");
const articleCompletionError = ref("");
const articleCompleted = ref(false);
const resourceError = ref("");
const noteContent = ref("");
const noteFieldError = ref("");
const noteSaving = ref(false);
const deletingNoteId = ref<string | null>(null);
const editingNoteId = ref<string | null>(null);
const editingContent = ref("");
const currentPositionSeconds = ref(0);
const videoDurationSeconds = ref(0);
const player = ref<{ seekTo(positionSeconds: number): void } | null>(null);
let pageAbort: AbortController | null = null;
let progressSync: ProgressSyncController | null = null;
let loadGeneration = 0;
let articleClientRequestId = "";
let articleCompletionStarted = false;
let articleRendered = false;
let learningFactsLoaded = false;
const resourceActions = createResourceActionController();

const enrolled = computed(() => enrollment.value?.status === "ACTIVE");
const entries = computed(() => course.value ? courseLearningEntries(course.value, enrolled.value) : []);
const current = computed(() => course.value && typeof route.params.lessonId === "string"
  ? findLearningEntry(course.value, route.params.lessonId) : null);
const currentIndex = computed(() => current.value
  ? entries.value.findIndex(item => item.lesson.id === current.value?.lesson.id) : -1);
const previous = computed(() => currentIndex.value > 0 ? entries.value[currentIndex.value - 1] : null);
const next = computed(() => currentIndex.value >= 0 && currentIndex.value < entries.value.length - 1
  ? entries.value[currentIndex.value + 1] : null);
const currentProgress = computed(() => progress.value?.lessons.find(item => item.lessonId === current.value?.lesson.id)
  ?? (reportedProgress.value?.lessonId === current.value?.lesson.id ? reportedProgress.value : null));
const learningFactsAllowed = computed(() => course.value?.accessType === "PUBLIC" || enrolled.value);
const videoProgressPercent = computed(() => {
  return calculateVideoProgressPercent(currentPositionSeconds.value, videoDurationSeconds.value,
    Boolean(currentProgress.value?.completed));
});

watch(() => [route.params.courseId, route.params.lessonId], loadPage, { immediate: true });
onBeforeUnmount(() => { cancelPage(); resourceActions.dispose(); });

/** 深链接与刷新先恢复发布课程，再独立读取报名、进度和笔记；部分失败不会移除播放器。 */
async function loadPage([courseId, lessonId]: unknown[]) {
  cancelPage();
  const generation = ++loadGeneration;
  resetPageState();
  if (typeof courseId !== "string" || typeof lessonId !== "string") {
    errorMessage.value = "课程学习地址无效。";
    return;
  }
  loading.value = true;
  const controller = new AbortController();
  pageAbort = controller;
  articleClientRequestId = crypto.randomUUID();
  try {
    const detail = await studentCourseApi.detail(courseId, controller.signal);
    if (stale(controller, generation)) return;
    course.value = detail;
    if (!findLearningEntry(detail, lessonId)) {
      errorMessage.value = "课时不存在，可能已更新或下架。";
      return;
    }
    try {
      content.value = await studentCourseApi.lessonContent(detail.id, lessonId, controller.signal);
    } catch (error) {
      if (!stale(controller, generation)) contentError.value = contentErrorMessage(error);
      return;
    }
    await loadLearningFacts(detail, lessonId, controller, generation);
    learningFactsLoaded = true;
    if (articleRendered) void completeRenderedArticle();
  } catch (error) {
    if (!stale(controller, generation)) errorMessage.value = detailErrorMessage(error);
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
}

/** PUBLIC 深链接代表明确开始学习，可幂等补报名；报名制试看不会隐式改变报名状态。 */
async function loadLearningFacts(detail: CourseDetail, lessonId: string, controller: AbortController,
    generation: number) {
  try {
    enrollment.value = await studentLearningApi.findActiveEnrollment(detail.id, controller.signal);
    if (!enrollment.value && detail.accessType === "PUBLIC") {
      enrollment.value = await studentLearningApi.enroll(detail.id, controller.signal);
      enrollmentMessage.value = "已加入我的学习。";
    }
  } catch (error) {
    if (!stale(controller, generation)) enrollmentError.value = learningErrorMessage(error, "报名状态暂时无法读取。");
  }
  if (stale(controller, generation)) return;
  if (detail.accessType === "ENROLLMENT_REQUIRED" && !enrolled.value) {
    progressError.value = "报名后才会记录正式课时进度。";
    notesError.value = "报名后才可创建和查看本课笔记。";
    return;
  }
  const [progressResult, notesResult, assessmentsResult] = await Promise.allSettled([
    studentLearningApi.progress(detail.id, controller.signal),
    studentLearningApi.notes(detail.id, lessonId, controller.signal),
    studentAssessmentApi.list({ page: 1, size: 100, courseId: detail.id }, controller.signal)
  ]);
  if (stale(controller, generation)) return;
  if (progressResult.status === "fulfilled") {
    progress.value = progressResult.value;
    currentPositionSeconds.value = progressResult.value.lessons.find(item => item.lessonId === lessonId)?.positionSeconds ?? 0;
    articleCompleted.value = Boolean(progressResult.value.lessons.find(item => item.lessonId === lessonId)?.completed);
  } else {
    progressError.value = learningErrorMessage(progressResult.reason, "课程进度暂时无法读取。");
  }
  if (notesResult.status === "fulfilled") notes.value = notesResult.value;
  else notesError.value = learningErrorMessage(notesResult.reason, "本课笔记暂时无法读取。");
  if (assessmentsResult.status === "fulfilled") {
    lessonAssessments.value = assessmentsResult.value.items.filter(item => item.lessonId === lessonId);
  } else assessmentsError.value = learningErrorMessage(assessmentsResult.reason, "本课练习暂时无法读取。");
  if (content.value?.type === "VIDEO") startProgressSync(detail.id, lessonId);
}

function startProgressSync(courseId: string, lessonId: string) {
  progressSync?.dispose();
  progressSync = createProgressSyncController(
    (body, signal) => studentLearningApi.reportProgress(courseId, lessonId, body, signal),
    value => {
      reportedProgress.value = value;
      if (progress.value) progress.value = mergeCourseProgress(progress.value, value);
      progressError.value = "";
    },
    error => { progressError.value = learningErrorMessage(error, "本次进度写入失败，后续播放仍会继续同步。"); }
  );
}

function handlePlaybackProgress(sample: PlaybackProgressSample) {
  if (learningFactsAllowed.value) progressSync?.enqueue(sample);
}

/** 播放授权始终使用当前发布课程资源行，页面从不把 mediaId 或 OSS 地址作为授权输入。 */
function getStudentGrant(signal: AbortSignal) {
  if (!course.value || !current.value || !content.value?.playbackResourceId) throw new Error("VIDEO_NOT_SELECTED");
  return studentCourseApi.playbackGrant(course.value.id, current.value.lesson.id, content.value.playbackResourceId, signal);
}

/** 仅在共享安全渲染器确认正文及全部正文图片成功后写入 0→100 完成事实。 */
async function handleArticleRendered() {
  articleRendered = true;
  if (learningFactsLoaded) await completeRenderedArticle();
}

async function completeRenderedArticle() {
  if (!course.value || !current.value || content.value?.type !== "ARTICLE" || articleCompletionStarted
      || !learningFactsAllowed.value || !pageAbort || pageAbort.signal.aborted) return;
  articleCompletionStarted = true;
  articleCompletionError.value = "";
  try {
    await studentLearningApi.completeArticle(course.value.id, current.value.lesson.id,
      { clientRequestId: articleClientRequestId }, pageAbort.signal);
    articleCompleted.value = true;
  } catch (error) {
    if (!pageAbort.signal.aborted) {
      articleCompletionError.value = "正文可继续阅读，完成状态尚未同步。";
      progressError.value = learningErrorMessage(error, articleCompletionError.value);
    }
  }
}

function handleArticleRenderError(message: string) {
  contentError.value = message;
}

/** 每次资料动作重新申请课程域授权；短期 URL 只用于当前 Blob，并由页面控制器统一清理。 */
async function runResourceAction(resource: LessonContentResource, action: "PREVIEW" | "DOWNLOAD") {
  if (!course.value || !current.value || !pageAbort) return;
  resourceError.value = "";
  try {
    await resourceActions.run(signal => studentCourseApi.resourceGrant(course.value!.id,
      current.value!.lesson.id, resource.id, action, signal), action, resource.displayName);
  } catch (error) { resourceError.value = resourceActionErrorMessage(error); }
}

function studentPlaybackErrorMessage(error: unknown): string {
  if (isAuthenticationRequired(error)) return "登录状态已失效，请重新登录后继续学习。";
  if (error instanceof GatewayRequestError && error.status === 403) {
    return enrolled.value
      ? "报名已经成功，播放访问权限仍在同步，请稍候手动重试。"
      : "当前课时需要先报名；试看课时仍可直接播放。";
  }
  return playbackErrorMessage(error);
}

async function reloadNotes() {
  if (!course.value || !current.value || !pageAbort || pageAbort.signal.aborted) return;
  notesError.value = "";
  try {
    notes.value = await studentLearningApi.notes(course.value.id, current.value.lesson.id, pageAbort.signal);
  } catch (error) {
    if (!pageAbort.signal.aborted) notesError.value = learningErrorMessage(error, "本课笔记暂时无法读取。");
  }
}

async function createNote() {
  if (!course.value || !current.value || !pageAbort || noteSaving.value) return;
  const noteText = noteContent.value.trim();
  noteFieldError.value = "";
  if (!noteText) {
    noteFieldError.value = "请输入笔记内容。";
    return;
  }
  noteSaving.value = true;
  try {
    const value = await studentLearningApi.createNote(course.value.id, current.value.lesson.id, noteText,
      content.value?.type === "VIDEO" && currentPositionSeconds.value > 0 ? Math.floor(currentPositionSeconds.value) : null,
      pageAbort.signal);
    notes.value = [value, ...notes.value];
    noteContent.value = "";
    notesError.value = "";
  } catch (error) {
    noteFieldError.value = noteErrorMessage(error);
  } finally {
    noteSaving.value = false;
  }
}

function beginEdit(note: LearningNote) {
  editingNoteId.value = note.id;
  editingContent.value = note.content;
  noteFieldError.value = "";
}

async function saveEdit(note: LearningNote) {
  if (!pageAbort || noteSaving.value) return;
  const content = editingContent.value.trim();
  if (!content) {
    noteFieldError.value = "笔记内容不能为空。";
    return;
  }
  noteSaving.value = true;
  noteFieldError.value = "";
  try {
    const updated = await studentLearningApi.updateNote(note.id, {
      expectedRevision: note.revision,
      content,
      positionSeconds: note.positionSeconds
    }, pageAbort.signal);
    notes.value = notes.value.map(item => item.id === updated.id ? updated : item);
    editingNoteId.value = null;
  } catch (error) {
    noteFieldError.value = noteErrorMessage(error);
  } finally {
    noteSaving.value = false;
  }
}

async function removeNote(note: LearningNote) {
  if (!pageAbort || deletingNoteId.value || !window.confirm("确认删除这条学习笔记？")) return;
  deletingNoteId.value = note.id;
  notesError.value = "";
  try {
    await studentLearningApi.deleteNote(note.id, pageAbort.signal);
    notes.value = notes.value.filter(item => item.id !== note.id);
  } catch (error) {
    notesError.value = learningErrorMessage(error, "笔记删除失败，请稍后重试。");
  } finally {
    deletingNoteId.value = null;
  }
}

function seekToNote(note: LearningNote) {
  if (note.positionSeconds !== null) player.value?.seekTo(note.positionSeconds);
}

function resetPageState() {
  course.value = null;
  content.value = null;
  enrollment.value = null;
  progress.value = null;
  reportedProgress.value = null;
  notes.value = [];
  lessonAssessments.value = [];
  errorMessage.value = "";
  enrollmentMessage.value = "";
  enrollmentError.value = "";
  progressError.value = "";
  notesError.value = "";
  assessmentsError.value = "";
  contentError.value = "";
  articleCompletionError.value = "";
  articleCompleted.value = false;
  articleCompletionStarted = false;
  articleRendered = false;
  learningFactsLoaded = false;
  resourceError.value = "";
  noteContent.value = "";
  noteFieldError.value = "";
  noteSaving.value = false;
  deletingNoteId.value = null;
  editingNoteId.value = null;
  currentPositionSeconds.value = 0;
  videoDurationSeconds.value = 0;
}

function stale(controller: AbortController, generation: number): boolean {
  return controller.signal.aborted || generation !== loadGeneration;
}

function detailErrorMessage(error: unknown): string {
  if (error instanceof GatewayRequestError && error.status === 404) return "课程不存在、尚未发布或已经下架。";
  if (error instanceof GatewayRequestError && error.status === 429) return "课程请求过于频繁，请稍后手动重试。";
  if (error instanceof TypeError) return "网络连接不可用，请检查网络后重试。";
  return error instanceof GatewayRequestError ? error.message : "学习页加载失败，请稍后重试。";
}

function contentErrorMessage(error: unknown): string {
  if (isAuthenticationRequired(error) || error instanceof GatewayRequestError && error.status === 401) return "登录状态已失效，请重新登录后读取课时内容。";
  if (error instanceof GatewayRequestError && error.status === 403) return "当前账号无权读取这个课时；未报名付费课程只能阅读允许试看的课时。";
  if (error instanceof GatewayRequestError && error.status === 404) return "课时正文不存在、已经下架或不属于当前发布版本。";
  if (error instanceof GatewayRequestError && error.status === 409) return "课时媒体尚未 READY，请稍后手动重试。";
  if (error instanceof GatewayRequestError && error.status === 429) return "课时内容请求过于频繁，请稍后手动重试。";
  if (error instanceof TypeError) return "当前处于离线状态，无法读取课时正文。";
  return error instanceof GatewayRequestError ? error.message : "课时内容加载失败，请稍后重试。";
}

function resourceActionErrorMessage(error: unknown): string {
  if (isAuthenticationRequired(error) || error instanceof GatewayRequestError && error.status === 401) return "登录状态已失效，请重新登录。";
  if (error instanceof GatewayRequestError && error.status === 403) return "当前账号不允许读取这份学习资料。";
  if (error instanceof GatewayRequestError && error.status === 404) return "资料不存在或不属于当前课时。";
  if (error instanceof GatewayRequestError && error.status === 409) return "资料尚未 READY，请稍后手动重试。";
  if (error instanceof GatewayRequestError && error.status === 422) return "该资料不支持所选动作。";
  if (error instanceof GatewayRequestError && error.status === 429) return "授权请求过于频繁，请稍后手动重试。";
  if (error instanceof TypeError) return "当前处于离线状态，正文仍可继续阅读。";
  return "资料读取失败，请手动重试。";
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
}

function lessonTypeLabel(type: CourseLearningEntryType): string {
  return ({ VIDEO: "视频", ARTICLE: "图文", ASSESSMENT: "测评" } as const)[type];
}

type CourseLearningEntryType = CourseDetail["chapters"][number]["lessons"][number]["type"];

function learningErrorMessage(error: unknown, fallback: string): string {
  if (isAuthenticationRequired(error)) return "登录状态已失效，请重新登录。";
  if (error instanceof TypeError) return "当前处于离线状态，请恢复网络后重试。";
  if (!(error instanceof GatewayRequestError)) return fallback;
  if (error.status === 401) return "登录状态已失效，请重新登录。";
  if (error.status === 403) return "当前账号没有访问该学习事实的权限，或尚未报名。";
  if (error.status === 404) return "课程、课时或学习记录不存在、已下架或不可见。";
  if (error.status === 409) return "学习状态已被其他请求更新，请刷新后重试。";
  if (error.status === 422) return "提交内容不符合要求，请修正后重试。";
  if (error.status === 429) return "请求过于频繁，有限重试仍未成功，请稍后手动重试。";
  if (error.status === 503) return "课程或学习服务暂时不可用，请稍后重试。";
  return error.message || fallback;
}

function noteErrorMessage(error: unknown): string {
  if (error instanceof GatewayRequestError && error.fieldErrors.content) return error.fieldErrors.content;
  if (error instanceof GatewayRequestError && error.status === 409) {
    return "这条笔记已在其他位置更新。你的输入已保留，请重新载入笔记后再应用。";
  }
  return learningErrorMessage(error, "笔记保存失败，请稍后重试。");
}

function formatSeconds(value: number): string {
  const minutes = Math.floor(value / 60);
  return `${minutes}:${String(Math.floor(value % 60)).padStart(2, "0")}`;
}

function cancelPage() {
  progressSync?.dispose();
  progressSync = null;
  pageAbort?.abort();
  pageAbort = null;
  resourceActions.dispose();
}
</script>

<template>
  <div class="learning-topbar">
    <RouterLink v-if="course" :to="`/courses/${course.id}`">← 退出学习</RouterLink>
    <RouterLink v-else to="/courses">← 返回课程中心</RouterLink>
    <span v-if="course">{{ course.title }} · 线上版本 {{ course.publishedVersion }}</span>
  </div>

  <div v-if="loading" class="learning-loading" role="status">正在恢复课程、报名与学习进度…</div>
  <section v-else-if="errorMessage && !current" class="learning-error" role="alert">
    <span class="section-kicker">LEARNING SPACE</span>
    <h2>暂时无法打开这一课</h2>
    <p>{{ errorMessage }}</p>
    <RouterLink v-if="course" class="button button--quiet" :to="`/courses/${course.id}`">返回课程详情</RouterLink>
    <button v-else class="button button--quiet" type="button"
      @click="loadPage([route.params.courseId, route.params.lessonId])">重新加载</button>
  </section>

  <div v-else-if="course && current" class="learning-layout">
    <main class="learning-main">
      <header class="learning-heading">
        <div>
          <span class="section-kicker">CURRENT LESSON</span>
          <h2>{{ current.lesson.title }}</h2>
          <p>{{ current.chapterTitle }} · 第 {{ current.lesson.sequence }} 课</p>
        </div>
        <span v-if="current.lesson.previewable" class="learning-preview-badge">试看</span>
      </header>

      <p v-if="enrollmentMessage" class="feedback feedback--success" role="status">{{ enrollmentMessage }}</p>
      <p v-if="enrollmentError" class="feedback feedback--warning" role="alert">{{ enrollmentError }}</p>
      <section v-if="contentError" class="lesson-content-error" role="alert">
        <h3>课时内容未能安全打开</h3><p>{{ contentError }}</p>
        <button class="button button--quiet" type="button" @click="loadPage([route.params.courseId,route.params.lessonId])">手动重试</button>
      </section>

      <template v-if="content?.type === 'VIDEO'">
        <HlsVideoPlayer v-if="content.playbackResourceId" ref="player"
          :grant-key="`${course.id}:${current.lesson.id}:${content.playbackResourceId}`"
          :title="`${current.lesson.title}视频`" :grant-provider="getStudentGrant" :poster-grant="content.poster"
          :initial-position-seconds="currentProgress?.positionSeconds ?? 0" :error-message-provider="studentPlaybackErrorMessage"
          @position-change="currentPositionSeconds = $event" @duration-change="videoDurationSeconds = $event"
          @playback-progress="handlePlaybackProgress" />
        <p v-else class="panel-error" role="alert">视频资源尚未 READY，不能开始播放或记录进度。</p>
      </template>
      <SafeArticleContent v-else-if="content?.type === 'ARTICLE'" :source="content.articleMarkdown ?? ''"
        :images="content.inlineImages" @rendered="handleArticleRendered" @render-error="handleArticleRenderError" />
      <section v-else-if="content?.type === 'ASSESSMENT'" class="lesson-content-empty">
        <h3>测评目录项</h3><p>请从下方“本课练习”进入已发布测评；本页不伪造正文或播放器。</p>
      </section>

      <section v-if="content?.resources.length" class="lesson-resources-panel" aria-labelledby="lesson-resources-heading">
        <header><div><span class="section-kicker">RESOURCES</span><h3 id="lesson-resources-heading">学习资料</h3></div></header>
        <ul><li v-for="resource in content.resources" :key="resource.id"><div><strong>{{ resource.displayName }}</strong>
          <small>{{ resource.mimeType }} · {{ formatBytes(resource.size) }}</small></div><div>
          <button v-if="resource.actions.includes('PREVIEW')" type="button" @click="runResourceAction(resource,'PREVIEW')">新标签预览</button>
          <button v-if="resource.actions.includes('DOWNLOAD')" type="button" @click="runResourceAction(resource,'DOWNLOAD')">下载</button>
        </div></li></ul><p v-if="resourceError" class="panel-error" role="alert">{{ resourceError }}</p>
      </section>

      <section v-if="content?.type === 'VIDEO' || content?.type === 'ARTICLE'" class="lesson-progress-panel" aria-labelledby="lesson-progress-heading">
        <div><span class="section-kicker">LESSON PROGRESS</span><h3 id="lesson-progress-heading">本课进度</h3></div>
        <strong>{{ content.type === 'ARTICLE' ? articleCompleted || currentProgress?.completed ? 100 : 0 : videoProgressPercent }}%</strong>
        <div class="learning-progress-bar" :aria-label="`本课进度 ${content.type === 'ARTICLE' ? articleCompleted || currentProgress?.completed ? 100 : 0 : videoProgressPercent}%`">
          <span :style="{ width: `${content.type === 'ARTICLE' ? articleCompleted || currentProgress?.completed ? 100 : 0 : videoProgressPercent}%` }" />
        </div>
        <p v-if="content.type === 'ARTICLE' && !learningFactsAllowed" class="panel-note">当前是未报名付费试看：可阅读正文和允许的资料，但不记录完成。</p>
        <p v-if="articleCompletionError" class="panel-error" role="status">{{ articleCompletionError }}</p>
        <p v-if="progressError && !articleCompletionError" class="panel-error" role="alert">{{ progressError }}</p>
      </section>

      <section class="learning-notes-panel" aria-labelledby="learning-notes-heading">
        <header>
          <div><span class="section-kicker">NOTES</span><h3 id="learning-notes-heading">本课笔记</h3></div>
          <span>{{ notes.length }} 条</span>
        </header>
        <form class="note-form" @submit.prevent="createNote">
          <label for="new-note">记录当前思路</label>
          <textarea id="new-note" v-model="noteContent" maxlength="10000" rows="4"
            :disabled="!learningFactsAllowed || noteSaving" placeholder="输入只属于你的学习笔记…" />
          <div><small>{{ content?.type === 'VIDEO' ? `保存时关联当前播放位置 ${formatSeconds(currentPositionSeconds)}` : '本课笔记不关联视频位置' }}</small>
            <button class="button button--primary" type="submit" :disabled="!learningFactsAllowed || noteSaving">
              {{ noteSaving ? "保存中…" : "保存笔记" }}
            </button></div>
          <p v-if="noteFieldError" class="panel-error" role="alert">{{ noteFieldError }}</p>
        </form>
        <div v-if="notesError" class="panel-error" role="alert">
          {{ notesError }} <button v-if="learningFactsAllowed" type="button" @click="reloadNotes">重新载入</button>
        </div>
        <ol v-if="notes.length" class="note-list">
          <li v-for="note in notes" :key="note.id">
            <template v-if="editingNoteId === note.id">
              <textarea v-model="editingContent" maxlength="10000" rows="3" :aria-label="`编辑笔记 ${note.id}`" />
              <div class="note-actions"><button type="button" :disabled="noteSaving" @click="saveEdit(note)">保存修改</button>
                <button type="button" @click="editingNoteId = null">取消</button></div>
            </template>
            <template v-else>
              <p>{{ note.content }}</p>
              <footer>
                <button v-if="content?.type === 'VIDEO' && note.positionSeconds !== null" type="button" @click="seekToNote(note)">跳到 {{ formatSeconds(note.positionSeconds) }}</button>
                <span>修订 {{ note.revision }} · {{ new Date(note.updatedAt).toLocaleString() }}</span>
                <button type="button" @click="beginEdit(note)">编辑</button>
                <button type="button" :disabled="deletingNoteId === note.id" @click="removeNote(note)">删除</button>
              </footer>
            </template>
          </li>
        </ol>
        <p v-else-if="!notesError" class="empty-inline">还没有笔记。播放课程时记录第一个关键点吧。</p>
      </section>

      <section class="lesson-assessments-panel" aria-labelledby="lesson-assessments-heading">
        <header><div><span class="section-kicker">ASSESSMENTS</span><h3 id="lesson-assessments-heading">本课练习</h3></div>
          <RouterLink to="/practice">查看练习中心</RouterLink></header>
        <p v-if="assessmentsError" class="panel-error" role="alert">{{ assessmentsError }}</p>
        <ul v-if="lessonAssessments.length"><li v-for="item in lessonAssessments" :key="item.id"><div><strong>{{ item.title }}</strong>
          <small>{{ item.type }} · {{ item.learnerState }}</small></div><RouterLink :to="`/practice/${item.id}`">进入测评</RouterLink></li></ul>
        <p v-else-if="!assessmentsError" class="empty-inline">当前课时没有已发布测评。</p>
      </section>

      <nav class="lesson-navigation" aria-label="课时导航">
        <RouterLink v-if="previous?.accessible" :to="`/learn/${course.id}/${previous.lesson.id}`"><small>上一课</small><strong>{{ previous.lesson.title }}</strong></RouterLink>
        <span v-else class="lesson-navigation__disabled"><small>上一课</small><strong>{{ previous ? "需要报名" : "已经是第一课" }}</strong></span>
        <RouterLink v-if="next?.accessible" :to="`/learn/${course.id}/${next.lesson.id}`"><small>下一课</small><strong>{{ next.lesson.title }}</strong></RouterLink>
        <span v-else class="lesson-navigation__disabled"><small>下一课</small><strong>{{ next ? "需要报名" : "已经是最后一课" }}</strong></span>
      </nav>
    </main>

    <aside class="learning-sidebar" aria-label="课程目录">
      <details open>
        <summary><span>课程目录</span><small>{{ entries.length }} 个课时</small></summary>
        <ol>
          <li v-for="entry in entries" :key="entry.lesson.id" :class="{ 'is-current': entry.lesson.id === current.lesson.id }">
            <RouterLink v-if="entry.accessible" :to="`/learn/${course.id}/${entry.lesson.id}`"
              :aria-current="entry.lesson.id === current.lesson.id ? 'page' : undefined">
              <span>{{ entry.chapterSequence }}.{{ entry.lesson.sequence }}</span><strong>{{ entry.lesson.title }}</strong>
              <small>{{ entry.lesson.previewable ? "试看" : lessonTypeLabel(entry.lesson.type) }}</small>
            </RouterLink>
            <div v-else aria-label="该课时需要报名"><span>{{ entry.chapterSequence }}.{{ entry.lesson.sequence }}</span>
              <strong>{{ entry.lesson.title }}</strong><small>需报名</small></div>
          </li>
        </ol>
      </details>
      <p>课时正文与资料只从受保护接口读取；公开课程详情不承载正文、mediaId 或资源绑定。</p>
    </aside>
  </div>
</template>
