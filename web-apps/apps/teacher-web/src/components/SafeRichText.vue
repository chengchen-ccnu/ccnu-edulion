<script setup lang="ts">
/**
 * 安全题目内容展示组件；props 是服务端 Markdown/LaTeX 原文，DOM 只接收统一清洗函数的输出。
 * 媒体不从富文本 URL 加载；只有同一题目版本 media 列表中的 QUESTION_IMAGE
 * 才能通过稳定 mediaId 占位符申请短期授权并内联展示。
 */
import "katex/dist/katex.min.css";
import type { QuestionMediaPurpose } from "@edu/api-client";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";

import { teacherMediaApi } from "../media-api";
import { renderSafeQuestionHtml } from "../render-rich-text";

type InlineMedia = { mediaId: string; purpose: QuestionMediaPurpose; sequence: number };
const props = defineProps<{ source: string; emptyText?: string; media?: InlineMedia[]; legacyMedia?: InlineMedia[] }>();
const root = ref<HTMLElement | null>(null);
const sourceWithLegacyImages = computed(() => props.source.replace(/\[图片(\d+)]/g, (marker, number: string) => {
  const media = props.legacyMedia?.filter(item => item.purpose === "QUESTION_IMAGE")
    .sort((left, right) => left.sequence - right.sequence)[Number(number) - 1];
  return media ? `{{media:${media.mediaId}}}` : marker;
}));
const html = computed(() => renderSafeQuestionHtml(sourceWithLegacyImages.value));
let controller: AbortController | null = null;
let objectUrls: string[] = [];

watch([html, () => props.media, () => props.legacyMedia], hydrateInlineImages, { immediate: true, flush: "post" });
onBeforeUnmount(dispose);

/** 仅为当前版本已绑定图片申请授权，把 Blob URL 写入清洗后的占位元素。 */
async function hydrateInlineImages() {
  dispose();
  await nextTick();
  if (!root.value) return;
  const placeholders = [...root.value.querySelectorAll<HTMLElement>("[data-question-media-id]")];
  if (!placeholders.length) return;
  const allowed = new Map((props.media ?? []).filter(item => item.purpose === "QUESTION_IMAGE")
    .map(item => [item.mediaId.toLowerCase(), item]));
  controller = new AbortController();
  const active = controller;
  const loaded = new Map<string, string>();
  for (const placeholder of placeholders) {
    const mediaId = placeholder.dataset.questionMediaId?.toLowerCase() ?? "";
    if (!allowed.has(mediaId)) { placeholder.textContent = "图片引用无效"; continue; }
    try {
      let objectUrl = loaded.get(mediaId);
      if (!objectUrl) {
        const grant = await teacherMediaApi.accessGrant(mediaId, "INLINE", active.signal);
        const response = await fetch(grant.url, { headers: grant.headers, signal: active.signal });
        if (!response.ok) throw new Error(`INLINE_MEDIA_${response.status}`);
        objectUrl = URL.createObjectURL(await response.blob());
        loaded.set(mediaId, objectUrl); objectUrls.push(objectUrl);
      }
      const image = document.createElement("img");
      image.src = objectUrl; image.alt = "题目内图片"; image.className = "question-inline-media__image";
      placeholder.replaceChildren(image);
    } catch {
      if (!active.signal.aborted) placeholder.textContent = "图片暂不可用";
    }
  }
}

function dispose() {
  controller?.abort(); controller = null;
  objectUrls.forEach(url => URL.revokeObjectURL(url)); objectUrls = [];
}
</script>

<template>
  <!-- 此处的 v-html 只消费 renderSafeQuestionHtml 经 DOMPurify 清洗后的结果。 -->
  <div v-if="source" ref="root" class="safe-rich-text" v-html="html" />
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
.safe-rich-text :deep(.question-inline-media) { display: inline-flex; min-width: 1.5em; min-height: 1em; align-items: center; justify-content: center; margin: 0 .12em; color: #6c7890; vertical-align: middle; }
.safe-rich-text :deep(.question-inline-media__image) { display: inline-block; max-width: min(100%, 36rem); max-height: 24rem; object-fit: contain; vertical-align: middle; }
.safe-rich-text--empty { color: #8a95a9; font-style: italic; }
</style>
