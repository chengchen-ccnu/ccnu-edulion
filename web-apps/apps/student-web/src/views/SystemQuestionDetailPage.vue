<script setup lang="ts">
/**
 * 独立公共题详情：显示 ACTIVE SYSTEM 安全正文、选项、有序组合小问、知识点与可见媒体。
 * 页面没有答案字段或提交操作；媒体只通过当前根题的领域授权读取并在离页时由组件清理。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { QuestionDifficulty, QuestionGrade, QuestionMedia, QuestionType, SystemQuestion,
  SystemQuestionChild } from "@edu/api-client";
import { SafeAssessmentContent } from "@edu/ui";
import type { AssessmentMediaResolver } from "@edu/ui";
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";

import QuestionMediaAction from "../components/QuestionMediaAction.vue";
import { studentQuestionApi } from "../question-api";

const route = useRoute();
const question = ref<SystemQuestion | null>(null);
const loading = ref(false);
const errorMessage = ref("");
let controller: AbortController | null = null;

watch(() => route.params.questionId, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load(questionId: unknown) {
  controller?.abort();
  question.value = null;
  errorMessage.value = "";
  if (typeof questionId !== "string") return;
  const active = new AbortController();
  controller = active;
  loading.value = true;
  try {
    const result = await studentQuestionApi.detail(questionId, active.signal);
    if (!active.signal.aborted) question.value = result;
  } catch (error) {
    if (!active.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!active.signal.aborted) loading.value = false;
  }
}

const resolveMedia: AssessmentMediaResolver = async (mediaId, purpose, signal) => {
  if (!question.value) throw new Error("QUESTION_NOT_LOADED");
  return studentQuestionApi.mediaGrant(question.value.id, mediaId,
    purpose === "QUESTION_ATTACHMENT" ? "ATTACHMENT" : "INLINE", signal);
};

type QuestionContent = Pick<SystemQuestion, "stem" | "options" | "media"> | SystemQuestionChild;
/** 按正文引用去重补充题图；输入当前题/小问的媒体绑定，输出尚未在正文内展示的图片。 */
function looseImages(content: QuestionContent): QuestionMedia[] {
  const sources = [content.stem, ...content.options.map(option => option.content)];
  const direct = new Set(sources.flatMap(source => [...source.matchAll(/\{\{media:([0-9a-fA-F-]{36})}}/g)]
    .map(match => match[1]?.toLowerCase())));
  const legacyIndexes = new Set(sources.flatMap(source => [...source.matchAll(/\[图片(\d+)]/g)]
    .map(match => Number(match[1]) - 1)));
  const legacy = new Set([...content.media].filter(item => item.purpose === "QUESTION_IMAGE")
    .sort((left, right) => left.sequence - right.sequence).filter((_, index) => legacyIndexes.has(index))
    .map(item => item.mediaId.toLowerCase()));
  return content.media.filter(item => item.purpose === "QUESTION_IMAGE"
    && !direct.has(item.mediaId.toLowerCase()) && !legacy.has(item.mediaId.toLowerCase()));
}
function supplemental(content: QuestionContent) {
  return content.media.filter(item => item.purpose !== "QUESTION_IMAGE");
}
function gradeLabel(value: QuestionGrade) {
  return ({ ONE: "一年级", TWO: "二年级", THREE: "三年级", FOUR: "四年级", FIVE: "五年级", SIX: "六年级",
    SEVEN: "七年级", EIGHT: "八年级", NINE: "九年级" } as const)[value];
}
const typeLabels: Record<QuestionType, string> = {
  SINGLE_CHOICE: "单选题", MULTIPLE_CHOICE: "多选题", TRUE_FALSE: "判断题", FILL_BLANK: "填空题",
  SHORT_ANSWER: "简答题", COMPOSITE: "组合题"
};
const difficultyLabels: Record<QuestionDifficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };
function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线，无法读取这道公共题。";
  if (!(error instanceof GatewayRequestError)) return "公共题目暂时无法读取。";
  const labels: Record<number, string> = {
    401: "登录状态已失效，请重新登录。", 403: "当前账号没有学习空间访问能力。",
    404: "题目不存在、已归档或不是公共系统题。", 429: "请求过于频繁，请稍后重试。",
    503: "学习或媒体服务暂时不可用。"
  };
  return labels[error.status] ?? error.message;
}
</script>

<template>
  <main class="public-question-page">
    <RouterLink class="back-link" to="/practice">← 返回题库</RouterLink>
    <div v-if="loading" class="practice-loading" role="status">正在读取公共题目…</div>
    <section v-else-if="errorMessage" class="practice-empty" role="alert"><h2>无法打开题目</h2><p>{{ errorMessage }}</p>
      <button class="button button--quiet" type="button" @click="load(route.params.questionId)">重新加载</button></section>
    <article v-else-if="question" class="public-question-sheet">
      <header class="public-question-heading"><div><span class="section-kicker">PUBLIC QUESTION</span><h2>{{ typeLabels[question.type] }}</h2></div>
        <dl><div><dt>学段</dt><dd>{{ question.stage === "PRIMARY" ? "小学" : "初中" }}</dd></div>
          <div><dt>年级</dt><dd>{{ gradeLabel(question.grade) }}</dd></div><div><dt>难度</dt><dd>{{ difficultyLabels[question.difficulty] }}</dd></div></dl></header>
      <section class="public-question-content" aria-labelledby="public-question-stem"><h3 id="public-question-stem">题目</h3>
        <SafeAssessmentContent :source="question.stem" :media="question.media" :legacy-media="question.media"
          :mark-fill-blanks="question.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
        <ol v-if="question.options.length" class="public-question-options"><li v-for="option in question.options" :key="option.key">
          <strong>{{ option.key }}</strong><SafeAssessmentContent :source="option.content" :media="question.media"
            :legacy-media="question.media" :resolve-media="resolveMedia" /></li></ol>
        <div v-for="media in looseImages(question)" :key="media.mediaId" class="question-loose-media">
          <SafeAssessmentContent :source="`{{media:${media.mediaId}}}`" :media="[media]" :resolve-media="resolveMedia" />
        </div>
        <section v-for="child in question.children" :key="child.versionId" class="public-question-child"><h4>({{ child.sequence }})</h4>
          <SafeAssessmentContent :source="child.stem" :media="child.media" :legacy-media="child.media"
            :mark-fill-blanks="child.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
          <ol v-if="child.options.length" class="public-question-options"><li v-for="option in child.options" :key="option.key"><strong>{{ option.key }}</strong>
            <SafeAssessmentContent :source="option.content" :media="child.media" :legacy-media="child.media" :resolve-media="resolveMedia" /></li></ol>
          <div v-for="media in looseImages(child)" :key="media.mediaId" class="question-loose-media">
            <SafeAssessmentContent :source="`{{media:${media.mediaId}}}`" :media="[media]" :resolve-media="resolveMedia" /></div>
          <QuestionMediaAction v-for="media in supplemental(child)" :key="media.mediaId" :question-id="question.id"
            :media="media" :label="media.purpose === 'QUESTION_AUDIO' ? '小问音频' : '小问附件'" />
          <small v-if="child.knowledgePoints.length" class="public-question-child__knowledge">知识点：{{ child.knowledgePoints.map(item => item.name).join("、") }}</small>
        </section>
      </section>
      <QuestionMediaAction v-for="media in supplemental(question)" :key="media.mediaId" :question-id="question.id"
        :media="media" :label="media.purpose === 'QUESTION_AUDIO' ? '题目音频' : '题目附件'" />
      <dl class="public-question-knowledge"><div><dt>知识点</dt><dd>{{ question.knowledgePoints.map(item => item.name).join("、") || "未标注" }}</dd></div></dl>
      <aside class="question-bank-scope">当前可浏览题目，在线作答尚未开放。</aside>
    </article>
  </main>
</template>
