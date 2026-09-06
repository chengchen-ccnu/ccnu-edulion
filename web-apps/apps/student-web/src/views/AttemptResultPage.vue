<script setup lang="ts">
/**
 * 学生结果页：同一份 AttemptResult 支持整卷和逐题 URL 视图；组合题始终以根题整体显示。
 * 成绩与参考答案只按 gradeVisible/answerVisible 分支创建 DOM，绝不从 status 推断或用 CSS 遮挡。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AttemptResult, LearnerAssessmentQuestion } from "@edu/api-client";
import { SafeAssessmentContent } from "@edu/ui";
import type { AssessmentMediaResolver } from "@edu/ui";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";
import { clampQuestionIndex, resultDisclosure } from "../assessment-view-model";

const route = useRoute(); const router = useRouter();
const result = ref<AttemptResult | null>(null); const loading = ref(true); const errorMessage = ref("");
let controller: AbortController | null = null;
const view = computed(() => route.query.view === "single" ? "single" : "all");
const isCourseTask = computed(() => route.name === "course-task-result");
const questionIndex = computed(() => clampQuestionIndex(route.query.question,
  result.value?.assessment.questions.length ?? 0));
const disclosure = computed(() => result.value ? resultDisclosure(result.value) : null);
const visibleQuestions = computed(() => !result.value ? [] : view.value === "single"
  ? [result.value.assessment.questions[questionIndex.value]].filter(Boolean) : result.value.assessment.questions);

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
      <header class="result-heading"><div><RouterLink :to="isCourseTask ? `/learn/${route.params.courseId}/tasks` : '/practice'">← {{ isCourseTask ? '返回课程任务' : '返回练习中心' }}</RouterLink><h2>{{ result.assessment.title }}</h2>
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
          <header><strong>第 {{ question.sequence }} 题</strong><span>{{ question.points }} 分</span>
            <span v-if="disclosure?.showPerQuestionGrade">本题 {{ answerFor(question)?.score ?? '待批' }} 分</span></header>
          <SafeAssessmentContent :source="question.stem" :media="question.media"
            :mark-fill-blanks="question.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
          <section v-for="child in question.children" :key="child.versionId" class="result-child"><h4>({{ child.sequence }})</h4>
            <SafeAssessmentContent :source="child.stem" :media="child.media"
              :mark-fill-blanks="child.type === 'FILL_BLANK'" :resolve-media="resolveMedia" /></section>
          <section class="result-answer"><h3>我的答案</h3>
            <ol v-if="answerFor(question)?.submittedAnswer.length"><li v-for="(answer, index) in answerFor(question)?.submittedAnswer" :key="index">
              <SafeAssessmentContent :source="answer" /></li></ol><p v-else>未作答</p></section>
          <section v-if="disclosure?.showPerQuestionGrade" class="result-grade-detail"><h3>评分</h3>
            <p>{{ answerFor(question)?.gradingStatus === 'PENDING_MANUAL' ? '等待教师人工批改' : answerFor(question)?.correct === true ? '作答正确' : answerFor(question)?.correct === false ? '作答有误' : '已评分' }}</p></section>
          <section v-if="disclosure?.showReferenceAnswer" class="result-reference"><h3>参考答案与解析</h3>
            <div v-for="(answer, index) in answerFor(question)?.correctAnswer ?? []" :key="index"><SafeAssessmentContent :source="answer" /></div>
            <SafeAssessmentContent :source="answerFor(question)?.analysis ?? ''" empty-text="教师未提供解析" /></section>
          <section v-else class="result-reference result-reference--pending"><h3>参考答案与解析</h3><p>教师暂未公布参考答案与解析。</p></section>
        </article>
      </section>
      <nav v-if="view === 'single'" class="question-navigation"><button type="button" :disabled="questionIndex === 0" @click="setView('single', questionIndex)">上一题</button>
        <button type="button" :disabled="questionIndex >= result.assessment.questions.length - 1" @click="setView('single', questionIndex + 2)">下一题</button></nav>
    </template>
  </main>
</template>
