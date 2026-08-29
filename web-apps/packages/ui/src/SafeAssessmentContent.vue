<script setup lang="ts">
/**
 * 角色中立的测评富文本底层组件。调用方提供已授权的单媒体读取函数；组件只将短期 URL 换成 Blob URL，
 * 离页、内容变化或授权失败时中止请求并撤销临时 URL，不持久化或记录授权信息。
 */
import "katex/dist/katex.min.css";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";

import { renderAssessmentContent } from "./render-assessment-content";

export type AssessmentContentMedia = {
  mediaId: string;
  purpose: "QUESTION_IMAGE" | "QUESTION_AUDIO" | "QUESTION_ATTACHMENT";
  sequence: number;
};
export type AssessmentMediaGrant = { url: string; headers: Record<string, string>; expiresAt: string };
export type AssessmentMediaResolver = (mediaId: string, purpose: AssessmentContentMedia["purpose"],
  signal: AbortSignal) => Promise<AssessmentMediaGrant>;

const props = defineProps<{
  source: string;
  media?: AssessmentContentMedia[];
  legacyMedia?: AssessmentContentMedia[];
  markFillBlanks?: boolean;
  resolveMedia?: AssessmentMediaResolver;
  emptyText?: string;
}>();
const root = ref<HTMLElement | null>(null);
const normalizedSource = computed(() => props.source.replace(/\[图片(\d+)]/g, (marker, number: string) => {
  const item = props.legacyMedia?.filter(media => media.purpose === "QUESTION_IMAGE")
    .sort((left, right) => left.sequence - right.sequence)[Number(number) - 1];
  return item ? `{{media:${item.mediaId}}}` : marker;
}));
const html = computed(() => renderAssessmentContent(normalizedSource.value, props.markFillBlanks));
let controller: AbortController | null = null;
let objectUrls: string[] = [];

watch([html, () => props.media, () => props.legacyMedia, () => props.resolveMedia], hydrate, {
  immediate: true, flush: "post"
});
onBeforeUnmount(dispose);

async function hydrate() {
  dispose();
  await nextTick();
  if (!root.value) return;
  const placeholders = [...root.value.querySelectorAll<HTMLElement>("[data-assessment-media-id]")];
  if (!placeholders.length) return;
  const allowed = new Map((props.media ?? []).filter(item => item.purpose === "QUESTION_IMAGE")
    .map(item => [item.mediaId.toLowerCase(), item]));
  controller = new AbortController();
  const active = controller;
  const loaded = new Map<string, string>();
  for (const placeholder of placeholders) {
    const mediaId = placeholder.dataset.assessmentMediaId?.toLowerCase() ?? "";
    const item = allowed.get(mediaId);
    if (!item || !props.resolveMedia) { placeholder.textContent = "图片引用暂不可用"; continue; }
    try {
      let objectUrl = loaded.get(mediaId);
      if (!objectUrl) {
        const grant = await props.resolveMedia(item.mediaId, item.purpose, active.signal);
        const response = await fetch(grant.url, { headers: grant.headers, signal: active.signal });
        if (!response.ok) throw new Error(`ASSESSMENT_MEDIA_${response.status}`);
        objectUrl = URL.createObjectURL(await response.blob());
        loaded.set(mediaId, objectUrl); objectUrls.push(objectUrl);
      }
      const image = document.createElement("img");
      image.src = objectUrl; image.alt = "测评题图"; image.className = "assessment-inline-media__image";
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
  <!-- v-html 仅消费 renderAssessmentContent 的清洗结果。 -->
  <div v-if="source" ref="root" class="assessment-safe-content" v-html="html" />
  <p v-else class="assessment-safe-content assessment-safe-content--empty">{{ emptyText ?? "未填写" }}</p>
</template>

<style scoped>
.assessment-safe-content { min-width: 0; color: #26334d; line-height: 1.8; overflow-wrap: anywhere; }
.assessment-safe-content :deep(p:first-child) { margin-top: 0; }
.assessment-safe-content :deep(p:last-child) { margin-bottom: 0; }
.assessment-safe-content :deep(pre),.assessment-safe-content :deep(.katex-display) { max-width: 100%; overflow-x: auto; }
.assessment-safe-content :deep(.assessment-fill-blank) { display: inline-block; width: 5em; height: 1em; border-bottom: 2px solid currentColor; }
.assessment-safe-content :deep(.assessment-inline-media) { display: inline-flex; min-width: 2rem; min-height: 1rem; align-items: center; justify-content: center; }
.assessment-safe-content :deep(.assessment-inline-media__image) { max-width: min(100%, 36rem); max-height: 24rem; object-fit: contain; }
.assessment-safe-content--empty { color: #77839a; font-style: italic; }
</style>
