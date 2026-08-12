<script setup lang="ts">
/**
 * 安全题目内容展示组件；props 是服务端 Markdown/LaTeX 原文，DOM 只接收统一清洗函数的输出。
 * 媒体不从富文本 URL 加载，而由独立的 mediaId 授权组件展示。
 */
import "katex/dist/katex.min.css";
import { computed } from "vue";

import { renderSafeQuestionHtml } from "../render-rich-text";

const props = defineProps<{ source: string; emptyText?: string }>();
const html = computed(() => renderSafeQuestionHtml(props.source));
</script>

<template>
  <!-- 此处的 v-html 只消费 renderSafeQuestionHtml 经 DOMPurify 清洗后的结果。 -->
  <div v-if="source" class="safe-rich-text" v-html="html" />
  <p v-else class="safe-rich-text safe-rich-text--empty">{{ emptyText ?? "未填写" }}</p>
</template>

<style scoped>
.safe-rich-text { min-width: 0; color: #26334d; font-size: .9rem; line-height: 1.8; overflow-wrap: anywhere; }
.safe-rich-text :deep(p:first-child) { margin-top: 0; }.safe-rich-text :deep(p:last-child) { margin-bottom: 0; }
.safe-rich-text :deep(pre) { max-width: 100%; padding: 12px; overflow: auto; background: #f2f5fa; border-radius: 8px; }
.safe-rich-text :deep(code) { padding: 2px 4px; background: #f2f5fa; border-radius: 4px; }
.safe-rich-text :deep(blockquote) { margin-left: 0; padding-left: 14px; color: #5d6980; border-left: 3px solid #b8c8e2; }
.safe-rich-text :deep(a) { color: var(--edu-color-primary); text-decoration: underline; }
.safe-rich-text :deep(.katex-display) { max-width: 100%; overflow-x: auto; overflow-y: hidden; }
.safe-rich-text--empty { color: #8a95a9; font-style: italic; }
</style>
