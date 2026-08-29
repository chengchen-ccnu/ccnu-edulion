<script setup lang="ts">
/**
 * 教师测评唯一管理页。assessmentId/panel/courseId/lessonId/status/page 均由 URL 恢复；所有写操作携带服务端 revision，
 * 关闭与两类公布只使用 T11B 资格事实。409 保留本地编辑/评分输入，不静默覆盖，离页停止请求与结算轮询。
 */
import { GatewayRequestError } from "@edu/api-client";
import type {
  AssessmentPage, AssessmentStatus, AttemptResult, CreateAssessmentRequest, QuestionSummary,
  TeacherAssessment, TeacherAttemptResultPage, TeacherQuestion
} from "@edu/api-client";
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { teacherAssessmentApi } from "../assessment-api";
import { publicationBlockReason, shouldPollSettlement } from "../assessment-management";
import QuestionVersionPreview from "../components/QuestionVersionPreview.vue";
import { teacherQuestionApi } from "../question-api";

type Panel = "basic" | "questions" | "publication" | "closure" | "results" | "grading";
type AssessmentForm = Omit<CreateAssessmentRequest, "clientRequestId">;
type GradeDraft = { score: string; comment: string };

const route = useRoute(); const router = useRouter();
const list = ref<AssessmentPage | null>(null); const assessment = ref<TeacherAssessment | null>(null);
const questionPage = ref<{ items: QuestionSummary[]; page: number; size: number; total: number } | null>(null);
const previewQuestion = ref<TeacherQuestion | null>(null); const resultPage = ref<TeacherAttemptResultPage | null>(null);
const selectedAttempt = ref<AttemptResult | null>(null); const gradeDrafts = reactive<Record<string, GradeDraft>>({});
const questionKeyword = ref("");
const configuredQuestions = ref<Array<{ questionVersionId: string; points: number }>>([]);
const loading = ref(false); const busy = ref(false); const errorMessage = ref(""); const successMessage = ref("");
const conflictMessage = ref(""); const localDirty = ref(false);
let controller: AbortController | null = null; let pollTimer: ReturnType<typeof setTimeout> | null = null; let pollCount = 0;

const panel = computed<Panel>(() => ["basic", "questions", "publication", "closure", "results", "grading"].includes(String(route.query.panel))
  ? route.query.panel as Panel : "basic");
const visibleAssessments = computed(() => (list.value?.items ?? []).filter(item =>
  !route.query.lessonId || item.lessonId === route.query.lessonId));
const form = reactive<AssessmentForm>({ courseId: "", lessonId: null, title: "", description: "", type: "PRACTICE",
  gradePublicationPolicy: "MANUAL", answerPublicationPolicy: "AFTER_SUBMISSION", opensAt: null, closesAt: null,
  timeLimitSeconds: null });

watch(() => route.query, restoreFromUrl, { immediate: true });
onMounted(() => document.addEventListener("visibilitychange", handleVisibilityChange));
onBeforeUnmount(dispose);

async function restoreFromUrl() {
  disposeRequests(); controller = new AbortController(); errorMessage.value = ""; successMessage.value = "";
  await loadList();
  const id = typeof route.query.assessmentId === "string" ? route.query.assessmentId : "";
  if (id) await loadDetail(id, !localDirty.value);
  else prepareCreateFromUrl();
  if (panel.value === "questions") await loadQuestions();
  if (panel.value === "results" || panel.value === "grading") await loadResults();
}

async function loadList() {
  loading.value = true;
  try { list.value = await teacherAssessmentApi.list({ page: positive(route.query.page), size: 20,
    courseId: queryString("courseId"), status: statusQuery() }, controller?.signal); }
  catch (error) { errorMessage.value = messageFor(error); }
  finally { loading.value = false; }
}

async function loadDetail(id: string, syncForm = true) {
  try {
    const value = await teacherAssessmentApi.detail(id, controller?.signal); assessment.value = value;
    if (syncForm) {
      configuredQuestions.value = value.questions.map(item => ({ questionVersionId: item.questionVersionId, points: item.points }));
      syncFormFromAssessment(value);
    }
    if (["PENDING", "PROCESSING"].includes(value.settlementStatus)) schedulePoll(); else stopPoll();
  } catch (error) {
    errorMessage.value = messageFor(error);
    if (assessment.value?.id !== id) assessment.value = null;
  }
}

function prepareCreateFromUrl() {
  assessment.value = null; configuredQuestions.value = []; localDirty.value = false;
  Object.assign(form, { courseId: queryString("courseId") ?? "", lessonId: queryString("lessonId") ?? null,
    title: "", description: "", type: "PRACTICE", gradePublicationPolicy: "MANUAL",
    answerPublicationPolicy: "AFTER_SUBMISSION", opensAt: null, closesAt: null, timeLimitSeconds: null });
}

function syncFormFromAssessment(value: TeacherAssessment) {
  Object.assign(form, { courseId: value.courseId, lessonId: value.lessonId, title: value.title,
    description: value.description, type: value.type, gradePublicationPolicy: value.gradePublicationPolicy,
    answerPublicationPolicy: value.answerPublicationPolicy, opensAt: toLocalInput(value.opensAt),
    closesAt: toLocalInput(value.closesAt), timeLimitSeconds: value.timeLimitSeconds });
  localDirty.value = false; conflictMessage.value = "";
}

async function saveBasic() {
  if (busy.value) return; busy.value = true; errorMessage.value = ""; successMessage.value = "";
  try {
    const body = normalizedForm();
    const saved = assessment.value
      ? await teacherAssessmentApi.update(assessment.value.id, { expectedRevision: assessment.value.revision,
        title: body.title, description: body.description, type: body.type,
        gradePublicationPolicy: body.gradePublicationPolicy, answerPublicationPolicy: body.answerPublicationPolicy,
        opensAt: body.opensAt, closesAt: body.closesAt, timeLimitSeconds: body.timeLimitSeconds }, controller?.signal)
      : await teacherAssessmentApi.create({ clientRequestId: crypto.randomUUID(), ...body }, controller?.signal);
    assessment.value = saved; syncFormFromAssessment(saved); successMessage.value = "测评基本信息已保存。";
    await setQuery({ assessmentId: saved.id, panel: "basic" }); await loadList();
  } catch (error) {
    if (error instanceof GatewayRequestError && error.status === 409) conflictMessage.value = "服务端 revision 已变化；当前输入已保留。请载入最新版本后确认是否重新应用。";
    errorMessage.value = messageFor(error);
  } finally { busy.value = false; }
}

async function removeDraft() {
  if (!assessment.value || assessment.value.status !== "DRAFT" || !window.confirm(`确认删除草稿“${assessment.value.title}”？`)) return;
  busy.value = true;
  try { await teacherAssessmentApi.remove(assessment.value.id, controller?.signal); successMessage.value = "草稿已删除。";
    await setQuery({ assessmentId: undefined, panel: "basic" }); prepareCreateFromUrl(); await loadList(); }
  catch (error) { errorMessage.value = messageFor(error); } finally { busy.value = false; }
}

async function reloadLatestPreservingInput() {
  if (selectedAttempt.value && panel.value === "grading") {
    await loadResults(true);
    conflictMessage.value = "已载入最新作答 revision；你的得分和评语仍保留，可检查后重新提交。";
    return;
  }
  if (!assessment.value) return;
  await loadDetail(assessment.value.id, false);
  conflictMessage.value = "已载入最新测评 revision；你的表单或组卷输入仍保留，可检查后重新提交。";
}

async function loadQuestions() {
  questionKeyword.value = queryString("keyword") ?? "";
  try { questionPage.value = await teacherQuestionApi.list({ page: positive(route.query.questionPage), size: 20,
    scope: queryString("scope") as "SYSTEM" | "TEACHER" | undefined, keyword: queryString("keyword") }, controller?.signal); }
  catch (error) { errorMessage.value = messageFor(error); }
}
async function searchQuestions() { await setQuery({ keyword: questionKeyword.value.trim(), questionPage: 1 }); }
async function preview(item: QuestionSummary) {
  try { previewQuestion.value = await teacherQuestionApi.detail(item.id, controller?.signal); }
  catch (error) { errorMessage.value = messageFor(error); }
}
function addQuestion(item: QuestionSummary) {
  if (!configuredQuestions.value.some(question => question.questionVersionId === item.currentVersionId)) {
    configuredQuestions.value.push({ questionVersionId: item.currentVersionId, points: 10 }); localDirty.value = true;
  }
}
function removeQuestion(index: number) { configuredQuestions.value.splice(index, 1); localDirty.value = true; }
function moveQuestion(index: number, delta: number) { const target = index + delta; if (target < 0 || target >= configuredQuestions.value.length) return;
  const [item] = configuredQuestions.value.splice(index, 1); configuredQuestions.value.splice(target, 0, item); localDirty.value = true; }
async function saveQuestions() {
  if (!assessment.value || !configuredQuestions.value.length) return; busy.value = true; resetFeedback();
  try { assessment.value = await teacherAssessmentApi.replaceQuestions(assessment.value.id, {
    expectedRevision: assessment.value.revision, questions: configuredQuestions.value.map((item, index) => ({
      questionVersionId: item.questionVersionId, sequence: index + 1, points: Number(item.points)
    })) }, controller?.signal); localDirty.value = false; successMessage.value = "试卷已按精确不可变版本保存。"; }
  catch (error) { if (error instanceof GatewayRequestError && error.status === 409) conflictMessage.value = "组卷 revision 冲突；当前题目顺序和分值已保留。"; errorMessage.value = messageFor(error); }
  finally { busy.value = false; }
}

async function changePublication(status: "PUBLISHED" | "ARCHIVED") {
  if (!assessment.value || !window.confirm(status === "PUBLISHED" ? "确认发布当前不可变试卷？" : "确认归档？历史作答仍将保留。")) return;
  busy.value = true; resetFeedback();
  try { assessment.value = await teacherAssessmentApi.publication(assessment.value.id, {
    expectedRevision: assessment.value.revision, status }, controller?.signal); successMessage.value = status === "PUBLISHED" ? "测评已发布。" : "测评已归档。"; await loadList(); }
  catch (error) { errorMessage.value = messageFor(error); } finally { busy.value = false; }
}

async function closeAssessment() {
  if (!assessment.value) return;
  const count = assessment.value.activeAttemptCount;
  if (!window.confirm(`关闭不可撤销。当前仍有 ${count} 份进行中作答；关闭后只结算服务端最后成功草稿。确认继续？`)) return;
  busy.value = true; resetFeedback();
  const body = { clientRequestId: crypto.randomUUID(), expectedRevision: assessment.value.revision };
  try { const closed = await teacherAssessmentApi.close(assessment.value.id, body, controller?.signal);
    assessment.value = closed.assessment; successMessage.value = `已关闭，受影响 ${closed.affectedInProgressAttemptCount} 份。`; pollCount = 0; schedulePoll(); }
  catch (error) { errorMessage.value = messageFor(error); } finally { busy.value = false; }
}
async function publish(kind: "grades" | "answers") {
  if (!assessment.value || !window.confirm(kind === "grades" ? "成绩公布后不可撤回，确认公布？" : "参考答案与解析公布后不可撤回，确认公布？")) return;
  busy.value = true; resetFeedback(); const body = { clientRequestId: crypto.randomUUID(), expectedRevision: assessment.value.revision };
  try { const result = kind === "grades" ? await teacherAssessmentApi.publishGrades(assessment.value.id, body, controller?.signal)
    : await teacherAssessmentApi.publishAnswers(assessment.value.id, body, controller?.signal);
    assessment.value = result.assessment; successMessage.value = kind === "grades" ? "成绩已公布。" : "参考答案与解析已公布。"; }
  catch (error) { errorMessage.value = messageFor(error); } finally { busy.value = false; }
}

function schedulePoll() {
  stopPoll();
  if (!shouldPollSettlement(assessment.value, document.visibilityState === "visible", pollCount)) return;
  pollTimer = setTimeout(async () => { pollCount += 1; if (assessment.value) await loadDetail(assessment.value.id, false); }, 2000);
}
function stopPoll() { if (pollTimer) clearTimeout(pollTimer); pollTimer = null; }
function handleVisibilityChange() {
  if (document.visibilityState === "visible") schedulePoll(); else stopPoll();
}

async function loadResults(preserveGradeInputs = false) {
  if (!assessment.value) return;
  try { resultPage.value = await teacherAssessmentApi.results(assessment.value.id, { page: positive(route.query.resultPage), size: 20 }, controller?.signal);
    const attemptId = queryString("attemptId");
    if (attemptId && panel.value === "grading") applyAttempt(resultPage.value.items.find(item => item.attemptId === attemptId) ?? null, preserveGradeInputs); }
  catch (error) { errorMessage.value = messageFor(error); }
}
function selectAttempt(value: AttemptResult | null, preserveInputs = false) {
  applyAttempt(value, preserveInputs);
  if (value) void setQuery({ attemptId: value.attemptId, panel: "grading" });
}
function applyAttempt(value: AttemptResult | null, preserveInputs = false) {
  selectedAttempt.value = value;
  if (!preserveInputs) {
    Object.keys(gradeDrafts).forEach(key => delete gradeDrafts[key]);
    value?.answers.filter(answer => answer.gradingStatus === "PENDING_MANUAL" || answer.gradingStatus === "MANUALLY_GRADED")
      .forEach(answer => { gradeDrafts[answer.assessmentQuestionId] = { score: answer.score?.toString() ?? "", comment: "" }; });
  }
}
async function submitGrades() {
  if (!selectedAttempt.value) return; const grades = Object.entries(gradeDrafts).map(([assessmentQuestionId, value]) => ({
    assessmentQuestionId, score: Number(value.score), comment: value.comment
  })).filter(item => Number.isFinite(item.score));
  if (!grades.length) { errorMessage.value = "请至少录入一项有效得分。"; return; }
  busy.value = true; resetFeedback();
  try { selectedAttempt.value = await teacherAssessmentApi.grade(selectedAttempt.value.attemptId, {
    clientRequestId: crypto.randomUUID(), expectedRevision: selectedAttempt.value.revision, grades
  }, controller?.signal); successMessage.value = "人工批改已提交；如成绩已公布，学生将直接看到最新分数。"; await loadResults(); }
  catch (error) { if (error instanceof GatewayRequestError && error.status === 409) conflictMessage.value = "作答 revision 已变化；当前得分和评语输入已保留。请载入最新作答后确认重新应用。"; errorMessage.value = messageFor(error); }
  finally { busy.value = false; }
}

function normalizedForm(): AssessmentForm { const exam = form.type === "EXAM"; return { ...form,
  courseId: form.courseId.trim(), lessonId: form.lessonId || null, opensAt: fromLocalInput(form.opensAt),
  closesAt: fromLocalInput(form.closesAt), timeLimitSeconds: exam && form.timeLimitSeconds ? Number(form.timeLimitSeconds) : null,
  answerPublicationPolicy: form.type === "PRACTICE" ? form.answerPublicationPolicy : form.answerPublicationPolicy === "AFTER_SUBMISSION" ? "MANUAL_AFTER_CLOSE" : form.answerPublicationPolicy } as AssessmentForm; }
function setQuery(values: Record<string, string | number | undefined>) { const query = { ...route.query }; Object.entries(values).forEach(([key, value]) => { if (value === undefined || value === "") delete query[key]; else query[key] = String(value); }); return router.replace({ query }); }
function openPanel(value: Panel) { void setQuery({ panel: value, attemptId: value === "grading" ? queryString("attemptId") : undefined }); }
function selectAssessment(id: string) { localDirty.value = false; void setQuery({ assessmentId: id, panel: "basic" }); }
function queryString(name: string) { return typeof route.query[name] === "string" && route.query[name] ? String(route.query[name]) : undefined; }
function statusQuery(): AssessmentStatus | undefined { const value = queryString("status"); return ["DRAFT", "PUBLISHED", "ARCHIVED"].includes(value ?? "") ? value as AssessmentStatus : undefined; }
function positive(value: unknown) { const parsed = Number(value); return Number.isInteger(parsed) && parsed > 0 ? parsed : 1; }
function toLocalInput(value: string | null) { if (!value) return null; const date = new Date(value); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16); }
function fromLocalInput(value: string | null) { return value ? new Date(value).toISOString() : null; }
function disposeRequests() { controller?.abort(); controller = null; stopPoll(); }
function dispose() {
  disposeRequests();
  document.removeEventListener("visibilitychange", handleVisibilityChange);
}
function resetFeedback() { errorMessage.value = ""; successMessage.value = ""; conflictMessage.value = ""; }
function messageFor(error: unknown) { if (error instanceof TypeError) return "当前离线；已加载内容和本地输入仍保留。";
  if (!(error instanceof GatewayRequestError)) return "测评服务暂时不可用。"; const labels: Record<number, string> = { 401: "登录状态已失效。", 403: "没有教学权限或不拥有该课程/测评。", 404: "测评、题目或作答不存在或不可见。", 409: "状态或 revision 已变化。", 422: error.message, 429: "请求过于频繁，请稍后重试。", 503: "课程或学习依赖暂时不可用。" }; return labels[error.status] ?? error.message; }
const panelLabels: Record<Panel, string> = { basic: "基本信息", questions: "题目配置", publication: "发布状态", closure: "关闭与公布", results: "作答结果", grading: "人工批改" };
</script>

<template>
  <main class="teacher-assessment-page"><header class="assessment-admin-heading"><div><span class="section-kicker">ASSESSMENT MANAGEMENT</span><h2>测评管理</h2>
    <p>唯一管理入口；关闭、成绩和答案公布均以服务端事实为准。</p></div><button class="button button--primary" type="button" @click="setQuery({ assessmentId: undefined, panel: 'basic' })">新建草稿</button></header>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p><p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>
    <p v-if="conflictMessage" class="feedback feedback--warning" role="alert">{{ conflictMessage }} <button type="button" @click="reloadLatestPreservingInput">载入最新 revision（保留输入）</button></p>
    <div class="assessment-admin-layout"><aside class="assessment-list-panel"><form @submit.prevent><label>课程 ID<input :value="route.query.courseId ?? ''" @change="setQuery({ courseId: ($event.target as HTMLInputElement).value.trim(), page: 1 })"></label>
      <label>课时 ID<input :value="route.query.lessonId ?? ''" @change="setQuery({ lessonId: ($event.target as HTMLInputElement).value.trim(), page: 1 })"></label>
      <label>状态<select :value="route.query.status ?? ''" @change="setQuery({ status: ($event.target as HTMLSelectElement).value, page: 1 })"><option value="">全部</option><option>DRAFT</option><option>PUBLISHED</option><option>ARCHIVED</option></select></label></form>
      <div v-if="loading" role="status">正在读取测评…</div><ul><li v-for="item in visibleAssessments" :key="item.id"><button type="button" :aria-current="assessment?.id === item.id ? 'page' : undefined" @click="selectAssessment(item.id)"><strong>{{ item.title }}</strong><span>{{ item.status }} · r{{ item.revision }}</span></button></li></ul>
      <nav v-if="list && list.total > list.size" class="pagination"><button :disabled="list.page <= 1" @click="setQuery({ page: list.page - 1 })">上一页</button><span>{{ list.page }}</span><button :disabled="list.page * list.size >= list.total" @click="setQuery({ page: list.page + 1 })">下一页</button></nav></aside>
      <section class="assessment-workbench"><nav v-if="assessment" class="assessment-tabs" aria-label="测评管理面板"><button v-for="(label, key) in panelLabels" :key="key" type="button" :aria-current="panel === key ? 'page' : undefined" @click="openPanel(key)">{{ label }}</button></nav>
        <form v-if="panel === 'basic'" class="assessment-form" @submit.prevent="saveBasic" @input="localDirty = true"><h3>{{ assessment ? '编辑基本信息' : '创建测评草稿' }}</h3>
          <label>课程 ID<input v-model="form.courseId" required :disabled="Boolean(assessment)"></label><label>课时 ID（可空）<input v-model="form.lessonId"></label>
          <label>标题<input v-model="form.title" maxlength="160" required></label><label>描述<textarea v-model="form.description" maxlength="5000" rows="4" /></label>
          <label>类型<select v-model="form.type"><option value="PRACTICE">普通练习</option><option value="HOMEWORK">作业</option><option value="EXAM">限时考试</option></select></label>
          <label>成绩公布策略<select v-model="form.gradePublicationPolicy"><option value="MANUAL">手动公布</option><option value="AUTOMATIC_AFTER_GRADING">完成批改后自动公布</option></select></label>
          <label>答案公布策略<select v-model="form.answerPublicationPolicy"><option v-if="form.type === 'PRACTICE'" value="AFTER_SUBMISSION">个人提交后</option><option value="AFTER_ASSESSMENT_GRADED">整场批改后</option><option value="MANUAL_AFTER_CLOSE">关闭后手动</option><option value="NEVER">永不公布</option></select></label>
          <label>开放时间（可空）<input v-model="form.opensAt" type="datetime-local"></label><label>截止时间（可空）<input v-model="form.closesAt" type="datetime-local"></label>
          <label v-if="form.type === 'EXAM'">限时秒数<input v-model.number="form.timeLimitSeconds" type="number" min="60" max="28800"></label>
          <footer><button class="button button--primary" type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存基本信息' }}</button><button v-if="assessment?.status === 'DRAFT'" class="button button--danger" type="button" @click="removeDraft">删除草稿</button></footer></form>
        <section v-else-if="panel === 'questions' && assessment" class="question-config"><h3>题目配置</h3><p>只保存精确不可变 questionVersionId；组合题整体加入。</p>
          <ol class="configured-list"><li v-for="(item, index) in configuredQuestions" :key="item.questionVersionId"><span>{{ index + 1 }}</span><code>{{ item.questionVersionId }}</code><label>分值<input v-model.number="item.points" type="number" min=".01" step=".01" @input="localDirty = true"></label><button @click="moveQuestion(index,-1)">↑</button><button @click="moveQuestion(index,1)">↓</button><button @click="removeQuestion(index)">移除</button></li></ol>
          <button class="button button--primary" :disabled="busy || !configuredQuestions.length || assessment.status !== 'DRAFT'" @click="saveQuestions">保存组卷</button>
          <form class="question-search" @submit.prevent="searchQuestions"><input v-model="questionKeyword" placeholder="搜索题库"><select :value="route.query.scope ?? ''" @change="setQuery({ scope: ($event.target as HTMLSelectElement).value, questionPage: 1 })"><option value="">SYSTEM＋本人</option><option value="SYSTEM">SYSTEM 公共题</option><option value="TEACHER">本人题</option></select><button>查询</button></form>
          <div class="question-picker"><ul><li v-for="item in questionPage?.items ?? []" :key="item.id"><div><strong>{{ item.type }} · {{ item.scope }}</strong><span>{{ item.stemPreview }}</span></div><button @click="preview(item)">预览</button><button :disabled="assessment.status !== 'DRAFT'" @click="addQuestion(item)">整题加入</button></li></ul>
            <QuestionVersionPreview v-if="previewQuestion" :version="previewQuestion.currentVersion" /></div></section>
        <section v-else-if="panel === 'publication' && assessment" class="status-panel"><h3>发布状态</h3><dl><div><dt>当前状态</dt><dd>{{ assessment.status }}</dd></div><div><dt>题目/总分</dt><dd>{{ assessment.questionCount }} / {{ assessment.totalPoints }}</dd></div></dl>
          <button v-if="assessment.status === 'DRAFT'" class="button button--publish" :disabled="busy" @click="changePublication('PUBLISHED')">发布不可变试卷</button><button v-else-if="assessment.status === 'PUBLISHED'" class="button button--danger" :disabled="busy" @click="changePublication('ARCHIVED')">归档测评</button></section>
        <section v-else-if="panel === 'closure' && assessment" class="status-panel"><h3>关闭与公布</h3><dl><div><dt>整场</dt><dd>{{ assessment.closeStatus }}</dd></div><div><dt>进行中</dt><dd>{{ assessment.activeAttemptCount }}</dd></div><div><dt>待结算</dt><dd>{{ assessment.pendingSettlementCount }}</dd></div><div><dt>结算</dt><dd>{{ assessment.settlementStatus }}</dd></div><div><dt>待批人数/题数</dt><dd>{{ assessment.pendingManualGradingAttemptCount }} / {{ assessment.pendingManualGradingAnswerCount }}</dd></div><div><dt>结果就绪</dt><dd>{{ assessment.resultsReady ? '是' : '否' }}</dd></div></dl>
          <p v-if="assessment.settlementStatus === 'FAILED'" class="feedback feedback--error">有限结算已失败并停止自动重试；草稿仍保留，请联系运维诊断。</p>
          <button v-if="assessment.closeStatus === 'OPEN' && assessment.status === 'PUBLISHED'" class="button button--danger" :disabled="busy" @click="closeAssessment">不可撤销地关闭整场</button>
          <div class="publication-command"><button class="button button--primary" :disabled="busy || !assessment.gradePublicationEligible || assessment.gradePublicationStatus === 'PUBLISHED'" @click="publish('grades')">公布成绩（不可撤回）</button><small v-if="publicationBlockReason('grades', assessment)">{{ publicationBlockReason('grades', assessment) }}</small>
            <button class="button button--primary" :disabled="busy || !assessment.answerPublicationEligible || assessment.answerPublicationStatus === 'PUBLISHED'" @click="publish('answers')">公布参考答案（不可撤回）</button><small v-if="publicationBlockReason('answers', assessment)">{{ publicationBlockReason('answers', assessment) }}</small></div></section>
        <section v-else-if="panel === 'results' && assessment" class="results-panel"><h3>作答结果</h3><table><thead><tr><th>learnerId</th><th>状态</th><th>分数</th><th>待批</th><th></th></tr></thead><tbody><tr v-for="item in resultPage?.items ?? []" :key="item.attemptId"><td><code>{{ item.learnerId }}</code></td><td>{{ item.status }}</td><td>{{ item.score ?? '未公布/待批' }}</td><td>{{ item.answers.filter(answer => answer.gradingStatus === 'PENDING_MANUAL').length }}</td><td><button @click="selectAttempt(item)">逐份批改</button></td></tr></tbody></table>
          <nav v-if="resultPage && resultPage.total > resultPage.size" class="pagination"><button :disabled="resultPage.page <= 1" @click="setQuery({ resultPage: resultPage.page - 1 })">上一页</button><span>{{ resultPage.page }}</span><button :disabled="resultPage.page * resultPage.size >= resultPage.total" @click="setQuery({ resultPage: resultPage.page + 1 })">下一页</button></nav></section>
        <section v-else-if="panel === 'grading' && selectedAttempt" class="grading-panel"><h3>人工批改 · learnerId {{ selectedAttempt.learnerId }}</h3><p>响应不提供历史评语，因此这里只展示并提交当前输入，不伪造历史。</p>
          <form @submit.prevent="submitGrades"><article v-for="answer in selectedAttempt.answers.filter(item => item.gradingStatus === 'PENDING_MANUAL' || item.gradingStatus === 'MANUALLY_GRADED')" :key="answer.assessmentQuestionId"><h4>{{ answer.assessmentQuestionId }}</h4><p>学生答案：{{ answer.submittedAnswer.join('；') || '未作答' }}</p><label>得分<input v-model="gradeDrafts[answer.assessmentQuestionId].score" type="number" min="0" step=".01"></label><label>评语<textarea v-model="gradeDrafts[answer.assessmentQuestionId].comment" maxlength="2000" rows="3" /></label></article><button class="button button--primary" :disabled="busy">提交人工批改</button></form></section>
        <section v-else class="practice-empty"><h3>请选择或创建测评</h3><p>列表、面板和上下文会保存在 URL 中。</p></section>
      </section></div>
  </main>
</template>
