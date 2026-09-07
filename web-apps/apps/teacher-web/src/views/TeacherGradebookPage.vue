<script setup lang="ts">
/**
 * 教师成绩册只负责 HOMEWORK/EXAM 矩阵、服务端汇总和同步 CSV 下载，不复制测评编辑器或批改器。
 * URL 是筛选、分页和排序的唯一恢复来源；读取失败保留已有矩阵，CSV 请求离页/取消会中止并撤销临时 Blob URL。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { Gradebook, GradebookCell, GradebookRow } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { teacherGradebookApi } from "../gradebook-api";
import { gradebookRequestQuery, parseGradebookQuery, toGradebookQuery } from "../gradebook-state";

const route = useRoute();
const router = useRouter();
const data = ref<Gradebook | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const downloadStatus = ref<"idle" | "downloading" | "success" | "cancelled" | "error">("idle");
const downloadMessage = ref("");
let controller: AbortController | null = null;
let exportController: AbortController | null = null;
let objectUrl: string | null = null;

const state = computed(() => parseGradebookQuery(route.query as Record<string, unknown>));
const ready = computed(() => data.value?.aggregateStatus === "READY");
const displaySort = computed(() => !ready.value && state.value.sort === "SCORE" ? "STUDENT_NUMBER" : state.value.sort);

watch(() => route.query, restoreFromUrl, { immediate: true });
onBeforeUnmount(() => { controller?.abort(); cancelExport(); });

/** 规范化 URL 后恢复筛选；课程清空时中止旧读取，避免迟到响应重新显示上一课程。 */
async function restoreFromUrl() {
  const canonical = toGradebookQuery(state.value);
  if (!sameQuery(route.query, canonical)) {
    await router.replace({ query: canonical });
    return;
  }
  if (!state.value.courseId) {
    controller?.abort();
    loading.value = false;
    data.value = null;
    errorMessage.value = "请在 URL 中提供课程 ID，成绩册只读取本人拥有课程。";
    return;
  }
  await load();
}

/** 读取当前筛选的服务端事实；首次 SCORE 深链先确认 READY，再按 URL 成绩顺序重读。 */
async function load() {
  controller?.abort();
  const active = new AbortController();
  controller = active;
  loading.value = true;
  errorMessage.value = "";
  try {
    const snapshot = state.value;
    const requestedSort = displaySort.value;
    let value = await teacherGradebookApi.get(gradebookRequestQuery({
      ...snapshot, sort: requestedSort
    }), active.signal);
    if (active.signal.aborted) return;
    // PENDING 不能按成绩排序；确认当前范围 READY 后才恢复初始 URL 的 SCORE，避免页面与实际顺序不一致。
    if (value.aggregateStatus === "READY" && snapshot.sort === "SCORE" && requestedSort !== "SCORE") {
      value = await teacherGradebookApi.get(gradebookRequestQuery(snapshot), active.signal);
      if (active.signal.aborted) return;
    }
    data.value = value;
    if (value.aggregateStatus === "PENDING" && state.value.sort === "SCORE") {
      await router.replace({ query: toGradebookQuery({ ...state.value, sort: "STUDENT_NUMBER" }) });
    }
  } catch (error) {
    if (!active.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!active.signal.aborted) loading.value = false;
  }
}

/** 导出当前 URL 筛选；每次操作使用独立请求，取消后旧响应不得覆盖新下载状态或生成文件。 */
async function downloadCsv() {
  if (downloadStatus.value === "downloading") return;
  const active = new AbortController();
  exportController = active;
  downloadStatus.value = "downloading";
  downloadMessage.value = "服务器正在生成当前筛选的 CSV；大数据量下载可能需要几分钟。";
  try {
    const stateSnapshot = state.value;
    const result = await teacherGradebookApi.exportCsv({
      courseId: stateSnapshot.courseId, clientRequestId: crypto.randomUUID(), assessmentId: stateSnapshot.assessmentId,
      type: stateSnapshot.type, submissionStatus: stateSnapshot.submissionStatus, gradingStatus: stateSnapshot.gradingStatus,
      late: stateSnapshot.late, keyword: stateSnapshot.keyword
    }, active.signal);
    if (active.signal.aborted) return;
    objectUrl = URL.createObjectURL(result.blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl; anchor.download = result.filename; anchor.click();
    downloadStatus.value = "success";
    downloadMessage.value = "CSV 已开始下载。";
  } catch (error) {
    if (exportController !== active) return;
    if (isAbort(error) || active.signal.aborted) {
      downloadStatus.value = "cancelled";
      downloadMessage.value = "CSV 下载已取消。";
    } else {
      downloadStatus.value = "error";
      downloadMessage.value = exportMessageFor(error);
    }
  } finally {
    if (exportController === active) {
      revokeObjectUrl();
      exportController = null;
    }
  }
}

/** 用户取消或离页立即结束本地下载状态并中止请求，不依赖网络何时兑现 AbortError。 */
function cancelExport() {
  exportController?.abort();
  exportController = null;
  revokeObjectUrl();
  if (downloadStatus.value === "downloading") {
    downloadStatus.value = "cancelled";
    downloadMessage.value = "CSV 下载已取消。";
  }
}

async function openRecord(row: GradebookRow, assessmentId: string) {
  const cell = row.assessments[assessmentId];
  if (!cell?.attemptId) return;
  await router.push({ path: "/teacher/assessments", query: {
    courseId: state.value.courseId, assessmentId, attemptId: cell.attemptId, panel: "grading"
  } });
}

function cellFor(row: GradebookRow, assessmentId: string): GradebookCell | null {
  return row.assessments[assessmentId] ?? null;
}
function scoreFor(cell: GradebookCell | null) { return ready.value && cell?.scorePercent !== null && cell?.scorePercent !== undefined ? `${cell.scorePercent}` : "—"; }
function statusLabel(value: string | undefined) {
  return ({ NOT_STARTED: "未开始", IN_PROGRESS: "进行中", SUBMITTED: "已提交", MISSING: "缺交", WITHDRAWN: "已退课",
    NOT_REQUIRED: "无需批改", PENDING: "待批", PARTIALLY_GRADED: "部分批改", GRADED: "已批改" } as Record<string, string>)[value ?? ""] ?? value ?? "—";
}
function percent(value: number | null) { return value === null ? "—" : `${value.toFixed(1)}%`; }
function score(value: number | null) { return value === null ? "—" : value.toFixed(1); }
function setQuery(values: Record<string, string | number | undefined>) {
  const next = { ...state.value, ...values };
  void router.replace({ query: toGradebookQuery({ ...next, page: values.page === undefined ? 1 : Number(next.page) }) });
}
function selectedLate() { return state.value.late === undefined ? "" : String(state.value.late); }
function normalizeLate(value: string) { return value === "true" || value === "false" ? value : undefined; }
function revokeObjectUrl() { if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; } }
function sameQuery(actual: Record<string, unknown>, expected: Record<string, string>) {
  const keys = new Set([...Object.keys(actual), ...Object.keys(expected)]);
  return [...keys].every(key => String(actual[key] ?? "") === String(expected[key] ?? ""));
}
function isAbort(error: unknown) { return error instanceof DOMException && error.name === "AbortError"; }
function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线；已有成绩册仍保留，恢复网络后可重试。";
  if (!(error instanceof GatewayRequestError)) return "成绩册暂时无法读取。";
  const labels: Record<number, string> = { 401: "登录状态已失效。", 403: "没有教学权限或课程所有权。", 404: "课程或成绩册不存在。",
    409: "成绩册状态已变化，请重新载入。", 422: error.message, 429: "请求过于频繁，请稍后重试。", 503: "学习或 IAM 依赖暂时不可用。" };
  return labels[error.status] ?? error.message;
}
function exportMessageFor(error: unknown) {
  if (error instanceof TypeError) return "网络中断，CSV 未下载完成。";
  if (!(error instanceof GatewayRequestError)) return "CSV 下载失败，请稍后重试。";
  const labels: Record<number, string> = { 401: "登录状态已失效。", 403: "没有导出权限。", 404: "课程或成绩册不存在。",
    409: "成绩册状态变化，当前导出未生成。", 422: "当前筛选或导出规模不符合要求。", 429: "导出请求过于频繁，请稍后重试。", 503: "导出服务暂时不可用。" };
  return labels[error.status] ?? error.message;
}
</script>

<template>
  <main class="gradebook-page">
    <header class="teacher-page-heading"><div><span class="section-kicker">GRADEBOOK</span><h2>教师成绩册</h2>
      <p>只包含 HOMEWORK/EXAM；学生编号、提交事实、批改状态和服务端公布成绩分开显示。</p></div>
      <button class="button button--primary" type="button" :disabled="!state.courseId || downloadStatus === 'downloading'" @click="downloadCsv">
        {{ downloadStatus === 'downloading' ? 'CSV 生成中…' : '下载当前筛选 CSV' }}</button>
    </header>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }} <button type="button" @click="load">重试</button></p>
    <p v-if="downloadMessage" class="feedback" :class="downloadStatus === 'error' ? 'feedback--error' : downloadStatus === 'cancelled' ? 'feedback--warning' : 'feedback--success'" role="status">
      {{ downloadMessage }} <button v-if="downloadStatus === 'downloading'" type="button" @click="cancelExport">取消下载</button></p>
    <form class="gradebook-filters" @submit.prevent="setQuery({ page: 1 })">
      <label>课程 ID<input :value="state.courseId" required @change="setQuery({ courseId: ($event.target as HTMLInputElement).value.trim(), page: 1 })"></label>
      <label>测评 ID<input :value="state.assessmentId ?? ''" @change="setQuery({ assessmentId: ($event.target as HTMLInputElement).value.trim() || undefined, page: 1 })"></label>
      <label>类型<select :value="state.type ?? ''" @change="setQuery({ type: ($event.target as HTMLSelectElement).value || undefined, page: 1 })"><option value="">作业＋考试</option><option value="HOMEWORK">作业</option><option value="EXAM">考试</option></select></label>
      <label>提交状态<select :value="state.submissionStatus ?? ''" @change="setQuery({ submissionStatus: ($event.target as HTMLSelectElement).value || undefined, page: 1 })"><option value="">全部</option><option v-for="value in ['NOT_STARTED','IN_PROGRESS','SUBMITTED','MISSING','WITHDRAWN']" :key="value" :value="value">{{ statusLabel(value) }}</option></select></label>
      <label>批改状态<select :value="state.gradingStatus ?? ''" @change="setQuery({ gradingStatus: ($event.target as HTMLSelectElement).value || undefined, page: 1 })"><option value="">全部</option><option v-for="value in ['NOT_REQUIRED','PENDING','PARTIALLY_GRADED','GRADED']" :key="value" :value="value">{{ statusLabel(value) }}</option></select></label>
      <label>迟交<select :value="selectedLate()" @change="setQuery({ late: normalizeLate(($event.target as HTMLSelectElement).value), page: 1 })"><option value="">全部</option><option value="true">迟交</option><option value="false">准时/无迟交</option></select></label>
      <label class="gradebook-filter-wide">关键词<input :value="state.keyword ?? ''" maxlength="120" placeholder="课程学生编号或昵称" @change="setQuery({ keyword: ($event.target as HTMLInputElement).value.trim() || undefined, page: 1 })"></label>
      <label>排序<select :value="displaySort" @change="setQuery({ sort: ($event.target as HTMLSelectElement).value, page: 1 })"><option value="STUDENT_NUMBER">课程学生编号</option><option value="LAST_SUBMITTED_AT">最后提交时间</option><option value="SCORE" :disabled="!ready">成绩</option><option value="SUBMISSION_STATUS">提交状态</option><option value="GRADING_STATUS">批改状态</option></select></label>
      <label>方向<select :value="state.direction" @change="setQuery({ direction: ($event.target as HTMLSelectElement).value, page: 1 })"><option value="asc">升序</option><option value="desc">降序</option></select></label>
    </form>
    <div v-if="loading && !data" class="teacher-loading" role="status">正在读取成绩册…</div>
    <template v-else-if="data">
      <p v-if="data.nicknameDependencyFailed" class="feedback feedback--warning" role="status">部分昵称暂时无法加载；课程学生编号和成绩事实仍保留。</p>
      <section class="gradebook-summary"><div><span>课程作业平均分</span><strong>{{ ready ? score(data.homeworkAverage) : "暂不公布" }}</strong></div><div><span>课程考试平均分</span><strong>{{ ready ? score(data.examAverage) : "暂不公布" }}</strong></div><div><span>学生人数</span><strong>{{ data.total }}</strong></div><div><span>数据状态</span><strong>{{ ready ? "全部就绪" : "处理中，成绩隐藏" }}</strong></div></section>
      <section v-if="!ready" class="feedback feedback--warning" role="status">当前至少有一场测评尚未关闭、结算或完成批改，成绩、平均分、极值和分布暂不进入页面。</section>
      <section class="gradebook-assessment-summary" aria-label="测评汇总"><article v-for="assessment in data.assessments" :key="assessment.assessmentId"><header><strong>{{ assessment.title }}</strong><span>{{ assessment.type }}</span></header>
        <p>{{ assessment.closeStatus }} · {{ assessment.settlementStatus }} · {{ assessment.aggregateStatus }}</p><dl><div><dt>应交</dt><dd>{{ assessment.stats.expectedCount }}</dd></div><div><dt>缺交</dt><dd>{{ assessment.stats.missingCount }}</dd></div><div><dt>待批</dt><dd>{{ assessment.stats.pendingCount }}</dd></div><div v-if="ready"><dt>准时提交率</dt><dd>{{ percent(assessment.stats.onTimeSubmissionRate) }}</dd></div><div v-if="ready"><dt>最终提交率</dt><dd>{{ percent(assessment.stats.finalSubmissionRate) }}</dd></div><div v-if="ready"><dt>最高/最低</dt><dd>{{ score(assessment.stats.highestScore) }} / {{ score(assessment.stats.lowestScore) }}</dd></div></dl><div v-if="ready" class="gradebook-score-distribution" aria-label="分数分布"><span v-for="(count, range) in assessment.stats.scoreDistribution" :key="range">{{ range }}：{{ count }} 人</span></div></article></section>
      <div class="gradebook-table-wrap"><table class="gradebook-table"><thead><tr><th>课程学生编号</th><th>昵称</th><th v-for="assessment in data.assessments" :key="assessment.assessmentId">{{ assessment.title }}<small>{{ assessment.type }}</small></th><th>作业平均</th><th>考试平均</th></tr></thead>
        <tbody><tr v-for="row in data.rows" :key="row.learnerId"><th scope="row">{{ row.courseStudentNumber }}</th><td><span v-if="row.nicknameAvailable && row.nickname">{{ row.nickname }}</span><span v-else>昵称暂不可用</span></td>
          <td v-for="assessment in data.assessments" :key="assessment.assessmentId"><button v-if="cellFor(row, assessment.assessmentId)?.attemptId" class="gradebook-cell-button" type="button" @click="openRecord(row, assessment.assessmentId)"><strong>{{ scoreFor(cellFor(row, assessment.assessmentId)) }}</strong><small>{{ statusLabel(cellFor(row, assessment.assessmentId)?.submissionStatus) }} · {{ statusLabel(cellFor(row, assessment.assessmentId)?.gradingStatus) }}<em v-if="cellFor(row, assessment.assessmentId)?.lateSubmission">迟交</em></small></button><span v-else class="gradebook-cell-button gradebook-cell-button--plain">{{ scoreFor(cellFor(row, assessment.assessmentId)) }}<small>{{ statusLabel(cellFor(row, assessment.assessmentId)?.submissionStatus) }} · {{ statusLabel(cellFor(row, assessment.assessmentId)?.gradingStatus) }}</small></span></td>
          <td>{{ ready ? score(row.homeworkAverage) : "—" }}</td><td>{{ ready ? score(row.examAverage) : "—" }}</td></tr></tbody></table></div>
      <p v-if="!data.rows.length" class="teacher-empty">当前筛选没有学生记录。</p>
      <nav v-if="data.total > data.size" class="pagination" aria-label="成绩册分页"><button type="button" :disabled="data.page <= 1" @click="setQuery({ page: data.page - 1 })">上一页</button><span>第 {{ data.page }} 页</span><button type="button" :disabled="data.page * data.size >= data.total" @click="setQuery({ page: data.page + 1 })">下一页</button></nav>
    </template>
  </main>
</template>
