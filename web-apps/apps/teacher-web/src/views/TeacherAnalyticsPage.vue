<script setup lang="ts">
/** 本人课程聚合分析页。课程、课时、任务及趋势均直接展示 Learning 持久投影，不在浏览器计算班级指标。 */
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AnalyticsCoursePicker from "../components/AnalyticsCoursePicker.vue";
import LearningTrend from "../components/LearningTrend.vue";
import { learningAnalyticsApi } from "../learning-analytics-api";
import type { CourseLessonPage, CourseOverview, CourseTaskPage, LearningTrendData } from "../learning-analytics-api";
import { analyticsError, duration, percent, trendWindow } from "../learning-analytics-state";

const route = useRoute(); const router = useRouter();
const courseId = computed(() => typeof route.query.courseId === "string" ? route.query.courseId : "");
const window = computed(() => trendWindow(route.query.window));
const lessonId = computed(() => typeof route.query.lessonId === "string" ? route.query.lessonId : "");
const page = (key: string) => { const n = Number(route.query[key]); return Number.isInteger(n) && n > 0 ? n : 1; };
const lessonPage = computed(() => page("lessonPage")); const taskPage = computed(() => page("taskPage"));
const overview = ref<CourseOverview | null>(null); const lessons = ref<CourseLessonPage | null>(null);
const tasks = ref<CourseTaskPage | null>(null); const trend = ref<LearningTrendData | null>(null);
const errors = ref({ overview: "", lessons: "", tasks: "", trend: "" });
const loading = ref({ overview: false, lessons: false, tasks: false, trend: false });
let controllers: AbortController[] = [];

/** 任何筛选变化都丢弃旧响应；不同聚合区独立失败，其他区仍可使用。 */
watch(() => [courseId.value, lessonPage.value, taskPage.value, window.value, lessonId.value], load, { immediate: true });
onBeforeUnmount(() => controllers.forEach(controller => controller.abort()));
function update(changes: Record<string, string | number | undefined>) {
  void router.push({ query: { ...route.query, ...Object.fromEntries(Object.entries(changes).map(([key, value]) => [key, value === "" || value === undefined ? undefined : String(value)])) } });
}
function changeCourse(value: string) { update({ courseId: value, lessonId: undefined, lessonPage: 1, taskPage: 1 }); }
function load() {
  controllers.forEach(controller => controller.abort()); controllers = [];
  overview.value = null; lessons.value = null; tasks.value = null; trend.value = null;
  errors.value = { overview: "", lessons: "", tasks: "", trend: "" };
  if (!courseId.value) return;
  fetchSection("overview", signal => learningAnalyticsApi.courseOverview(courseId.value, signal), value => overview.value = value);
  fetchSection("lessons", signal => learningAnalyticsApi.courseLessons(courseId.value, lessonPage.value, signal), value => lessons.value = value);
  fetchSection("tasks", signal => learningAnalyticsApi.courseTasks(courseId.value, taskPage.value, signal), value => tasks.value = value);
  fetchSection("trend", signal => learningAnalyticsApi.courseTrend(courseId.value, window.value, lessonId.value || undefined, signal), value => trend.value = value);
}
/** 单块失败仅显示该块的错误和重试，避免 Course 503 或网络中断留下旧课程数据。 */
async function fetchSection<K extends "overview" | "lessons" | "tasks" | "trend">(
  key: K, call: (signal: AbortSignal) => Promise<NonNullable<{ overview: CourseOverview; lessons: CourseLessonPage; tasks: CourseTaskPage; trend: LearningTrendData }[K]>>,
  assign: (value: NonNullable<{ overview: CourseOverview; lessons: CourseLessonPage; tasks: CourseTaskPage; trend: LearningTrendData }[K]>) => void) {
  const controller = new AbortController(); controllers.push(controller); loading.value[key] = true;
  try { const value = await call(controller.signal); if (!controller.signal.aborted) assign(value); }
  catch (cause) { if (!controller.signal.aborted) errors.value[key] = analyticsError(cause); }
  finally { if (!controller.signal.aborted) loading.value[key] = false; }
}
</script>

<template>
  <main class="analytics-page">
    <header class="teacher-page-heading"><div><span class="section-kicker">COURSE ANALYTICS</span><h2>课程学情分析</h2><p>当前发布内容、ACTIVE 学生和已落库学习事实。</p></div><RouterLink class="button button--quiet" :to="{ path: '/teacher/students', query: { courseId: courseId || undefined } }">查看学生学情</RouterLink></header>
    <AnalyticsCoursePicker :course-id="courseId" @select="changeCourse" />
    <p v-if="!courseId" class="teacher-empty">选择本人课程后查看课程统计。</p>
    <template v-else>
      <section class="analytics-section" aria-labelledby="analytics-overview"><h3 id="analytics-overview">课程概览</h3><p v-if="errors.overview" class="feedback feedback--error" role="alert">{{ errors.overview }} <button type="button" @click="load">重试</button></p><p v-if="loading.overview" role="status">正在读取课程统计…</p>
        <p v-if="overview?.state === 'NO_PUBLISHED_VERSION'" class="teacher-empty">课程暂无已发布版本，当前内容统计为空。</p>
        <dl v-else-if="overview" class="analytics-metrics"><div><dt>在读学生</dt><dd>{{ overview.activeStudentCount }}</dd></div><div><dt>当前课时</dt><dd>{{ overview.totalLessonCount }}</dd></div><div><dt>完成课程学生</dt><dd>{{ overview.completedStudentCount }}</dd></div><div><dt>累计观看</dt><dd>{{ duration(overview.watchedSeconds) }}</dd></div><div><dt>实际观看者</dt><dd>{{ overview.actualViewerCount }}</dd></div><div><dt>每名在读学生平均观看</dt><dd>{{ duration(overview.averageWatchedPerActiveStudent) }}</dd></div><div><dt>每名观看者平均观看</dt><dd>{{ duration(overview.averageWatchedPerViewer) }}</dd></div></dl>
      </section>
      <section class="analytics-section" aria-labelledby="analytics-lessons"><h3 id="analytics-lessons">课时统计</h3><p v-if="errors.lessons" class="feedback feedback--error" role="alert">{{ errors.lessons }} <button type="button" @click="load">重试</button></p><p v-if="loading.lessons" role="status">正在读取课时统计…</p><p v-else-if="lessons && !lessons.items.length" class="teacher-empty">当前发布版本没有课时统计。</p>
        <div v-if="lessons?.items.length" class="analytics-table-wrap"><table class="analytics-table"><caption>当前发布课时聚合</caption><thead><tr><th>课时 ID</th><th>类型</th><th>开始 / 完成</th><th>完成率</th><th>观看</th><th>观看人数</th><th>在读平均 / 观看者平均</th><th>趋势</th></tr></thead><tbody><tr v-for="lesson in lessons.items" :key="lesson.lessonId"><th scope="row">{{ lesson.lessonId }}</th><td>{{ lesson.lessonType }}</td><td>{{ lesson.startedCount }} / {{ lesson.completedCount }}</td><td>{{ percent(lesson.completionRate) }}</td><td>{{ duration(lesson.watchedSeconds) }}</td><td>{{ lesson.actualViewerCount ?? '不适用' }}</td><td>{{ duration(lesson.averageWatchedPerActiveStudent) }} / {{ duration(lesson.averageWatchedPerViewer) }}</td><td><button type="button" :aria-pressed="lessonId === lesson.lessonId" @click="update({ lessonId: lessonId === lesson.lessonId ? undefined : lesson.lessonId })">{{ lessonId === lesson.lessonId ? '全课趋势' : '仅此课时' }}</button></td></tr></tbody></table></div>
        <nav v-if="lessons && lessons.total > lessons.size" class="pagination" aria-label="课时统计分页"><button type="button" :disabled="lessons.page <= 1" @click="update({ lessonPage: lessons.page - 1 })">上一页</button><span>第 {{ lessons.page }} 页</span><button type="button" :disabled="lessons.page * lessons.size >= lessons.total" @click="update({ lessonPage: lessons.page + 1 })">下一页</button></nav>
      </section>
      <section class="analytics-section" aria-labelledby="analytics-tasks"><h3 id="analytics-tasks">课程任务统计</h3><p v-if="errors.tasks" class="feedback feedback--error" role="alert">{{ errors.tasks }} <button type="button" @click="load">重试</button></p><p v-if="loading.tasks" role="status">正在读取任务统计…</p><p v-else-if="tasks && !tasks.items.length" class="teacher-empty">当前课程暂无任务统计。</p>
        <div v-if="tasks?.items.length" class="analytics-task-grid"><details v-for="task in tasks.items" :key="task.assessmentId" :open="task.status !== 'ARCHIVED'"><summary><strong>{{ task.title }}</strong><span>{{ task.type }} · {{ task.status === 'ARCHIVED' ? '已归档' : '已发布' }}</span></summary><dl class="analytics-metrics"><div><dt>应交 / 已交</dt><dd>{{ task.expectedCount }} / {{ task.submittedCount }}</dd></div><div><dt>缺交 / 迟交 / 待批</dt><dd>{{ task.missingCount }} / {{ task.lateCount }} / {{ task.pendingCount }}</dd></div><div><dt>平均分</dt><dd>{{ task.resultsReady ? percent(task.averageScorePercent) : '班级成绩暂不可见' }}</dd></div><div><dt>最高 / 最低</dt><dd>{{ task.resultsReady ? `${percent(task.highestScorePercent)} / ${percent(task.lowestScorePercent)}` : '暂不可见' }}</dd></div></dl><p v-if="task.resultsReady && task.distribution" class="analytics-distribution"><span v-for="(count, range) in task.distribution" :key="range">{{ range }}：{{ count }} 人</span></p><p v-else class="analytics-note">班级统计将在服务端门禁满足后显示。</p></details></div>
        <nav v-if="tasks && tasks.total > tasks.size" class="pagination" aria-label="任务统计分页"><button type="button" :disabled="tasks.page <= 1" @click="update({ taskPage: tasks.page - 1 })">上一页</button><span>第 {{ tasks.page }} 页</span><button type="button" :disabled="tasks.page * tasks.size >= tasks.total" @click="update({ taskPage: tasks.page + 1 })">下一页</button></nav>
      </section>
      <section class="analytics-section" aria-labelledby="analytics-trend"><div class="analytics-trend-controls"><h3 id="analytics-trend">{{ lessonId ? '所选课时' : '全课程' }}活动趋势</h3><label>时间范围<select :value="window" @change="update({ window: ($event.target as HTMLSelectElement).value })"><option value="LAST_7_DAYS">最近 7 天</option><option value="LAST_30_DAYS">最近 30 天</option><option value="ALL">全部</option></select></label></div><p v-if="errors.trend" class="feedback feedback--error" role="alert">{{ errors.trend }} <button type="button" @click="load">重试</button></p><p v-if="loading.trend" role="status">正在读取趋势…</p><LearningTrend v-else-if="trend" :trend="trend" /></section>
    </template>
  </main>
</template>
