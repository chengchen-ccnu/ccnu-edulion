<script setup lang="ts">
/**
 * 既有课程学习工作区：Course 提供发布目录与媒体授权，Learning 提供本人报名、单调进度和课时笔记。
 * 输入为受保护的 courseId/lessonId 深链接；页面按 learning:access 工作，不绑定 STUDENT 角色。
 * 切换课时或离页会中止目录/学习请求、丢弃旧进度队列；播放器继续销毁 HLS、定时器和短期 URL。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseDetail, CourseProgress, Enrollment, LearningNote, LessonProgress } from "@edu/api-client";
import { HlsVideoPlayer, playbackErrorMessage } from "@edu/ui";
import type { PlaybackProgressSample } from "@edu/ui";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { studentCourseApi } from "../course-api";
import { courseLearningEntries, findLearningEntry } from "../course-learning";
import { mergeCourseProgress } from "../learning-model";
import { isAuthenticationRequired, studentLearningApi } from "../learning-api";
import { createProgressSyncController } from "../progress-sync";
import type { ProgressSyncController } from "../progress-sync";

const route = useRoute();
const course = ref<CourseDetail | null>(null);
const enrollment = ref<Enrollment | null>(null);
const progress = ref<CourseProgress | null>(null);
const reportedProgress = ref<LessonProgress | null>(null);
const notes = ref<LearningNote[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const enrollmentMessage = ref("");
const enrollmentError = ref("");
const progressError = ref("");
const notesError = ref("");
const noteContent = ref("");
const noteFieldError = ref("");
const noteSaving = ref(false);
const deletingNoteId = ref<string | null>(null);
const editingNoteId = ref<string | null>(null);
const editingContent = ref("");
const currentPositionSeconds = ref(0);
const player = ref<{ seekTo(positionSeconds: number): void } | null>(null);
let pageAbort: AbortController | null = null;
let progressSync: ProgressSyncController | null = null;
let loadGeneration = 0;

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

watch(() => [route.params.courseId, route.params.lessonId], loadPage, { immediate: true });
onBeforeUnmount(cancelPage);

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
  try {
    const detail = await studentCourseApi.detail(courseId, controller.signal);
    if (stale(controller, generation)) return;
    course.value = detail;
    if (!findLearningEntry(detail, lessonId)) {
      errorMessage.value = "课时或视频资源不存在，可能已更新或下架。";
      return;
    }
    await loadLearningFacts(detail, lessonId, controller, generation);
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
  const [progressResult, notesResult] = await Promise.allSettled([
    studentLearningApi.progress(detail.id, controller.signal),
    studentLearningApi.notes(detail.id, lessonId, controller.signal)
  ]);
  if (stale(controller, generation)) return;
  if (progressResult.status === "fulfilled") {
    progress.value = progressResult.value;
    currentPositionSeconds.value = progressResult.value.lessons.find(item => item.lessonId === lessonId)?.positionSeconds ?? 0;
  } else {
    progressError.value = learningErrorMessage(progressResult.reason, "课程进度暂时无法读取。");
  }
  if (notesResult.status === "fulfilled") notes.value = notesResult.value;
  else notesError.value = learningErrorMessage(notesResult.reason, "本课笔记暂时无法读取。");
  startProgressSync(detail.id, lessonId);
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
  if (!course.value || !current.value) throw new Error("VIDEO_NOT_SELECTED");
  return studentCourseApi.playbackGrant(course.value.id, current.value.lesson.id, current.value.resourceId, signal);
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
  const content = noteContent.value.trim();
  noteFieldError.value = "";
  if (!content) {
    noteFieldError.value = "请输入笔记内容。";
    return;
  }
  noteSaving.value = true;
  try {
    const value = await studentLearningApi.createNote(course.value.id, current.value.lesson.id, content,
      currentPositionSeconds.value > 0 ? Math.floor(currentPositionSeconds.value) : null, pageAbort.signal);
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
  enrollment.value = null;
  progress.value = null;
  reportedProgress.value = null;
  notes.value = [];
  errorMessage.value = "";
  enrollmentMessage.value = "";
  enrollmentError.value = "";
  progressError.value = "";
  notesError.value = "";
  noteContent.value = "";
  noteFieldError.value = "";
  noteSaving.value = false;
  deletingNoteId.value = null;
  editingNoteId.value = null;
  currentPositionSeconds.value = 0;
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

      <HlsVideoPlayer ref="player" :grant-key="`${course.id}:${current.lesson.id}:${current.resourceId}`"
        :title="`${current.lesson.title}视频`" :grant-provider="getStudentGrant"
        :initial-position-seconds="currentProgress?.positionSeconds ?? 0"
        :error-message-provider="studentPlaybackErrorMessage"
        @position-change="currentPositionSeconds = $event" @playback-progress="handlePlaybackProgress" />

      <section class="lesson-progress-panel" aria-labelledby="lesson-progress-heading">
        <div><span class="section-kicker">PROGRESS</span><h3 id="lesson-progress-heading">学习进度</h3></div>
        <strong>{{ progress?.completionPercent ?? 0 }}%</strong>
        <div class="learning-progress-bar" :aria-label="`课程完成度 ${progress?.completionPercent ?? 0}%`">
          <span :style="{ width: `${progress?.completionPercent ?? 0}%` }" />
        </div>
        <p v-if="currentProgress">当前课时已记录到 {{ formatSeconds(currentProgress.positionSeconds) }}，累计观看
          {{ formatSeconds(currentProgress.watchedSeconds) }}{{ currentProgress.completed ? "，已完成" : "" }}。</p>
        <p v-else>播放后每隔一段时间、暂停或完成时同步单调进度。</p>
        <p v-if="progressError" class="panel-error" role="alert">{{ progressError }}</p>
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
          <div><small>保存时关联当前播放位置 {{ formatSeconds(currentPositionSeconds) }}</small>
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
                <button v-if="note.positionSeconds !== null" type="button" @click="seekToNote(note)">跳到 {{ formatSeconds(note.positionSeconds) }}</button>
                <span>修订 {{ note.revision }} · {{ new Date(note.updatedAt).toLocaleString() }}</span>
                <button type="button" @click="beginEdit(note)">编辑</button>
                <button type="button" :disabled="deletingNoteId === note.id" @click="removeNote(note)">删除</button>
              </footer>
            </template>
          </li>
        </ol>
        <p v-else-if="!notesError" class="empty-inline">还没有笔记。播放课程时记录第一个关键点吧。</p>
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
        <summary><span>课程目录</span><small>{{ entries.length }} 个视频课时</small></summary>
        <ol>
          <li v-for="entry in entries" :key="entry.lesson.id" :class="{ 'is-current': entry.lesson.id === current.lesson.id }">
            <RouterLink v-if="entry.accessible" :to="`/learn/${course.id}/${entry.lesson.id}`"
              :aria-current="entry.lesson.id === current.lesson.id ? 'page' : undefined">
              <span>{{ entry.chapterSequence }}.{{ entry.lesson.sequence }}</span><strong>{{ entry.lesson.title }}</strong>
              <small>{{ entry.lesson.previewable ? "试看" : "视频" }}</small>
            </RouterLink>
            <div v-else aria-label="该课时需要报名"><span>{{ entry.chapterSequence }}.{{ entry.lesson.sequence }}</span>
              <strong>{{ entry.lesson.title }}</strong><small>需报名</small></div>
          </li>
        </ol>
      </details>
      <p>当前学习页已接通真实进度和本人笔记；测评与 AI 将在后续任务中沿用本路由启用。</p>
    </aside>
  </div>
</template>
