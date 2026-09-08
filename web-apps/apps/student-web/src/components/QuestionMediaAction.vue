<script setup lang="ts">
/**
 * 公共题音频/附件读取组件。输入是根题 ID 和服务端可见媒体绑定；组件申请领域授权后换成本地 Blob URL，
 * 切题或离页会中止请求并撤销 URL，不记录或复用短期签名地址。
 */
import type { QuestionMedia } from "@edu/api-client";
import { onBeforeUnmount, ref, watch } from "vue";

import { studentQuestionApi } from "../question-api";

const props = defineProps<{ questionId: string; media: QuestionMedia; label: string }>();
const objectUrl = ref("");
const loading = ref(false);
const errorMessage = ref("");
let controller: AbortController | null = null;

watch(() => [props.questionId, props.media.mediaId, props.media.purpose], dispose);
onBeforeUnmount(dispose);

async function load() {
  if (loading.value || objectUrl.value) return;
  controller?.abort();
  const active = new AbortController();
  controller = active;
  loading.value = true;
  errorMessage.value = "";
  try {
    const grant = await studentQuestionApi.mediaGrant(props.questionId, props.media.mediaId,
      props.media.purpose === "QUESTION_ATTACHMENT" ? "ATTACHMENT" : "INLINE", active.signal);
    const response = await fetch(grant.url, { headers: grant.headers, signal: active.signal });
    if (!response.ok) throw new Error(`QUESTION_MEDIA_${response.status}`);
    const blob = await response.blob();
    if (!active.signal.aborted) objectUrl.value = URL.createObjectURL(blob);
  } catch {
    if (!active.signal.aborted) errorMessage.value = "媒体暂时无法读取，请稍后重试。";
  } finally {
    if (!active.signal.aborted) loading.value = false;
  }
}

/** 切题或离页中止授权/下载，并释放已创建的本地媒体地址和展示状态。 */
function dispose() {
  controller?.abort();
  controller = null;
  if (objectUrl.value) URL.revokeObjectURL(objectUrl.value);
  objectUrl.value = "";
  loading.value = false;
  errorMessage.value = "";
}
</script>

<template>
  <article class="public-question-media">
    <strong>{{ label }}</strong>
    <audio v-if="media.purpose === 'QUESTION_AUDIO' && objectUrl" :src="objectUrl" controls preload="metadata" />
    <a v-else-if="media.purpose === 'QUESTION_ATTACHMENT' && objectUrl" class="button button--quiet" :href="objectUrl" download>下载附件</a>
    <button v-else class="button button--quiet" type="button" :disabled="loading" @click="load">
      {{ loading ? "正在读取…" : media.purpose === "QUESTION_ATTACHMENT" ? "授权并下载" : "加载音频" }}
    </button>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  </article>
</template>
