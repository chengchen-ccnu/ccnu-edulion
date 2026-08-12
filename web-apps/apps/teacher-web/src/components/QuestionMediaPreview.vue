<script setup lang="ts">
/**
 * 题目媒体短期预览；输入是稳定 mediaId/用途，先申请短期授权再下载为本地 Blob URL。
 * 离开页面或切换媒体时中止请求并 revoke Object URL，不保存、日志输出或复用签名 URL。
 */
import type { QuestionMediaPurpose } from "@edu/api-client";
import { onBeforeUnmount, onMounted, ref } from "vue";

import { teacherMediaApi } from "../media-api";

const props = defineProps<{ mediaId: string; purpose: QuestionMediaPurpose; label: string }>();
const objectUrl = ref("");
const loading = ref(false);
const errorMessage = ref("");
let controller: AbortController | null = null;

onMounted(() => { if (props.purpose !== "QUESTION_ATTACHMENT") void load(); });
onBeforeUnmount(dispose);

async function load() {
  if (loading.value || objectUrl.value) return;
  dispose();
  loading.value = true;
  errorMessage.value = "";
  controller = new AbortController();
  try {
    const grant = await teacherMediaApi.accessGrant(props.mediaId,
      props.purpose === "QUESTION_ATTACHMENT" ? "ATTACHMENT" : "INLINE", controller.signal);
    const response = await fetch(grant.url, { headers: grant.headers, signal: controller.signal });
    if (!response.ok) throw new Error(`MEDIA_PREVIEW_${response.status}`);
    objectUrl.value = URL.createObjectURL(await response.blob());
  } catch (error) {
    if (!controller?.signal.aborted) errorMessage.value = "媒体预览暂不可用，可稍后重试。";
  } finally { loading.value = false; }
}
function dispose() {
  controller?.abort();
  controller = null;
  if (objectUrl.value) URL.revokeObjectURL(objectUrl.value);
  objectUrl.value = "";
}
</script>

<template>
  <article class="question-media-preview">
    <header><strong>{{ label }}</strong><small>{{ mediaId }}</small></header>
    <img v-if="purpose === 'QUESTION_IMAGE' && objectUrl" :src="objectUrl" :alt="label">
    <audio v-else-if="purpose === 'QUESTION_AUDIO' && objectUrl" :src="objectUrl" controls preload="metadata" />
    <a v-else-if="purpose === 'QUESTION_ATTACHMENT' && objectUrl" class="button button--quiet" :href="objectUrl" download>下载附件</a>
    <button v-else class="button button--quiet" type="button" :disabled="loading" @click="load">
      {{ loading ? "正在申请授权…" : purpose === "QUESTION_ATTACHMENT" ? "授权并下载" : "重新加载预览" }}
    </button>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  </article>
</template>
