<script setup lang="ts">
/** 课程状态消息的独立授权通用提示；只读取target安全状态/支持邮箱，渲染成功才标普通消息已读。 */
import type { NotificationTarget } from "@edu/api-client";
import { onBeforeUnmount, ref, watch } from "vue";
import { authStore } from "../auth";
import { operationsApi } from "../operations-api";
import { isUuid, readAfterRender } from "../notification-target";
import { usePortalFailure } from "../portal-state";
const props = defineProps<{ courseId: string; notificationId?: string }>();
const target = ref<NotificationTarget | null>(null);
const failure = usePortalFailure(), error = failure.error;
let controller: AbortController | null = null;
/** 再次授权消息与课程身份；通用状态成功显示后写精确revision已读，失败不泄露私有课程内容。 */
async function load() {
  controller?.abort(); target.value = null; error.value = "";
  if (!isUuid(props.notificationId) || !failure.allowed() || authStore.state.status !== "authenticated") return;
  const active = new AbortController(); controller = active;
  try {
    const result = await operationsApi.target(props.notificationId, active.signal);
    if (active.signal.aborted || result.targetType !== "COURSE_STATUS") return;
    if (result.courseId !== props.courseId) throw new Error("TARGET_MISMATCH");
    target.value = result;
    await readAfterRender(props.notificationId, result, active.signal);
  } catch (value) { if (!active.signal.aborted) { failure.fail(value); if ([401, 403, 404].includes((value as {status?: number}).status ?? 0)) target.value = null; } }
}
watch([() => props.courseId, () => props.notificationId, () => authStore.state.status, () => authStore.state.currentUser?.id], load, { immediate: true });
onBeforeUnmount(() => controller?.abort());
</script>
<template>
  <section v-if="target" class="course-status-notice" aria-labelledby="course-status-title"><span class="section-kicker">COURSE STATUS</span><h2 id="course-status-title">{{ target.paused ? '课程暂不可用' : '课程已恢复' }}</h2><p>{{ target.paused ? '课程目前暂停访问，请稍后再来。' : '课程已恢复访问，当前可学习内容以课程页面为准。' }}</p><p v-if="target.supportEmail">如需帮助，请联系 {{ target.supportEmail }}</p></section>
  <p v-if="error" class="feedback feedback--warning" role="alert">{{ error }} <button @click="load">重试消息状态</button></p>
</template>
