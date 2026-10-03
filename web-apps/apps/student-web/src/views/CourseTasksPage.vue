<script setup lang="ts">
/**
 * 课程任务总览：URL 显式 type=PRACTICE 时只读取本课程练习，省略时保留作业/考试总览。
 * 输入是受保护路由中的 courseId 与 URL 分页；输出是服务端状态驱动的课程内导航。
 * 请求离页会中止，已有数据在部分失败或离线时保留，避免把失败误报为空列表。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AssessmentGradingStatus, AssessmentSubmissionStatus, AssessmentTimeState, LearnerCourseTaskPage,
  LearnerCourseTaskSummary, LearnerCourseTask } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";
import { operationsApi } from "../operations-api";
import { isUuid, readAfterRender } from "../notification-target";
import { usePortalFailure } from "../portal-state";
import { authStore } from "../auth";

const route = useRoute();
const router = useRouter();
const data = ref<LearnerCourseTaskPage | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const linkedTask = ref<LearnerCourseTask | null>(null);
const landingFailure = usePortalFailure(), landingError = landingFailure.error;
let landingController: AbortController | null = null;
let controller: AbortController | null = null;
let loadedScope = "";

const courseId = computed(() => String(route.params.courseId ?? ""));
const page = computed(() => positive(route.query.page, 1));
const size = computed(() => positive(route.query.size, 20));
const isPractice = computed(() => route.query.type === "PRACTICE");

watch(() => [route.params.courseId, route.query.page, route.query.size, route.query.type], load, { immediate: true });
watch([() => route.params.courseId, () => route.query.notificationId, () => authStore.state.currentUser?.id], loadLinkedTask, { immediate: true });
onBeforeUnmount(() => { controller?.abort(); landingController?.abort(); });

/** 消息定位的任务可在当前分页之外；单条真实授权详情成功后再标已读，不遍历全课程任务。 */
async function loadLinkedTask() {
  landingController?.abort(); linkedTask.value = null; landingError.value = "";
  if (!isUuid(route.query.notificationId) || !landingFailure.allowed()) return;
  const active = new AbortController(); landingController = active;
  try {
    const target = await operationsApi.target(route.query.notificationId, active.signal);
    if (target.targetType !== "COURSE_TASK" || target.courseId !== courseId.value) throw new Error("TARGET_MISMATCH");
    const detail = await studentAssessmentApi.courseTask(courseId.value, target.targetId, undefined, active.signal);
    if (active.signal.aborted) return;
    linkedTask.value = detail;
    await readAfterRender(route.query.notificationId, target, active.signal);
  } catch (error) { if (!active.signal.aborted) { landingFailure.fail(error); if ([401,403,404].includes((error as { status?: number }).status ?? 0)) linkedTask.value = null; } }
}

async function load() {
  controller?.abort();
  // 切课或切换任务类型时清除旧摘要，避免旧任务被展示在新的课程上下文。
  const scope = `${courseId.value}:${isPractice.value}`;
  if (scope !== loadedScope) data.value = null;
  loadedScope = scope;
  const active = new AbortController();
  controller = active;
  loading.value = true;
  errorMessage.value = "";
  try {
    const result = await studentAssessmentApi.listCourseTasks(courseId.value,
      { page: page.value, size: Math.min(100, size.value), type: isPractice.value ? "PRACTICE" : undefined }, active.signal);
    if (!active.signal.aborted) data.value = result;
  } catch (error) {
    if (!active.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!active.signal.aborted) loading.value = false;
  }
}

async function openTask(task: LearnerCourseTaskSummary | LearnerCourseTask) {
  if (task.finalAttempt && task.finalAttempt.status !== "DRAFT"
      && (task.attemptsUsed >= task.maxAttempts || task.timeState === "CLOSED")) {
    await router.push({ path: `/learn/${courseId.value}/tasks/${task.id}/result`,
      query: task.type === "PRACTICE" ? { type: "PRACTICE" } : undefined });
    return;
  }
  if (task.timeState === "OPEN" || task.timeState === "LATE_WINDOW") {
    await router.push({ path: `/learn/${courseId.value}/tasks/${task.id}`,
      query: task.type === "PRACTICE" ? { type: "PRACTICE" } : undefined });
  }
}

function taskActionLabel(task: LearnerCourseTaskSummary | LearnerCourseTask) {
  if (task.finalAttempt && task.finalAttempt.status !== "DRAFT"
      && (task.attemptsUsed >= task.maxAttempts || task.timeState === "CLOSED")) return "查看最后结果";
  if (task.timeState === "OPEN" || task.timeState === "LATE_WINDOW") {
    return task.finalAttempt?.status === "DRAFT" ? "继续作答" : "开始作答";
  }
  if (task.submissionStatus === "MISSING") return "缺交";
  if (task.timeState === "NOT_OPEN") return "尚未开放";
  return "已关闭";
}

function canOpen(task: LearnerCourseTaskSummary | LearnerCourseTask) {
  return (task.timeState === "OPEN" || task.timeState === "LATE_WINDOW")
    || Boolean(task.finalAttempt && task.finalAttempt.status !== "DRAFT"
      && (task.attemptsUsed >= task.maxAttempts || task.timeState === "CLOSED"));
}

function setPage(nextPage: number) {
  void router.replace({ query: { ...route.query, page: String(nextPage) } });
}

const timeLabels: Record<AssessmentTimeState, string> = {
  NOT_OPEN: "尚未开放", OPEN: "开放中", LATE_WINDOW: "迟交期", CLOSED: "已关闭"
};
const submissionLabels: Record<AssessmentSubmissionStatus, string> = {
  NOT_STARTED: "未开始", IN_PROGRESS: "进行中", SUBMITTED: "已提交", MISSING: "缺交", WITHDRAWN: "已退课"
};
const gradingLabels: Record<AssessmentGradingStatus, string> = {
  NOT_REQUIRED: "无需批改", PENDING: "处理中/待批", PARTIALLY_GRADED: "部分批改", GRADED: "已批改"
};
function typeLabel(type: LearnerCourseTaskSummary["type"]) {
  return type === "PRACTICE" ? "课程练习" : type === "HOMEWORK" ? "课程作业" : "课程考试";
}
function lessonLabel(task: LearnerCourseTaskSummary) { return task.lessonId ? "课时任务" : "课程级任务"; }
function dateLabel(value: string | null) { return value ? new Date(value).toLocaleString("zh-CN") : "未设置"; }
function positive(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线；课程任务摘要仍可保留，恢复网络后可重试。";
  if (!(error instanceof GatewayRequestError)) return "课程任务暂时无法读取。";
  const labels: Record<number, string> = {
    401: "登录状态已失效，请重新登录。", 403: "当前账号没有课程学习权限。",
    404: "课程不存在、未报名或任务不可见。", 422: error.message,
    429: "请求过于频繁，请稍后重试。", 503: "学习服务暂时不可用，请稍后重试。"
  };
  return labels[error.status] ?? error.message;
}
</script>

<template>
  <main class="course-tasks-page">
    <header class="course-tasks-heading">
      <div><span class="section-kicker">COURSE TASKS</span><h2>{{ isPractice ? "课程练习" : "课程作业与考试" }}</h2>
        <p>{{ isPractice ? "只展示教师为当前课程发布且本人有权访问的练习。" : "仅展示本人应交的作业与考试；开放时间、截止裁决和可执行操作均以服务端返回为准。" }}</p></div>
      <RouterLink class="button button--quiet" :to="`/courses/${courseId}`">返回课程详情</RouterLink>
    </header>
    <p v-if="landingError" class="feedback feedback--warning" role="alert">{{ landingError }} <button @click="loadLinkedTask">重试消息目标</button></p>
    <article v-if="linkedTask" class="linked-task-notice"><span class="section-kicker">消息中的课程任务</span><h3>{{ linkedTask.title }}</h3><p>{{ timeLabels[linkedTask.timeState] }} · {{ submissionLabels[linkedTask.submissionStatus] }}</p><button class="button button--primary" :disabled="!canOpen(linkedTask)" @click="openTask(linkedTask)">{{ taskActionLabel(linkedTask) }}</button></article>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }} <button type="button" @click="load">重试</button></p>
    <div v-if="loading && !data" class="practice-loading" role="status">正在读取课程任务…</div>
    <section v-else-if="data?.items.length" class="course-task-list" aria-label="课程任务列表">
      <article v-for="task in data.items" :key="task.id" class="course-task-card">
        <header><div><span class="task-type">{{ typeLabel(task.type) }}</span><span class="task-scope">{{ lessonLabel(task) }}</span></div>
          <strong :class="`task-state task-state--${task.timeState.toLowerCase()}`">{{ timeLabels[task.timeState] }}</strong></header>
        <h3>{{ task.title }}</h3>
        <dl><div><dt>提交状态</dt><dd>{{ submissionLabels[task.submissionStatus] }}</dd></div>
          <div><dt>批改状态</dt><dd>{{ gradingLabels[task.gradingStatus] }}</dd></div>
          <div><dt>次数</dt><dd>{{ task.attemptsUsed }} / {{ task.maxAttempts }}</dd></div>
          <div><dt>开放</dt><dd>{{ dateLabel(task.opensAt) }}</dd></div>
          <div><dt>准时截止</dt><dd>{{ dateLabel(task.closesAt) }}</dd></div>
          <div><dt>最终截止</dt><dd>{{ dateLabel(task.lateUntil ?? task.closesAt) }}</dd></div>
          <div><dt>最后提交</dt><dd>{{ dateLabel(task.lastSubmittedAt) }}</dd></div>
          <div><dt>迟交</dt><dd>{{ task.lateSubmission ? "是" : "否" }}</dd></div></dl>
        <footer><span v-if="task.timeState === 'NOT_OPEN'">未开放任务只展示安全摘要，不提供题目内容。</span>
          <span v-else-if="task.submissionStatus === 'MISSING'">当前记录为缺交，不能由前端改写为已提交。</span>
          <span v-else>{{ task.submissionStatus }} · {{ task.gradingStatus }}</span>
          <button class="button button--primary" type="button" :disabled="!canOpen(task)" @click="openTask(task)">{{ taskActionLabel(task) }}</button></footer>
      </article>
    </section>
    <section v-else-if="!loading && !errorMessage" class="practice-empty"><h3>{{ isPractice ? "当前课程没有可展示的练习" : "当前没有可展示的课程任务" }}</h3>
      <p>{{ isPractice ? "教师发布本课程的练习后，你可以在这里查看。" : "课程级待开放摘要和本人应交任务由服务端按课程访问事实返回。" }}</p></section>
    <p v-if="data" class="server-time">服务端时间：{{ new Date(data.serverTime).toLocaleString('zh-CN') }}</p>
    <nav v-if="data && data.total > data.size" class="pagination" aria-label="课程任务分页">
      <button type="button" :disabled="data.page <= 1" @click="setPage(data.page - 1)">上一页</button>
      <span>第 {{ data.page }} 页</span>
      <button type="button" :disabled="data.page * data.size >= data.total" @click="setPage(data.page + 1)">下一页</button>
    </nav>
  </main>
</template>
