<script setup lang="ts">
/**
 * 角色中立的测评富文本底层组件。调用方提供已授权的单媒体读取函数；组件只将短期 URL 换成 Blob URL，
 * 离页、内容变化或授权失败时中止请求并撤销临时 URL，不持久化或记录授权信息。
 */
import "katex/dist/katex.min.css";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";

import { AssessmentMediaLoadError, loadAssessmentMedia } from "./assessment-media-loader";
import { renderAssessmentContent } from "./render-assessment-content";

export type AssessmentContentMedia = {
  mediaId: string;
  purpose: "QUESTION_IMAGE" | "QUESTION_AUDIO" | "QUESTION_ATTACHMENT";
  sequence: number;
};
export type { AssessmentMediaGrant } from "./assessment-media-loader";
import type { AssessmentMediaGrant } from "./assessment-media-loader";
export type AssessmentMediaResolver = (mediaId: string, purpose: AssessmentContentMedia["purpose"],
  signal: AbortSignal) => Promise<AssessmentMediaGrant>;

const props = defineProps<{
  source: string;
  media?: AssessmentContentMedia[];
  legacyMedia?: AssessmentContentMedia[];
  markFillBlanks?: boolean;
  resolveMedia?: AssessmentMediaResolver;
  /** 领域资源范围，例如 questionId；与 mediaId/固定 ORIGINAL/INLINE 共同组成安全缓存键。 */
  mediaCacheScope?: string;
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
const retryVersion = ref(0);

watch([html, () => props.media, () => props.legacyMedia, () => props.resolveMedia, retryVersion], hydrate, {
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
  await Promise.all(placeholders.map(async placeholder => {
    const mediaId = placeholder.dataset.assessmentMediaId?.toLowerCase() ?? "";
    const item = allowed.get(mediaId);
    if (!item || !props.resolveMedia) { placeholder.textContent = "图片引用暂不可用"; return; }
    try {
      let objectUrl = loaded.get(mediaId);
      let metrics = { authorizationMs: 0, downloadMs: 0, decodeMs: 0, status: 200, bytes: 0, cacheHit: true };
      if (!objectUrl) {
        const grantKey = `${props.mediaCacheScope ?? "assessment"}:${item.mediaId.toLowerCase()}:ORIGINAL:INLINE`;
        const result = await loadAssessmentMedia(grantKey, async sharedSignal => {
          return props.resolveMedia!(item.mediaId, item.purpose, sharedSignal);
        }, active.signal);
        metrics = result.metrics;
        objectUrl = URL.createObjectURL(result.blob);
        loaded.set(mediaId, objectUrl); objectUrls.push(objectUrl);
      }
      const image = document.createElement("img");
      image.src = objectUrl; image.alt = "测评题图"; image.className = "assessment-inline-media__image";
      placeholder.replaceChildren(image);
      const decodeStarted = performance.now();
      try { await image.decode?.(); } catch { /* 解码失败由浏览器替代文本兜底，不触发网络重试。 */ }
      const decodeMs = performance.now() - decodeStarted;
      // 仅暴露不含授权地址的性能摘要，便于浏览器验收区分授权、下载与解码阶段。
      Object.assign(placeholder.dataset, {
        assessmentAuthorizationMs: metrics.authorizationMs.toFixed(1),
        assessmentDownloadMs: metrics.downloadMs.toFixed(1),
        assessmentDecodeMs: decodeMs.toFixed(1),
        assessmentResponseStatus: String(metrics.status),
        assessmentResponseBytes: String(metrics.bytes),
        assessmentCacheHit: String(metrics.cacheHit)
      });
      root.value?.dispatchEvent(new CustomEvent("assessment-media-metric", { bubbles: true, detail: {
        mediaId: item.mediaId, ...metrics, decodeMs
      } }));
    } catch (error) {
      if (!active.signal.aborted) renderFailure(placeholder, error);
    }
  }));
}

function renderFailure(placeholder: HTMLElement, error: unknown) {
  const kind = error instanceof AssessmentMediaLoadError ? error.kind : "MEDIA_UNAVAILABLE";
  placeholder.dataset.assessmentFailureKind = kind;
  if (error instanceof AssessmentMediaLoadError && error.status) {
    placeholder.dataset.assessmentResponseStatus = String(error.status);
  }
  if (error instanceof AssessmentMediaLoadError && error.code) {
    placeholder.dataset.assessmentFailureCode = error.code;
  }
  const labels = { AUTHORIZATION_FAILED: "题图授权失败", RATE_LIMITED: "题图请求过于频繁", SERVICE_UNAVAILABLE: "题图服务暂不可用",
    NETWORK_FAILED: "题图网络加载失败", ACCESS_EXPIRED: "题图授权已过期", MEDIA_UNAVAILABLE: "题图暂不可用", CANCELLED: "题图加载已取消" } as const;
  placeholder.replaceChildren(document.createTextNode(labels[kind]));
  if (kind === "CANCELLED") return;
  const button = document.createElement("button"); button.type = "button"; button.className = "assessment-inline-media__retry";
  button.textContent = "重试"; button.addEventListener("click", () => { retryVersion.value += 1; }, { once: true });
  placeholder.append(" ", button);
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
