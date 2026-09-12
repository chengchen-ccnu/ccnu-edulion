<script setup lang="ts">
/** 根据本人稳定 mediaId 临时授权并显示题图；URL 仅用于 Blob 抓取，切换或离页立即撤销。 */
import { onBeforeUnmount, ref, watch } from "vue";
import { accountSecurityApi } from "../account-security-api";
const props = defineProps<{ mediaId: string }>();
const source = ref(""); let controller: AbortController | null = null;
watch(() => props.mediaId, load, { immediate: true }); onBeforeUnmount(dispose);
async function load() { dispose(); const active = new AbortController(); controller = active; try { const grant = await accountSecurityApi.mediaAccessGrant(props.mediaId, active.signal); const response = await fetch(grant.url, { method: grant.method, headers: grant.headers, signal: active.signal, cache: "no-store" }); if (!response.ok) throw new Error(); const url = URL.createObjectURL(await response.blob()); if (!active.signal.aborted) source.value = url; else URL.revokeObjectURL(url); } catch { /* 文本消息仍可读，图片失败保持可感知替代文本。 */ } }
function dispose() { controller?.abort(); controller = null; if (source.value) URL.revokeObjectURL(source.value); source.value = ""; }
</script>
<template><img v-if="source" class="ai-question-image" :src="source" alt="学生上传的题图"><span v-else class="ai-question-image--missing">题图暂时无法读取</span></template>
