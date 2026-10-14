<script setup lang="ts">
/**
 * 学生结果页：同一份 AttemptResult 支持整卷和逐题 URL 视图；组合题始终以根题整体显示。
 * 成绩与参考答案只按 gradeVisible/answerVisible 分支创建 DOM，绝不从 status 推断或用 CSS 遮挡。
 * 已公布答案/解析图片复用同一冻结快照的媒体绑定与领域授权；旧图片编号按内容区域分段恢复。
 * 题头将满分与本人得分成组展示，批改状态紧邻本人答案；纯展示调整不参与判分。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AttemptResult, LearnerAssessmentQuestion } from "@edu/api-client";
import { SafeAssessmentContent } from "@edu/ui";
import type { AssessmentMediaResolver } from "@edu/ui";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";
import { clampQuestionIndex, resultDisclosure } from "../assessment-view-model";
import { resultLegacyMedia } from "../assessment-result-media";

const route = useRoute(); const router = useRouter();
const result = ref<AttemptResult | null>(null); const loading = ref(true); const errorMessage = ref("");
let controller: AbortController | null = null;
const view = computed(() => route.query.view === "single" ? "single" : "all");
const isCourseTask = computed(() => route.name === "course-task-result");
const questionIndex = computed(() => clampQuestionIndex(route.query.question,
  result.value?.assessment.questions.length ?? 0));
const disclosure = computed(() => result.value ? resultDisclosure(result.value) : null);
const courseTaskBack = computed(() => {
  const path = `/learn/${String(route.params.courseId ?? "")}/tasks`;
  return result.value?.assessment.type === "PRACTICE" || route.query.type === "PRACTICE" ? `${path}?type=PRACTICE` : path;
});
const visibleQuestions = computed(() => !result.value ? [] : view.value === "single"
  ? [result.value.assessment.questions[questionIndex.value]].filter(Boolean) : result.value.assessment.questions);
const legacyMedia = computed(() => new Map(result.value?.assessment.questions.map(question =>
  [question.id, resultLegacyMedia(question, answerFor(question))]) ?? []));

watch(() => [route.name, route.params.courseId, route.params.assessmentId, route.params.attemptId], load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load() {
  controller?.abort(); controller = new AbortController(); loading.value = true; errorMessage.value = "";
  try {
    result.value = isCourseTask.value
      ? await studentAssessmentApi.courseTaskResult(String(route.params.courseId ?? ""), String(route.params.assessmentId ?? ""), controller.signal)
      : await studentAssessmentApi.result(String(route.params.attemptId), controller.signal);
    if (!isCourseTask.value && result.value.assessment.type !== "PRACTICE") {
      await router.replace(`/learn/${result.value.assessment.courseId}/tasks/${result.value.assessment.id}/result`);
      return;
    }
  }
  catch (error) { if (!controller.signal.aborted) errorMessage.value = messageFor(error); }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
function answerFor(question: LearnerAssessmentQuestion) {
  return result.value?.answers.find(answer => answer.assessmentQuestionId === question.id);
}
function setView(next: "all" | "single", question = 1) {
  void router.replace({ query: next === "all" ? { view: "all" } : { view: "single", question: String(question) } });
}
const resolveMedia: AssessmentMediaResolver = async (mediaId, purpose, signal) => {
  if (!result.value) throw new Error("RESULT_NOT_LOADED");
  return studentAssessmentApi.mediaGrant(result.value.assessmentId, mediaId,
    purpose === "QUESTION_ATTACHMENT" ? "ATTACHMENT" : "INLINE", signal);
};
function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线，无法读取冻结结果。";
  if (!(error instanceof GatewayRequestError)) return "结果暂时无法读取。";
  if (error.status === 401) return "登录状态已失效。";
  if (error.status === 403 || error.status === 404) return "结果不存在或不可见。";
  if (error.status === 429) return "请求过于频繁，请稍后重试。";
  return error.message;
}
</script>

<template>
  <main class="result-page">
    <div v-if="loading" class="practice-loading" role="status">正在读取冻结结果…</div>
    <section v-else-if="errorMessage" class="practice-empty" role="alert"><h2>无法查看结果</h2><p>{{ errorMessage }}</p>
      <button class="button button--quiet" type="button" @click="load">重新加载</button></section>
    <template v-else-if="result">
      <header class="result-heading"><div><RouterLink :to="isCourseTask ? courseTaskBack : '/practice'">← {{ isCourseTask ? (result.assessment.type === 'PRACTICE' ? '返回课程练习' : '返回课程任务') : '返回题库' }}</RouterLink><h2>{{ result.assessment.title }}</h2>
        <p>提交于 {{ result.submittedAt ? new Date(result.submittedAt).toLocaleString('zh-CN') : '提交时间同步中' }}</p></div>
        <div v-if="disclosure?.showTotalScore" class="result-score"><span>最新成绩</span><strong>{{ result.score }} / {{ result.totalPoints }}</strong>
          <small v-if="result.gradeUpdatedAt">最后更新 {{ new Date(result.gradeUpdatedAt).toLocaleString('zh-CN') }}</small></div>
        <div v-else class="result-receipt"><strong>提交成功</strong><span>成绩尚未公布</span></div></header>
      <nav class="result-view-switch" aria-label="结果查看方式"><button type="button" :aria-current="view === 'all' ? 'page' : undefined" @click="setView('all')">连续查看全部</button>
        <button type="button" :aria-current="view === 'single' ? 'page' : undefined" @click="setView('single', questionIndex + 1)">逐题查看</button></nav>
      <nav v-if="view === 'single'" class="result-question-map" aria-label="结果题号">
        <button v-for="(_question, index) in result.assessment.questions" :key="index" type="button"
          :aria-current="index === questionIndex ? 'step' : undefined" @click="setView('single', index + 1)">{{ index + 1 }}</button></nav>
      <section class="result-questions">
        <article v-for="question in visibleQuestions" :key="question.id" class="result-question">
          <header class="result-question-heading">
            <h3>第 {{ question.sequence }} 题</h3>
            <dl class="result-question-scores" aria-label="本题分数">
              <div><dt>本题满分</dt><dd>{{ question.points }} <span>分</span></dd></div>
              <div v-if="disclosure?.showPerQuestionGrade" class="result-question-earned">
                <dt>本题得分</dt>
                <dd v-if="answerFor(question)?.score != null">{{ answerFor(question)?.score }} <span>分</span></dd>
                <dd v-else class="result-question-ungraded">待批改</dd>
              </div>
            </dl>
          </header>
          <SafeAssessmentContent :source="question.stem" :media="question.media"
            :legacy-media="legacyMedia.get(question.id)?.stem" :media-cache-scope="result.assessmentId"
            :mark-fill-blanks="question.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
          <section v-for="child in question.children" :key="child.versionId" class="result-child"><h4>({{ child.sequence }})</h4>
            <SafeAssessmentContent :source="child.stem" :media="child.media"
              :legacy-media="child.media" :media-cache-scope="result.assessmentId"
              :mark-fill-blanks="child.type === 'FILL_BLANK'" :resolve-media="resolveMedia" /></section>
          <section class="result-answer">
            <div class="result-answer-heading"><h4>我的答案</h4>
              <span v-if="disclosure?.showPerQuestionGrade" class="result-grade-status"
                :class="{ 'result-grade-status--correct': answerFor(question)?.gradingStatus !== 'PENDING_MANUAL' && answerFor(question)?.correct === true,
                  'result-grade-status--incorrect': answerFor(question)?.gradingStatus !== 'PENDING_MANUAL' && answerFor(question)?.correct === false }">
                {{ answerFor(question)?.gradingStatus === 'PENDING_MANUAL' ? '等待教师人工批改' : answerFor(question)?.correct === true ? '作答正确' : answerFor(question)?.correct === false ? '作答有误' : '已评分' }}
              </span>
            </div>
            <ol v-if="answerFor(question)?.submittedAnswer.length" class="result-answer-list"
              :class="{ 'result-answer-list--single': answerFor(question)?.submittedAnswer.length === 1 }">
              <li v-for="(answer, index) in answerFor(question)?.submittedAnswer" :key="index">
              <SafeAssessmentContent :source="answer" /></li></ol><p v-else>未作答</p></section>
          <section v-if="disclosure?.showReferenceAnswer" class="result-reference">
            <div class="result-reference-answer"><h4>参考答案</h4>
              <div v-for="(answer, index) in answerFor(question)?.correctAnswer ?? []" :key="index"><SafeAssessmentContent :source="answer"
                :media="question.media" :legacy-media="legacyMedia.get(question.id)?.answers[index]"
                :media-cache-scope="result.assessmentId" :resolve-media="resolveMedia" /></div>
            </div>
            <h4>解题解析</h4>
            <SafeAssessmentContent :source="answerFor(question)?.analysis ?? ''" :media="question.media"
              :legacy-media="legacyMedia.get(question.id)?.analysis" :media-cache-scope="result.assessmentId"
              :resolve-media="resolveMedia" empty-text="教师未提供解析" /></section>
          <section v-else class="result-reference result-reference--pending"><h4>参考答案与解析</h4><p>教师暂未公布参考答案与解析。</p></section>
        </article>
      </section>
      <nav v-if="view === 'single'" class="question-navigation"><button type="button" :disabled="questionIndex === 0" @click="setView('single', questionIndex)">上一题</button>
        <button type="button" :disabled="questionIndex >= result.assessment.questions.length - 1" @click="setView('single', questionIndex + 2)">下一题</button></nav>
    </template>
  </main>
</template>
