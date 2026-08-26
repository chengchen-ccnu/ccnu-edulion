<script setup lang="ts">
/**
 * “我的学习”聚合页：按 learning:access 读取本人 ACTIVE 报名，再并行组合 Course 公开详情与 Learning 进度。
 * 单门课程失败只影响对应卡片，已成功报名仍保留展示；翻页、重试或离页会中止旧请求。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { Enrollment, EnrollmentPage } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentCourseApi } from "../course-api";
import { isCompleted, latestLessonProgress, resumeLessonId } from "../learning-model";
import type { MyLearningItem } from "../learning-model";
import { isAuthenticationRequired, studentLearningApi } from "../learning-api";

const route = useRoute();
const router = useRouter();
const pageSize = 12;
const enrollmentPage = ref<EnrollmentPage | null>(null);
const items = ref<MyLearningItem[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const selectedView = ref<"active" | "completed">("active");
let loadAbort: AbortController | null = null;
let generation = 0;

const currentPage = computed(() => enrollmentPage.value?.page ?? pageFromRoute());
const pageCount = computed(() => Math.max(1, Math.ceil((enrollmentPage.value?.total ?? 0) / pageSize)));
const visibleItems = computed(() => items.value.filter(item => selectedView.value === "completed"
  ? isCompleted(item) : !isCompleted(item)));
const partialFailureCount = computed(() => items.value.filter(item => item.courseError || item.progressError).length);

watch(() => route.fullPath, loadEnrollments, { immediate: true });
onBeforeUnmount(cancelLoad);

async function loadEnrollments() {
  cancelLoad();
  const currentGeneration = ++generation;
  const controller = new AbortController();
  loadAbort = controller;
  loading.value = true;
  errorMessage.value = "";
  items.value = [];
  try {
    const page = await studentLearningApi.enrollments({ page: pageFromRoute(), size: pageSize, status: "ACTIVE" },
      controller.signal);
    if (controller.signal.aborted || currentGeneration !== generation) return;
    enrollmentPage.value = page;
    items.value = page.items.map(emptyLearningItem);
    await Promise.all(page.items.map(enrollment => loadEnrollmentFacts(enrollment, currentGeneration, controller)));
  } catch (error) {
    if (!controller.signal.aborted && currentGeneration === generation) {
      errorMessage.value = learningErrorMessage(error, "我的报名暂时无法读取，请稍后重试。");
    }
  } finally {
    if (currentGeneration === generation) loading.value = false;
  }
}

/** Course 与 Progress 独立读取，任一失败都不抹掉已成功的另一侧事实。 */
async function loadEnrollmentFacts(enrollment: Enrollment, currentGeneration: number, controller: AbortController) {
  const [courseResult, progressResult] = await Promise.allSettled([
    studentCourseApi.detail(enrollment.courseId, controller.signal),
    studentLearningApi.progress(enrollment.courseId, controller.signal)
  ]);
  if (controller.signal.aborted || currentGeneration !== generation) return;
  const index = items.value.findIndex(item => item.enrollment.id === enrollment.id);
  if (index < 0) return;
  const previous = items.value[index]!;
  items.value[index] = {
    ...previous,
    course: courseResult.status === "fulfilled" ? courseResult.value : null,
    progress: progressResult.status === "fulfilled" ? progressResult.value : null,
    courseError: courseResult.status === "rejected"
      ? learningErrorMessage(courseResult.reason, "课程信息暂时不可用。") : "",
    progressError: progressResult.status === "rejected"
      ? learningErrorMessage(progressResult.reason, "进度暂时不可用。") : ""
  };
}

async function retryItem(item: MyLearningItem) {
  if (!loadAbort || loadAbort.signal.aborted) return;
  await loadEnrollmentFacts(item.enrollment, generation, loadAbort);
}

async function changePage(page: number) {
  if (page < 1 || page > pageCount.value || page === currentPage.value) return;
  await router.push({ name: "my-learning", query: page > 1 ? { page: String(page) } : {} });
}

function emptyLearningItem(enrollment: Enrollment): MyLearningItem {
  return { enrollment, course: null, progress: null, courseError: "", progressError: "" };
}

function continueHref(item: MyLearningItem): string | null {
  if (!item.course) return null;
  const lessonId = resumeLessonId(item.course, item.progress, true);
  return lessonId ? `/learn/${item.course.id}/${lessonId}` : null;
}

function latestPosition(item: MyLearningItem): string {
  const latest = latestLessonProgress(item.progress);
  return latest ? `最近学习到 ${formatSeconds(latest.positionSeconds)}` : "尚未产生学习进度";
}

function formatSeconds(value: number): string {
  const minutes = Math.floor(value / 60);
  const seconds = Math.floor(value % 60);
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function learningErrorMessage(error: unknown, fallback: string): string {
  if (isAuthenticationRequired(error)) return "登录状态已失效，请重新登录。";
  if (error instanceof TypeError) return "当前处于离线状态，请恢复网络后重试。";
  if (!(error instanceof GatewayRequestError)) return fallback;
  if (error.status === 401) return "登录状态已失效，请重新登录。";
  if (error.status === 403) return "当前账号没有学习空间访问能力。";
  if (error.status === 404) return "课程或学习记录不存在、已下架或不可见。";
  if (error.status === 409) return "学习记录状态已变化，请刷新后重试。";
  if (error.status === 422) return "请求参数无效，请检查当前地址后重试。";
  if (error.status === 429) return "请求过于频繁，有限重试仍未成功，请稍后手动重试。";
  if (error.status === 503) return "课程或学习服务暂时不可用，请稍后重试。";
  return error.message || fallback;
}

function pageFromRoute(): number {
  const value = Number(typeof route.query.page === "string" ? route.query.page : "1");
  return Number.isInteger(value) && value > 0 ? value : 1;
}

function cancelLoad() {
  loadAbort?.abort();
  loadAbort = null;
}
</script>

<template>
  <section class="learning-dashboard-hero">
    <div>
      <span class="section-kicker">MY LEARNING</span>
      <h2>我的学习</h2>
      <p>这里汇总你的真实报名、课程进度和最近学习位置。</p>
    </div>
    <RouterLink class="button button--quiet" to="/courses">发现更多课程</RouterLink>
  </section>

  <div class="learning-filter" role="group" aria-label="学习状态筛选">
    <button type="button" :aria-pressed="selectedView === 'active'"
      @click="selectedView = 'active'">学习中</button>
    <button type="button" :aria-pressed="selectedView === 'completed'"
      @click="selectedView = 'completed'">已完成</button>
  </div>

  <p v-if="errorMessage" class="feedback feedback--error" role="alert">
    {{ errorMessage }}
    <button type="button" @click="loadEnrollments">重新加载</button>
  </p>
  <p v-else-if="partialFailureCount" class="feedback feedback--warning" role="status">
    {{ partialFailureCount }} 门课程的详情或进度读取失败，其他学习记录仍可使用。
  </p>

  <div v-if="loading" class="learning-card-grid" aria-label="正在加载我的学习">
    <span v-for="index in 3" :key="index" class="learning-card-skeleton" />
  </div>
  <div v-else-if="visibleItems.length" class="learning-card-grid">
    <article v-for="item in visibleItems" :key="item.enrollment.id" class="learning-record-card">
      <div v-if="item.course" class="learning-record-card__cover">
        <img v-if="item.course.coverUrl" :src="item.course.coverUrl" :alt="`${item.course.title}封面`">
        <span v-else aria-hidden="true">▧</span>
      </div>
      <div class="learning-record-card__body">
        <span class="learning-record-card__status">{{ isCompleted(item) ? "已完成" : "学习中" }}</span>
        <h3>{{ item.course?.title ?? `课程 ${item.enrollment.courseId}` }}</h3>
        <p v-if="item.course">{{ item.course.summary || "课程暂未填写摘要。" }}</p>
        <p v-else class="learning-record-card__error">{{ item.courseError || "正在读取课程信息…" }}</p>

        <div class="learning-record-card__meta">
          <span>课程完成度</span>
          <strong>{{ item.progress?.completionPercent ?? 0 }}%</strong>
        </div>
        <div class="learning-progress-bar" :aria-label="`课程完成度 ${item.progress?.completionPercent ?? 0}%`">
          <span :style="{ width: `${item.progress?.completionPercent ?? 0}%` }" />
        </div>
        <div class="learning-record-card__detail">
          <span v-if="item.progress">{{ item.progress.completedLessonCount }} / {{ item.progress.lessonCount }} 课时</span>
          <span v-else>{{ item.progressError || "正在读取进度…" }}</span>
          <small>{{ latestPosition(item) }}</small>
        </div>

        <div class="learning-record-card__actions">
          <RouterLink v-if="continueHref(item)" class="button button--primary" :to="continueHref(item)!">
            {{ item.progress?.lessons.length ? "继续学习" : "开始学习" }}
          </RouterLink>
          <RouterLink v-if="item.course" class="button button--quiet" :to="`/courses/${item.course.id}`">课程详情</RouterLink>
          <button v-if="item.courseError || item.progressError" class="button button--quiet" type="button"
            @click="retryItem(item)">重试失败区域</button>
        </div>
      </div>
    </article>
  </div>
  <div v-else-if="enrollmentPage" class="empty-state">
    <span>{{ selectedView === "completed" ? "暂无已完成课程" : "暂无学习中的课程" }}</span>
    <h3>{{ selectedView === "completed" ? "完成一门课程后会出现在这里" : "从课程中心开始第一次学习" }}</h3>
    <p>本页只展示真实报名和进度，不会用推荐或收藏假数据填充。</p>
    <RouterLink class="button button--primary" to="/courses">前往课程中心</RouterLink>
  </div>

  <nav v-if="enrollmentPage && enrollmentPage.total > pageSize" class="pagination" aria-label="我的学习分页">
    <button type="button" :disabled="currentPage <= 1" @click="changePage(currentPage - 1)">上一页</button>
    <span>第 {{ currentPage }} / {{ pageCount }} 页</span>
    <button type="button" :disabled="currentPage >= pageCount" @click="changePage(currentPage + 1)">下一页</button>
  </nav>
</template>
