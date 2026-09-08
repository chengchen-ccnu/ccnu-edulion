<script setup lang="ts">
/**
 * 旧 `/practice/:assessmentId` 深链迁移页。只使用服务端 entry 投影解析课程归属和权限，
 * 成功后替换为课程任务或最后结果路由；失败时不从 URL、枚举或本地列表猜测 courseId。
 */
import { GatewayRequestError } from "@edu/api-client";
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const errorMessage = ref("");
let controller: AbortController | null = null;

watch(() => route.params.assessmentId, resolveEntry, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function resolveEntry(assessmentId: unknown) {
  controller?.abort();
  errorMessage.value = "";
  if (typeof assessmentId !== "string") return;
  const active = new AbortController();
  controller = active;
  loading.value = true;
  try {
    const entry = await studentAssessmentApi.assessmentEntry(assessmentId, active.signal);
    if (active.signal.aborted) return;
    const resultAvailable = entry.finalAttempt?.status !== undefined && entry.finalAttempt.status !== "DRAFT"
      && (entry.timeState === "CLOSED" || entry.attemptsUsed >= entry.maxAttempts);
    await router.replace({
      path: `/learn/${entry.courseId}/tasks/${entry.id}${resultAvailable ? "/result" : ""}`,
      query: entry.type === "PRACTICE" ? { type: "PRACTICE" } : undefined
    });
  } catch (error) {
    if (!active.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!active.signal.aborted) loading.value = false;
  }
}

function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线，无法安全确认这条旧链接的课程归属。";
  if (!(error instanceof GatewayRequestError)) return "旧练习链接暂时无法解析。";
  const labels: Record<number, string> = {
    401: "登录状态已失效，请重新登录。", 403: "你尚未报名该课程，或没有学习访问能力。",
    404: "练习不存在、未发布、未分配或不属于当前可访问课程。",
    429: "请求过于频繁，请稍后重试。", 503: "课程或学习服务暂时不可用。"
  };
  return labels[error.status] ?? error.message;
}
</script>

<template>
  <main class="legacy-practice-page">
    <div v-if="loading" class="practice-loading" role="status">正在安全确认课程练习归属…</div>
    <section v-else class="practice-empty" role="alert"><h2>无法迁移旧练习链接</h2><p>{{ errorMessage }}</p>
      <button class="button button--quiet" type="button" @click="resolveEntry(route.params.assessmentId)">重新检查</button>
      <RouterLink class="button button--quiet" to="/practice">返回题库</RouterLink></section>
  </main>
</template>
