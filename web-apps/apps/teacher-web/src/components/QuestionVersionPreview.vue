<script setup lang="ts">
/**
 * 只读题目版本预览；父题与有序子题在存储中仍保持结构化，但界面按一张完整试题展示。
 * 题干内容、小问和题图共用题干区域；答案与解析分别统一展示，不重复拆成子题卡片。
 */
import type { QuestionVersion } from "@edu/api-client";

import QuestionMediaPreview from "./QuestionMediaPreview.vue";
import SafeRichText from "./SafeRichText.vue";

defineProps<{ version: QuestionVersion; historical?: boolean }>();

const typeLabels = {
  SINGLE_CHOICE: "单选题", MULTIPLE_CHOICE: "多选题", TRUE_FALSE: "判断题",
  FILL_BLANK: "填空题", SHORT_ANSWER: "简答题", COMPOSITE: "组合题"
} as const;
const difficultyLabels = { EASY: "简单", MEDIUM: "中等", HARD: "困难" } as const;
const purposeLabels = { QUESTION_IMAGE: "题目图片", QUESTION_AUDIO: "题目音频", QUESTION_ATTACHMENT: "题目附件" } as const;

type VersionMedia = QuestionVersion["media"][number];
type ContentVersion = Pick<QuestionVersion, "stem" | "options" | "answers" | "analysis" | "media">;

/** 存量 `[图片N]` 在题干/选项、解析和各答案内分别从 1 编号，这里按导入时的实际 media 追加顺序分段。 */
const orderedImages = (media: VersionMedia[]) => media.filter(item => item.purpose === "QUESTION_IMAGE")
  .sort((left, right) => left.sequence - right.sequence);
const legacyCount = (sources: string[]) => sources.reduce((maximum, source) => {
  const values = [...source.matchAll(/\[图片(\d+)]/g)].map(match => Number(match[1]));
  return Math.max(maximum, ...values, 0);
}, 0);
const stemLegacyCount = (version: ContentVersion) => legacyCount([version.stem, ...version.options.map(option => option.content)]);
const analysisLegacyCount = (version: ContentVersion) => legacyCount([version.analysis]);
const answerLegacyCount = (version: ContentVersion, index: number) => legacyCount([version.answers[index] ?? ""]);
function legacySlice(version: ContentVersion, start: number, count: number): VersionMedia[] {
  return orderedImages(version.media).slice(start, start + count);
}
function legacyStemMedia(version: ContentVersion) {
  return legacySlice(version, 0, stemLegacyCount(version));
}
function legacyAnalysisMedia(version: ContentVersion) {
  return legacySlice(version, stemLegacyCount(version), analysisLegacyCount(version));
}
function legacyAnswerMedia(version: ContentVersion, index: number) {
  const preceding = version.answers.slice(0, index).reduce((sum, _answer, at) => sum + answerLegacyCount(version, at), 0);
  return legacySlice(version, stemLegacyCount(version) + analysisLegacyCount(version) + preceding,
    answerLegacyCount(version, index));
}
function numberedLines(source: string): string {
  return source.replace(/([^\n])\s*(?=[（(][1-9][0-9]*[)）])/g, "$1\n\n");
}
function parentAnswers(version: QuestionVersion): string[] {
  const supplied = version.answers.filter(answer => answer.trim()).map(numberedLines);
  if (supplied.length) return supplied;
  return version.children.flatMap(child => child.answers.map(answer => `(${child.sequence}) ${answer}`));
}
function unplacedImages(version: ContentVersion): VersionMedia[] {
  const legacyPlaced = stemLegacyCount(version) + analysisLegacyCount(version)
    + version.answers.reduce((sum, _answer, index) => sum + answerLegacyCount(version, index), 0);
  const direct = new Set([version.stem, version.analysis, ...version.answers, ...version.options.map(option => option.content)]
    .flatMap(source => [...source.matchAll(/\{\{media:([0-9a-fA-F-]{36})}}/g)].map(match => match[1]?.toLowerCase())));
  return orderedImages(version.media).filter((media, index) => index >= legacyPlaced && !direct.has(media.mediaId.toLowerCase()));
}
const supplementalMedia = (version: ContentVersion) => version.media.filter(item => item.purpose !== "QUESTION_IMAGE");
</script>

<template>
  <article class="question-version-preview">
    <header><div><span class="status-pill status-pill--active">v{{ version.versionNumber }}</span>
      <strong>{{ typeLabels[version.type] }}</strong><span>{{ difficultyLabels[version.difficulty] }}</span></div>
      <time :datetime="version.createdAt">{{ new Date(version.createdAt).toLocaleString("zh-CN") }}</time></header>
    <section class="question-unified-stem"><h3>题干</h3>
      <SafeRichText :source="version.stem" :media="version.media" :legacy-media="legacyStemMedia(version)"
        :mark-fill-blanks="version.type === 'FILL_BLANK'" />
      <ol v-if="version.options.length" class="question-preview-options">
        <li v-for="option in version.options" :key="option.key"><strong>{{ option.key }}</strong>
          <SafeRichText :source="option.content" :media="version.media" :legacy-media="legacyStemMedia(version)" /></li>
      </ol>
      <div v-for="child in version.children" :key="child.versionId" class="question-unified-part">
        <strong>({{ child.sequence }})</strong>
        <SafeRichText :source="child.stem" :media="child.media" :legacy-media="legacyStemMedia(child)"
          :mark-fill-blanks="child.type === 'FILL_BLANK'" />
        <ol v-if="child.options.length" class="question-preview-options"><li v-for="option in child.options" :key="option.key">
          <strong>{{ option.key }}</strong><SafeRichText :source="option.content" :media="child.media"
            :legacy-media="legacyStemMedia(child)" /></li></ol>
        <QuestionMediaPreview v-for="media in unplacedImages(child)" :key="media.id" :media-id="media.mediaId"
          :purpose="media.purpose" label="小问附图" />
      </div>
      <QuestionMediaPreview v-for="media in unplacedImages(version)" :key="media.id" :media-id="media.mediaId"
        :purpose="media.purpose" label="题目附图" />
    </section>
    <section class="question-answer-preview"><h3>答案</h3>
      <div v-for="(answer, index) in parentAnswers(version)" :key="index"><SafeRichText :source="answer"
        :media="version.media" :legacy-media="legacyAnswerMedia(version, index)" /></div>
    </section>
    <section><h3>解析</h3>
      <SafeRichText v-if="version.analysis" :source="numberedLines(version.analysis)" :media="version.media"
        :legacy-media="legacyAnalysisMedia(version)" />
      <div v-else-if="version.children.some(child => child.analysis)" class="question-unified-analysis">
        <div v-for="child in version.children.filter(item => item.analysis)" :key="child.versionId" class="question-unified-part">
          <strong>({{ child.sequence }})</strong><SafeRichText :source="child.analysis" :media="child.media"
            :legacy-media="legacyAnalysisMedia(child)" /></div>
      </div>
      <p v-else class="scope-note">本版本未填写解析</p>
    </section>
    <dl class="question-preview-meta"><div><dt>学段</dt><dd>{{ version.stage === "PRIMARY" ? "小学" : "初中" }}</dd></div>
      <div><dt>年级</dt><dd>{{ ({ ONE: "一年级", TWO: "二年级", THREE: "三年级", FOUR: "四年级", FIVE: "五年级", SIX: "六年级", SEVEN: "七年级", EIGHT: "八年级", NINE: "九年级" } as const)[version.grade] }}</dd></div>
      <div><dt>知识点</dt><dd>{{ version.knowledgePoints.map(point => point.name).join("、") || "未绑定" }}</dd></div></dl>
    <div v-if="supplementalMedia(version).length" class="question-media-list">
      <QuestionMediaPreview v-for="media in supplementalMedia(version)" :key="media.id" :media-id="media.mediaId"
        :purpose="media.purpose" :label="purposeLabels[media.purpose]" />
    </div>
    <p v-if="historical" class="scope-note">当前展示的是不可变历史快照；查看不会改变题目当前版本或 revision。</p>
  </article>
</template>

<style scoped>
.question-unified-stem,.question-unified-analysis { display: grid; gap: 12px; }
.question-unified-part { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 4px 8px; align-items: start; }
.question-unified-part > .question-preview-options,.question-unified-part > .question-media-preview { grid-column: 2; }
</style>
