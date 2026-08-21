<script setup lang="ts">
/** 只读题目版本预览；输入是 T09B 不可变版本投影，父题和有序子题保持结构化展示。 */
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
</script>

<template>
  <article class="question-version-preview">
    <header><div><span class="status-pill status-pill--active">v{{ version.versionNumber }}</span>
      <strong>{{ typeLabels[version.type] }}</strong><span>{{ difficultyLabels[version.difficulty] }}</span></div>
      <time :datetime="version.createdAt">{{ new Date(version.createdAt).toLocaleString("zh-CN") }}</time></header>
    <section><h3>题干</h3><SafeRichText :source="version.stem" /></section>
    <ol v-if="version.options.length" class="question-preview-options">
      <li v-for="option in version.options" :key="option.key"><strong>{{ option.key }}</strong><SafeRichText :source="option.content" /></li>
    </ol>
    <section class="question-answer-preview"><h3>答案</h3>
      <div v-for="(answer, index) in version.answers" :key="index"><SafeRichText :source="answer" /></div>
    </section>
    <section><h3>解析</h3><SafeRichText :source="version.analysis" empty-text="本版本未填写解析" /></section>
    <dl class="question-preview-meta"><div><dt>学段</dt><dd>{{ version.stage === "PRIMARY" ? "小学" : "初中" }}</dd></div>
      <div><dt>年级</dt><dd>{{ ({ ONE: "一年级", TWO: "二年级", THREE: "三年级", FOUR: "四年级", FIVE: "五年级", SIX: "六年级", SEVEN: "七年级", EIGHT: "八年级", NINE: "九年级" } as const)[version.grade] }}</dd></div>
      <div><dt>知识点</dt><dd>{{ version.knowledgePoints.map(point => point.name).join("、") || "未绑定" }}</dd></div></dl>
    <div v-if="version.media.length" class="question-media-list">
      <QuestionMediaPreview v-for="media in version.media" :key="media.id" :media-id="media.mediaId"
        :purpose="media.purpose" :label="purposeLabels[media.purpose]" />
    </div>
    <section v-if="version.children.length" class="question-preview-children"><h3>有序子题</h3>
      <article v-for="child in version.children" :key="child.versionId">
        <header><strong>第 {{ child.sequence }} 题 · {{ typeLabels[child.type] }}</strong><span>v{{ child.versionNumber }}</span></header>
        <SafeRichText :source="child.stem" />
        <ol v-if="child.options.length"><li v-for="option in child.options" :key="option.key"><strong>{{ option.key }}</strong>
          <SafeRichText :source="option.content" /></li></ol>
        <div class="question-answer-preview"><strong>答案：</strong>
          <SafeRichText v-for="(answer, index) in child.answers" :key="index" :source="answer" />
        </div>
        <div><strong>解析</strong><SafeRichText :source="child.analysis" empty-text="未填写解析" /></div>
      </article>
    </section>
    <p v-if="historical" class="scope-note">当前展示的是不可变历史快照；查看不会改变题目当前版本或 revision。</p>
  </article>
</template>
