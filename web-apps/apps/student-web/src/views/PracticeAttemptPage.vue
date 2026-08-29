<script setup lang="ts">
/**
 * 学生作答页：一项 assessmentQuestion 对应一个连续主问题区块，组合题子问保持有序且不拆路由。
 * 草稿通过 AttemptSaveController 防抖/定时保存；截止仅使用服务端校准时间，归零后锁定输入并提交最后成功草稿。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AttemptAnswerInput, AttemptDraft, LearnerAssessment, LearnerAssessmentQuestion, SubmitAttemptRequest } from "@edu/api-client";
import { SafeAssessmentContent } from "@edu/ui";
import type { AssessmentMediaResolver } from "@edu/ui";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";
import { AttemptSaveController, ServerDeadlineClock } from "../attempt-session";
import type { AttemptSaveSnapshot } from "../attempt-session";

const route = useRoute(); const router = useRouter();
const assessment = ref<LearnerAssessment | null>(null);
const draft = ref<AttemptDraft | null>(null);
const answers = ref<Record<string, string[]>>({});
const currentIndex = ref(0);
const overviewOpen = ref(false);
const loading = ref(true); const locked = ref(false); const submitting = ref(false);
const errorMessage = ref(""); const submitMessage = ref("");
const saveSnapshot = ref<AttemptSaveSnapshot | null>(null);
const remainingMs = ref<number | null>(null);
const clock = new ServerDeadlineClock();
let pageAbort: AbortController | null = null;
let saveController: AttemptSaveController | null = null;
let clockTimer: ReturnType<typeof setInterval> | null = null;
let submitBody: SubmitAttemptRequest | null = null;

const questions = computed(() => assessment.value?.questions ?? []);
const currentQuestion = computed(() => questions.value[currentIndex.value] ?? null);
const answeredCount = computed(() => questions.value.filter(question => isAnswered(question)).length);

onMounted(() => { void load(); document.addEventListener("visibilitychange", recalibrateWhenVisible); });
onBeforeUnmount(dispose);
onBeforeRouteLeave(() => !saveSnapshot.value?.dirty || submitting.value
  || window.confirm("仍有未保存答案，确认离开作答页？"));

watch(answers, () => {
  if (!locked.value) saveController?.change(toAnswerInputs());
}, { deep: true });

async function load() {
  disposeRuntime();
  loading.value = true; errorMessage.value = "";
  const assessmentId = String(route.params.assessmentId ?? "");
  pageAbort = new AbortController();
  try {
    const detail = await studentAssessmentApi.detail(assessmentId, pageAbort.signal);
    assessment.value = detail;
    if (detail.currentAttempt && detail.currentAttempt.status !== "DRAFT") {
      await router.replace(`/attempt/${detail.currentAttempt.id}/result`); return;
    }
    const restored = detail.currentAttempt
      ? await studentAssessmentApi.draft(detail.currentAttempt.id, pageAbort.signal)
      : await studentAssessmentApi.start(assessmentId, { clientRequestId: crypto.randomUUID() }, pageAbort.signal);
    initializeDraft(restored);
  } catch (error) {
    if (!pageAbort.signal.aborted) errorMessage.value = messageFor(error);
  } finally { if (!pageAbort?.signal.aborted) loading.value = false; }
}

function initializeDraft(value: AttemptDraft) {
  draft.value = value;
  answers.value = Object.fromEntries(questions.value.map(question => [question.id,
    [...(value.answers.find(item => item.assessmentQuestionId === question.id)?.answer ?? [])]]));
  clock.calibrate(value.serverTime, value.deadlineAt);
  saveController = new AttemptSaveController(
    (body, signal) => studentAssessmentApi.save(value.id, body, signal), value,
    snapshot => { saveSnapshot.value = snapshot; }
  );
  clockTimer = setInterval(tickClock, 500);
  tickClock();
}

function tickClock() {
  remainingMs.value = clock.remainingMilliseconds();
  if (remainingMs.value === 0 && !locked.value) {
    locked.value = true;
    const lastSaved = saveController?.getSnapshot();
    saveController?.dispose();
    if (draft.value && lastSaved) {
      submitBody = { clientRequestId: crypto.randomUUID(), expectedRevision: lastSaved.revision,
        answers: lastSaved.lastSavedAnswers };
      submitMessage.value = "考试时间已到，输入已锁定；正在按服务端最后成功保存的草稿提交。";
      void submitWithFiniteRetry();
    }
  }
}

async function recalibrateWhenVisible() {
  if (document.visibilityState !== "visible" || !assessment.value || locked.value || !pageAbort) return;
  try {
    const fresh = await studentAssessmentApi.detail(assessment.value.id, pageAbort.signal);
    assessment.value = fresh;
    clock.calibrate(fresh.serverTime, fresh.currentAttempt?.deadlineAt ?? draft.value?.deadlineAt ?? null);
    tickClock();
  } catch (error) {
    if (!pageAbort.signal.aborted) errorMessage.value = messageFor(error);
  }
}

async function confirmSubmit() {
  if (!draft.value || locked.value || submitting.value) return;
  errorMessage.value = "";
  const saved = await saveController?.flush();
  const snapshot = saveController?.getSnapshot();
  if (!saved || !snapshot || snapshot.dirty) {
    errorMessage.value = "最后一次草稿尚未成功保存，请恢复网络或手动重试保存后再提交。"; return;
  }
  if (!window.confirm(`确认提交？当前已答 ${answeredCount.value}/${questions.value.length} 题；提交后不能修改。`)) return;
  locked.value = true; saveController?.dispose();
  submitBody = { clientRequestId: crypto.randomUUID(), expectedRevision: snapshot.revision,
    answers: snapshot.lastSavedAnswers };
  await submitWithFiniteRetry();
}

async function submitWithFiniteRetry() {
  if (!draft.value || !submitBody || submitting.value) return;
  submitting.value = true; errorMessage.value = "";
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      await studentAssessmentApi.submit(draft.value.id, submitBody, pageAbort?.signal);
      await router.replace(`/attempt/${draft.value.id}/result`); return;
    } catch (error) {
      if (!retryable(error) || attempt === 2) {
        errorMessage.value = `作答已保持只读锁定。${messageFor(error)} 请稍后使用同一页面重试提交。`;
        submitting.value = false; return;
      }
      await delay(retryDelay(error, attempt), pageAbort?.signal);
    }
  }
  submitting.value = false;
}

function retryLockedSubmit() { if (locked.value && submitBody) void submitWithFiniteRetry(); }
function retryDraftSave() { void saveController?.flush(); }

function updateSingle(question: LearnerAssessmentQuestion, value: string) { answers.value[question.id] = value ? [value] : []; }
function updateMultiple(question: LearnerAssessmentQuestion, key: string, checked: boolean) {
  const current = new Set(answers.value[question.id] ?? []); checked ? current.add(key) : current.delete(key);
  answers.value[question.id] = [...current].sort();
}
function updateText(question: LearnerAssessmentQuestion, value: string) {
  answers.value[question.id] = question.type === "FILL_BLANK" ? value.split(/\r?\n/).map(item => item.trim()).filter(Boolean) : [value];
}
function compositeValue(question: LearnerAssessmentQuestion, index: number) { return answers.value[question.id]?.[index] ?? ""; }
function updateComposite(question: LearnerAssessmentQuestion, index: number, value: string) {
  const next = [...(answers.value[question.id] ?? [])]; while (next.length < question.children.length) next.push("");
  next[index] = value; answers.value[question.id] = next;
}
function compositeSelections(question: LearnerAssessmentQuestion, index: number): string[] {
  return compositeValue(question, index).split(",").map(item => item.trim()).filter(Boolean);
}
/** 组合题在契约中是一条主问题、answer 数组按子问顺序保存；多选子问在对应槽位内用逗号保存有序 token。 */
function updateCompositeMultiple(question: LearnerAssessmentQuestion, index: number, key: string, checked: boolean) {
  const values = new Set(compositeSelections(question, index)); checked ? values.add(key) : values.delete(key);
  updateComposite(question, index, [...values].sort().join(","));
}
function isAnswered(question: LearnerAssessmentQuestion) {
  return (answers.value[question.id] ?? []).some(value => value.trim().length > 0);
}
function toAnswerInputs(): AttemptAnswerInput[] {
  return questions.value.map(question => ({ assessmentQuestionId: question.id, answer: [...(answers.value[question.id] ?? [])] }));
}
function hasInline(source: string, mediaId: string) { return source.toLowerCase().includes(`{{media:${mediaId.toLowerCase()}}}`); }
function looseImages(question: LearnerAssessmentQuestion) {
  const sources = [question.stem, ...question.options.map(option => option.content),
    ...question.children.flatMap(child => [child.stem, ...child.options.map(option => option.content)])];
  return [...question.media, ...question.children.flatMap(child => child.media)]
    .filter(media => media.purpose === "QUESTION_IMAGE" && !sources.some(source => hasInline(source, media.mediaId)));
}
const resolveMedia: AssessmentMediaResolver = async (mediaId, purpose, signal) => {
  if (!assessment.value) throw new Error("ASSESSMENT_NOT_LOADED");
  return studentAssessmentApi.mediaGrant(assessment.value.id, mediaId,
    purpose === "QUESTION_ATTACHMENT" ? "ATTACHMENT" : "INLINE", signal);
};

function disposeRuntime() {
  saveController?.dispose(); saveController = null;
  if (clockTimer) clearInterval(clockTimer); clockTimer = null;
  pageAbort?.abort(); pageAbort = null;
}
function dispose() { disposeRuntime(); document.removeEventListener("visibilitychange", recalibrateWhenVisible); }
function retryable(error: unknown) { return error instanceof TypeError || error instanceof GatewayRequestError && (error.status === 429 || error.status >= 500); }
function retryDelay(error: unknown, attempt: number) { return error instanceof GatewayRequestError && error.retryAfterSeconds !== null ? Math.min(5000, error.retryAfterSeconds * 1000) : 500 * 2 ** attempt; }
function delay(ms: number, signal?: AbortSignal) { return new Promise<void>((resolve, reject) => { const timer = setTimeout(resolve, ms); signal?.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); }, { once: true }); }); }
function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线，网络恢复前无法完成请求。";
  if (!(error instanceof GatewayRequestError)) return "测评服务暂时不可用。";
  const labels: Record<number, string> = { 401: "登录状态已失效。", 403: "当前账号无权访问该测评。", 404: "测评或作答不存在、未发布或不可见。", 409: "测评、截止时间或作答 revision 已变化。", 422: error.message, 429: "请求过于频繁。", 503: "学习或媒体依赖暂时不可用。" };
  return labels[error.status] ?? error.message;
}
function formatRemaining(value: number | null) { if (value === null) return "不限时"; const seconds = Math.ceil(value / 1000); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`; }
</script>

<template>
  <main class="attempt-page">
    <div v-if="loading" class="practice-loading" role="status">正在恢复试卷与服务端草稿…</div>
    <section v-else-if="errorMessage && !assessment" class="practice-empty" role="alert"><h2>无法打开测评</h2><p>{{ errorMessage }}</p>
      <button class="button button--quiet" type="button" @click="load">重新加载</button></section>
    <template v-else-if="assessment && draft">
      <header class="attempt-header"><div><RouterLink to="/practice">← 返回练习中心</RouterLink><h2>{{ assessment.title }}</h2>
        <p>{{ answeredCount }}/{{ questions.length }} 已答 · {{ assessment.totalPoints }} 分</p></div>
        <div class="attempt-status"><strong :class="{ 'is-urgent': remainingMs !== null && remainingMs < 60_000 }">{{ formatRemaining(remainingMs) }}</strong>
          <span role="status">{{ saveSnapshot?.status === 'saving' ? '保存中…' : saveSnapshot?.status === 'failed' ? '保存失败' : saveSnapshot?.dirty ? '等待保存' : '已保存' }}</span></div></header>
      <p v-if="submitMessage" class="feedback feedback--warning" role="status">{{ submitMessage }}</p>
      <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
      <div class="attempt-layout">
        <aside class="question-map" aria-label="题号导航"><button type="button" :aria-expanded="overviewOpen" @click="overviewOpen = !overviewOpen">整卷概览</button>
          <ol :class="{ 'is-open': overviewOpen }"><li v-for="(question, index) in questions" :key="question.id">
            <button type="button" :class="{ current: index === currentIndex, answered: isAnswered(question) }"
              :aria-current="index === currentIndex ? 'step' : undefined" @click="currentIndex = index; overviewOpen = false">{{ index + 1 }}<span>{{ isAnswered(question) ? '已答' : '未答' }}</span></button>
          </li></ol></aside>
        <section v-if="currentQuestion" class="question-block" :aria-labelledby="`question-${currentQuestion.id}`">
          <header><span>第 {{ currentIndex + 1 }} 题 · {{ currentQuestion.points }} 分</span><strong>{{ currentQuestion.type }}</strong></header>
          <h3 :id="`question-${currentQuestion.id}`" class="sr-only">第 {{ currentIndex + 1 }} 题</h3>
          <SafeAssessmentContent :source="currentQuestion.stem" :media="currentQuestion.media"
            :mark-fill-blanks="currentQuestion.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
          <div v-for="media in looseImages(currentQuestion)" :key="media.mediaId" class="question-loose-media">
            <SafeAssessmentContent :source="`{{media:${media.mediaId}}}`" :media="[media]" :resolve-media="resolveMedia" />
          </div>
          <fieldset v-if="currentQuestion.type === 'SINGLE_CHOICE' || currentQuestion.type === 'TRUE_FALSE'" :disabled="locked">
            <legend>请选择一个答案</legend>
            <label v-for="option in currentQuestion.type === 'TRUE_FALSE' ? [{ key: 'TRUE', content: '正确' }, { key: 'FALSE', content: '错误' }] : currentQuestion.options" :key="option.key">
              <input type="radio" :name="currentQuestion.id" :value="option.key" :checked="answers[currentQuestion.id]?.[0] === option.key"
                @change="updateSingle(currentQuestion, option.key)"><strong>{{ option.key }}</strong>
              <SafeAssessmentContent :source="option.content" :media="currentQuestion.media" :resolve-media="resolveMedia" /></label>
          </fieldset>
          <fieldset v-else-if="currentQuestion.type === 'MULTIPLE_CHOICE'" :disabled="locked"><legend>可选择多个答案</legend>
            <label v-for="option in currentQuestion.options" :key="option.key"><input type="checkbox" :checked="answers[currentQuestion.id]?.includes(option.key)"
              @change="updateMultiple(currentQuestion, option.key, ($event.target as HTMLInputElement).checked)"><strong>{{ option.key }}</strong>
              <SafeAssessmentContent :source="option.content" :media="currentQuestion.media" :resolve-media="resolveMedia" /></label></fieldset>
          <label v-else-if="currentQuestion.type !== 'COMPOSITE'" class="written-answer"><span>你的答案</span>
            <textarea :value="answers[currentQuestion.id]?.join('\n') ?? ''" rows="6" :disabled="locked"
              :placeholder="currentQuestion.type === 'FILL_BLANK' ? '多个空按行填写' : '输入完整作答过程'"
              @input="updateText(currentQuestion, ($event.target as HTMLTextAreaElement).value)" /></label>
          <section v-else class="composite-children" aria-label="组合题小问">
            <article v-for="(child, childIndex) in currentQuestion.children" :key="child.versionId"><h4>({{ child.sequence }})</h4>
              <SafeAssessmentContent :source="child.stem" :media="child.media" :mark-fill-blanks="child.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
              <fieldset v-if="child.type === 'SINGLE_CHOICE' || child.type === 'TRUE_FALSE'" :disabled="locked"><legend>本小问请选择一个答案</legend>
                <label v-for="option in child.type === 'TRUE_FALSE' ? [{ key: 'TRUE', content: '正确' }, { key: 'FALSE', content: '错误' }] : child.options" :key="option.key">
                  <input type="radio" :name="`${currentQuestion.id}-${childIndex}`" :value="option.key"
                    :checked="compositeValue(currentQuestion, childIndex) === option.key"
                    @change="updateComposite(currentQuestion, childIndex, option.key)"><strong>{{ option.key }}</strong>
                  <SafeAssessmentContent :source="option.content" :media="child.media" :resolve-media="resolveMedia" /></label>
              </fieldset>
              <fieldset v-else-if="child.type === 'MULTIPLE_CHOICE'" :disabled="locked"><legend>本小问可选择多个答案</legend>
                <label v-for="option in child.options" :key="option.key"><input type="checkbox"
                  :checked="compositeSelections(currentQuestion, childIndex).includes(option.key)"
                  @change="updateCompositeMultiple(currentQuestion, childIndex, option.key, ($event.target as HTMLInputElement).checked)">
                  <strong>{{ option.key }}</strong><SafeAssessmentContent :source="option.content" :media="child.media" :resolve-media="resolveMedia" /></label>
              </fieldset>
              <label v-else><span>本小问答案</span><textarea :value="compositeValue(currentQuestion, childIndex)" rows="4" :disabled="locked"
                :placeholder="child.type === 'FILL_BLANK' ? '多个空按行填写' : '输入完整作答过程'"
                @input="updateComposite(currentQuestion, childIndex, ($event.target as HTMLTextAreaElement).value)" /></label></article>
          </section>
          <footer class="question-navigation"><button type="button" :disabled="currentIndex === 0" @click="currentIndex--">上一题</button>
            <button v-if="currentIndex < questions.length - 1" type="button" @click="currentIndex++">下一题</button>
            <button v-else class="button button--primary" type="button" :disabled="locked || submitting" @click="confirmSubmit">{{ submitting ? '提交中…' : '检查并提交' }}</button></footer>
        </section>
      </div>
      <footer class="attempt-submit-bar"><span>{{ saveSnapshot?.dirty ? '存在未保存答案' : '草稿与服务端一致' }}</span>
        <button v-if="saveSnapshot?.status === 'failed' && !locked" type="button" @click="retryDraftSave">重试保存</button>
        <button v-if="locked && errorMessage" class="button button--primary" type="button" :disabled="submitting" @click="retryLockedSubmit">重试提交</button>
        <button v-else class="button button--primary" type="button" :disabled="locked || submitting" @click="confirmSubmit">提交整卷</button></footer>
    </template>
  </main>
</template>
