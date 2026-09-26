<script setup lang="ts">
/** 本人课程 ACTIVE 学生学情页。URL 恢复筛选与单生面板；各明细只在选中时请求，所有统计和成绩可见性由 Learning 返回。 */
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AnalyticsCoursePicker from "../components/AnalyticsCoursePicker.vue";
import LearningTrend from "../components/LearningTrend.vue";
import { learningAnalyticsApi } from "../learning-analytics-api";
import type { LearningTrendData, StudentLessonPage, StudentOverview, StudentPage, StudentTaskPage } from "../learning-analytics-api";
import { analyticsError, dateTime, duration, percent, studentState, trendWindow } from "../learning-analytics-state";

const route = useRoute(); const router = useRouter();
const state = computed(() => studentState(route.query));
const window = computed(() => trendWindow(route.query.window));
const list = ref<StudentPage | null>(null); const detail = ref<StudentOverview | StudentLessonPage | StudentTaskPage | null>(null);
const trend = ref<LearningTrendData | null>(null);
const listLoading = ref(false); const detailLoading = ref(false); const trendLoading = ref(false);
const listError = ref(""); const detailError = ref(""); const trendError = ref("");
let listController: AbortController | null = null; let detailController: AbortController | null = null; let trendController: AbortController | null = null;
const currentStudent = computed(() => list.value?.items.find(item => item.learnerId === state.value.learnerId));

/** 路由参数改变时中止旧请求；服务端 owner 和 ACTIVE 校验失败时不展示上一人的详情。 */
watch([() => state.value.courseId, () => state.value.page, () => state.value.sort, () => state.value.direction,
  () => state.value.keyword, () => state.value.minProgressPercent, () => state.value.maxProgressPercent,
  () => state.value.missingOnly, () => state.value.pendingOnly], loadList, { immediate: true });
watch([() => state.value.courseId, () => state.value.learnerId, () => state.value.panel,
  () => state.value.detailPage], loadDetail, { immediate: true });
watch([() => state.value.courseId, () => state.value.learnerId, () => state.value.panel,
  () => window.value], loadTrend, { immediate: true });
onBeforeUnmount(() => { listController?.abort(); detailController?.abort(); trendController?.abort(); });

async function loadList() {
  listController?.abort(); list.value = null; listError.value = "";
  if (!state.value.courseId) return;
  const active = new AbortController(); listController = active; listLoading.value = true;
  try {
    const s = state.value;
    list.value = await learningAnalyticsApi.students(s.courseId, {
      page: s.page, size: 20, sort: s.sort, direction: s.direction, keyword: s.keyword || undefined,
      minProgressPercent: s.minProgressPercent, maxProgressPercent: s.maxProgressPercent,
      missingOnly: s.missingOnly || undefined, pendingOnly: s.pendingOnly || undefined
    }, active.signal);
  } catch (cause) { if (!active.signal.aborted) listError.value = analyticsError(cause); }
  finally { if (!active.signal.aborted) listLoading.value = false; }
}
async function loadDetail() {
  detailController?.abort(); detail.value = null; detailError.value = "";
  const s = state.value; if (!s.courseId || !s.learnerId) return;
  const active = new AbortController(); detailController = active; detailLoading.value = true;
  try {
    detail.value = s.panel === "overview" ? await learningAnalyticsApi.studentOverview(s.courseId, s.learnerId, active.signal)
      : s.panel === "lessons" ? await learningAnalyticsApi.studentLessons(s.courseId, s.learnerId, s.detailPage, active.signal)
        : await learningAnalyticsApi.studentTasks(s.courseId, s.learnerId, s.detailPage, active.signal);
  } catch (cause) { if (!active.signal.aborted) detailError.value = analyticsError(cause); }
  finally { if (!active.signal.aborted) detailLoading.value = false; }
}
async function loadTrend() {
  trendController?.abort(); trend.value = null; trendError.value = "";
  const s = state.value; if (!s.courseId || !s.learnerId || s.panel !== "overview") return;
  const active = new AbortController(); trendController = active; trendLoading.value = true;
  try { trend.value = await learningAnalyticsApi.studentTrend(s.courseId, s.learnerId, window.value, active.signal); }
  catch (cause) { if (!active.signal.aborted) trendError.value = analyticsError(cause); }
  finally { if (!active.signal.aborted) trendLoading.value = false; }
}
function update(changes: Record<string, string | number | boolean | undefined>) {
  const query = { ...route.query, ...Object.fromEntries(Object.entries(changes).map(([key, value]) => [key, value === undefined || value === "" || value === false ? undefined : String(value)])) };
  void router.push({ query });
}
function changeCourse(courseId: string) { update({ courseId, learnerId: undefined, page: 1, detailPage: 1, panel: "overview" }); }
function openStudent(learnerId: string) { update({ learnerId, panel: "overview", detailPage: 1 }); }
function changePanel(panel: string) { update({ panel, detailPage: 1 }); }
function isOverview(value: typeof detail.value): value is StudentOverview { return Boolean(value && "student" in value); }
function isLessonPage(value: typeof detail.value): value is StudentLessonPage { return Boolean(value && "items" in value && state.value.panel === "lessons"); }
function isTaskPage(value: typeof detail.value): value is StudentTaskPage { return Boolean(value && "items" in value && state.value.panel === "tasks"); }
function score(value: number | null | undefined) { return value == null ? "成绩暂不可用" : value.toFixed(1); }
</script>

<template>
  <main class="analytics-page">
    <header class="teacher-page-heading"><div><span class="section-kicker">STUDENT LEARNING</span><h2>学生学情</h2><p>只展示本人课程的在读学生与当前发布内容，统计以已落库事实为准。</p></div>
      <RouterLink class="button button--quiet" :to="{ path: '/teacher/analytics', query: { courseId: state.courseId || undefined } }">查看课程分析</RouterLink></header>
    <AnalyticsCoursePicker :course-id="state.courseId" @select="changeCourse" />
    <p v-if="!state.courseId" class="teacher-empty">选择本人课程后查看在读学生。</p>
    <template v-else>
      <form class="analytics-filters" @submit.prevent><label>昵称或课程学生编号<input :value="state.keyword" maxlength="120" placeholder="筛选在读学生" @change="update({ keyword: ($event.target as HTMLInputElement).value.trim(), page: 1 })"></label>
        <label>最小进度 %<input type="number" min="0" max="100" :value="state.minProgressPercent ?? ''" @change="update({ minProgressPercent: ($event.target as HTMLInputElement).value || undefined, page: 1 })"></label>
        <label>最大进度 %<input type="number" min="0" max="100" :value="state.maxProgressPercent ?? ''" @change="update({ maxProgressPercent: ($event.target as HTMLInputElement).value || undefined, page: 1 })"></label>
        <label>排序<select :value="state.sort" @change="update({ sort: ($event.target as HTMLSelectElement).value, page: 1 })"><option value="STUDENT_NUMBER">课程学生编号</option><option value="LAST_ACTIVITY">最后活动</option><option value="PROGRESS">完成进度</option><option value="WATCHED_SECONDS">观看时长</option><option value="HOMEWORK_AVERAGE">作业平均</option><option value="EXAM_AVERAGE">考试平均</option></select></label>
        <label>方向<select :value="state.direction" @change="update({ direction: ($event.target as HTMLSelectElement).value, page: 1 })"><option value="asc">升序</option><option value="desc">降序</option></select></label>
        <label class="analytics-check"><input type="checkbox" :checked="state.missingOnly" @change="update({ missingOnly: ($event.target as HTMLInputElement).checked, page: 1 })">只看缺交</label>
        <label class="analytics-check"><input type="checkbox" :checked="state.pendingOnly" @change="update({ pendingOnly: ($event.target as HTMLInputElement).checked, page: 1 })">只看待批</label>
      </form>
      <p v-if="listError" class="feedback feedback--error" role="alert">{{ listError }} <button type="button" @click="loadList">重试</button></p>
      <p v-if="listLoading" class="teacher-loading" role="status">正在读取在读学生…</p>
      <template v-else-if="list">
        <p v-if="list.state === 'NO_PUBLISHED_VERSION'" class="teacher-empty">课程暂无已发布版本，暂不能查看当前内容学情。</p>
        <p v-else-if="!list.items.length" class="teacher-empty">当前筛选没有在读学生。</p>
        <p v-if="list.nicknameDependencyFailed" class="feedback feedback--warning" role="status">昵称服务暂时不可用；课程学生编号和学情数据仍可查看。</p>
        <div v-if="list.items.length" class="analytics-table-wrap"><table class="analytics-table"><caption>在读学生，共 {{ list.total }} 人</caption><thead><tr><th scope="col">编号与昵称</th><th scope="col">完成度</th><th scope="col">课时</th><th scope="col">观看</th><th scope="col">最后活动</th><th scope="col">作业平均</th><th scope="col">考试平均</th><th scope="col">缺交 / 待批</th></tr></thead>
          <tbody><tr v-for="student in list.items" :key="student.learnerId"><th scope="row"><button type="button" :aria-current="student.learnerId === state.learnerId ? 'true' : undefined" @click="openStudent(student.learnerId)">{{ student.courseStudentNumber }} · {{ student.nicknameAvailable && student.nickname ? student.nickname : '昵称暂不可用' }}</button></th><td>{{ percent(student.progressPercent) }}</td><td>{{ student.completedLessons }} / {{ student.totalLessons }}</td><td>{{ duration(student.watchedSeconds) }}</td><td>{{ dateTime(student.lastActivityAt) }}</td><td>{{ score(student.homeworkAverage) }}</td><td>{{ score(student.examAverage) }}</td><td>{{ student.missingCount }} / {{ student.pendingCount }}</td></tr></tbody></table></div>
        <nav v-if="list.total > list.size" class="pagination" aria-label="学生分页"><button type="button" :disabled="list.page <= 1" @click="update({ page: list.page - 1 })">上一页</button><span>第 {{ list.page }} 页</span><button type="button" :disabled="list.page * list.size >= list.total" @click="update({ page: list.page + 1 })">下一页</button></nav>
      </template>
      <section v-if="state.learnerId" class="analytics-detail" aria-label="单个学生学情"><header><div><h3>{{ currentStudent?.courseStudentNumber ?? '所选学生' }} · {{ currentStudent?.nicknameAvailable && currentStudent.nickname ? currentStudent.nickname : '昵称暂不可用' }}</h3><p>当前课程、当前发布内容与服务端可见成绩</p></div><button type="button" @click="update({ learnerId: undefined, panel: undefined, detailPage: undefined })">关闭详情</button></header>
        <nav class="analytics-tabs" aria-label="学生详情面板"><button v-for="panel in ['overview','lessons','tasks']" :key="panel" type="button" :aria-current="state.panel === panel ? 'page' : undefined" @click="changePanel(panel)">{{ { overview: '概览', lessons: '课时', tasks: '任务' }[panel] }}</button></nav>
        <p v-if="detailError" class="feedback feedback--error" role="alert">{{ detailError }} <button type="button" @click="loadDetail">重试</button></p>
        <p v-if="detailLoading" class="teacher-loading" role="status">正在读取学生详情…</p>
        <template v-else-if="isOverview(detail)"><p v-if="detail.nicknameDependencyFailed" class="feedback feedback--warning">昵称暂不可用；其余统计仍可查看。</p><dl class="analytics-metrics"><div><dt>已完成课时</dt><dd>{{ detail.student.completedLessons }} / {{ detail.student.totalLessons }}</dd></div><div><dt>完成进度</dt><dd>{{ percent(detail.student.progressPercent) }}</dd></div><div><dt>观看时长</dt><dd>{{ duration(detail.student.watchedSeconds) }}</dd></div><div><dt>课程练习提交</dt><dd>{{ detail.practiceSubmissionCount }}</dd></div><div><dt>缺交 / 待批</dt><dd>{{ detail.student.missingCount }} / {{ detail.student.pendingCount }}</dd></div><div><dt>最后活动</dt><dd>{{ dateTime(detail.student.lastActivityAt) }}</dd></div></dl>
          <div class="analytics-trend-controls"><h4>个人活动趋势</h4><label>时间范围<select :value="window" @change="update({ window: ($event.target as HTMLSelectElement).value })"><option value="LAST_7_DAYS">最近 7 天</option><option value="LAST_30_DAYS">最近 30 天</option><option value="ALL">全部</option></select></label></div><p v-if="trendError" class="feedback feedback--error" role="alert">{{ trendError }} <button type="button" @click="loadTrend">重试</button></p><p v-if="trendLoading" role="status">正在读取趋势…</p><LearningTrend v-else-if="trend" :trend="trend" />
        </template>
        <template v-else-if="isLessonPage(detail)"><p v-if="!detail.items.length" class="teacher-empty">当前发布版本暂无课时记录。</p><div v-else class="analytics-table-wrap"><table class="analytics-table"><caption>当前课时进度</caption><thead><tr><th>课时 ID</th><th>类型</th><th>开始</th><th>完成</th><th>观看</th><th>最后活动</th></tr></thead><tbody><tr v-for="lesson in detail.items" :key="lesson.lessonId"><th scope="row">{{ lesson.lessonId }}</th><td>{{ lesson.lessonType }}</td><td>{{ lesson.started ? '是' : '否' }}</td><td>{{ lesson.completed ? '是' : '否' }}</td><td>{{ duration(lesson.watchedSeconds) }}</td><td>{{ dateTime(lesson.lastActivityAt) }}</td></tr></tbody></table></div></template>
        <template v-else-if="isTaskPage(detail)"><p v-if="!detail.items.length" class="teacher-empty">当前课程暂无任务记录。</p><div v-else class="analytics-table-wrap"><table class="analytics-table"><caption>课程任务与安全成绩</caption><thead><tr><th>任务</th><th>类型</th><th>提交</th><th>迟交</th><th>批改</th><th>个人分数</th><th>排名</th></tr></thead><tbody><tr v-for="task in detail.items" :key="task.assessmentId"><th scope="row">{{ task.title }}<small v-if="task.status === 'ARCHIVED'">（已归档）</small></th><td>{{ task.type }}</td><td>{{ task.submitted ? '已提交' : '未提交' }}</td><td>{{ task.late ? '是' : '否' }}</td><td>{{ task.gradingPending ? '待批' : '—' }}</td><td>{{ task.scorePercent == null ? '尚不可见' : `${task.rawScore} / ${task.totalPoints}（${percent(task.scorePercent)}）` }}</td><td>{{ task.resultsReady && task.rank != null ? `${task.rank} / ${task.rankedStudentCount} · 领先 ${percent(task.rankingPercent)}` : '班级排名暂不可见' }}</td></tr></tbody></table></div></template>
        <nav v-if="detail && 'total' in detail && detail.total > detail.size" class="pagination" aria-label="学生明细分页"><button type="button" :disabled="detail.page <= 1" @click="update({ detailPage: detail.page - 1 })">上一页</button><span>第 {{ detail.page }} 页</span><button type="button" :disabled="detail.page * detail.size >= detail.total" @click="update({ detailPage: detail.page + 1 })">下一页</button></nav>
      </section>
    </template>
  </main>
</template>
