<script setup lang="ts">
/**
 * 课程介绍后独立的ACTIVE报名公告区。输入仅课程ID和可选定位ID，服务端再次裁决权限。
 * 最新未确认取服务端摘要；详情深链独立读取；只有显式点击才确认当前revision，409刷新后再次确认。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AnnouncementPage, CourseAnnouncement } from "@edu/api-client";
import { invalidateNotificationCounts } from "@edu/auth";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { authStore } from "../auth";
import { operationsApi } from "../operations-api";
import { dateLabel, usePortalFailure } from "../portal-state";
import { isUuid } from "../notification-target";

const props = defineProps<{ courseId: string; announcementId?: string }>();
const data = ref<AnnouncementPage | null>(null), located = ref<CourseAnnouncement | null>(null);
const loading = ref(false), busy = ref(false), expanded = ref(false);
const page = ref(1);
const denied = ref(false);
const failure = usePortalFailure(), actionFailure = usePortalFailure();
const error = failure.error, actionError = actionFailure.error;
const notice = ref("");
let controller: AbortController | null = null, commandController: AbortController | null = null;
const featured = computed(() => data.value?.latestUnconfirmed ?? located.value ?? null);
const others = computed(() => {
  const items = located.value ? [located.value, ...(data.value?.items ?? [])] : data.value?.items ?? [];
  return items.filter((item, index) => item.id !== featured.value?.id && items.findIndex(other => other.id === item.id) === index);
});
// 同逻辑确认失败后保留请求ID，409读取新版本时才创建新的明确确认命令。
let command: { id: string; revision: number; requestId: string } | null = null;

/** 切页/课程/身份清空受保护正文；退课、撤回和授权变化后旧内容不能继续展示。 */
async function load() {
  if (!failure.allowed()) return;
  controller?.abort(); const active = new AbortController(); controller = active;
  loading.value = true; error.value = ""; data.value = null; located.value = null; denied.value = false;
  try {
    const result = await operationsApi.announcements(props.courseId, page.value, active.signal);
    if (!active.signal.aborted) data.value = result;
    if (props.announcementId) {
      if (!isUuid(props.announcementId)) throw new Error("INVALID_ANNOUNCEMENT");
      const detail = await operationsApi.announcement(props.courseId, props.announcementId, active.signal);
      if (!active.signal.aborted) {
        located.value = detail; if (detail.id !== featured.value?.id) expanded.value = true; await focusAnnouncement(detail.id);
      }
    }
  } catch (value) {
    if (!active.signal.aborted) {
      failure.fail(value);
      if (value instanceof GatewayRequestError && [401, 403, 404].includes(value.status)) {
        data.value = null; located.value = null; denied.value = true;
      }
    }
  } finally { if (!active.signal.aborted) loading.value = false; }
}
async function focusAnnouncement(id: string) {
  await nextTick(); if (controller?.signal.aborted) return;
  document.getElementById(`announcement-${id}`)?.focus({ preventScroll: false });
}
/** 写命令只绑定学生眼前版本；冲突不自动确认新版，失败不改未读计数。 */
async function confirm(item: CourseAnnouncement) {
  if (busy.value || !actionFailure.allowed()) return;
  commandController?.abort(); const active = new AbortController(); commandController = active;
  if (!command || command.id !== item.id || command.revision !== item.revision)
    command = { id: item.id, revision: item.revision, requestId: crypto.randomUUID() };
  busy.value = true; actionError.value = ""; notice.value = "";
  try {
    await operationsApi.confirm(props.courseId, item.id,
      { clientRequestId: command.requestId, expectedRevision: command.revision }, active.signal);
    if (!active.signal.aborted) { command = null; invalidateNotificationCounts(); notice.value = "已确认这条公告。"; await load(); }
  } catch (value) {
    if (!active.signal.aborted) {
      actionFailure.fail(value);
      if (value instanceof GatewayRequestError && value.status === 409) {
        command = null; await load(); notice.value = "公告内容已更新，请阅读最新内容后再次点击“我知道了”。";
      } else if (value instanceof GatewayRequestError && [401, 403, 404].includes(value.status)) {
        data.value = null; located.value = null; denied.value = true;
      }
    }
  } finally {
    if (!active.signal.aborted) { busy.value = false; await nextTick(); document.getElementById("announcements-heading")?.focus(); }
  }
}
function visible() { if (!document.hidden) void load(); }
watch([() => props.courseId, () => props.announcementId, () => authStore.state.currentUser?.id], () => {
  commandController?.abort(); busy.value = false; command = null; page.value = 1; expanded.value = false;
  actionError.value = ""; notice.value = ""; void load();
}, { immediate: true });
onMounted(() => document.addEventListener("visibilitychange", visible));
onBeforeUnmount(() => { controller?.abort(); commandController?.abort(); document.removeEventListener("visibilitychange", visible); });
</script>

<template>
  <section class="announcements-panel" aria-labelledby="announcements-heading">
    <header class="portal-heading"><div><span class="section-kicker">COURSE UPDATES</span><h3 id="announcements-heading" tabindex="-1">课程公告</h3></div><span v-if="data?.unconfirmedCount" class="announcement-count">{{ data.unconfirmedCount }} 条待确认</span></header>
    <p v-if="loading" role="status">正在读取课程公告…</p>
    <p v-if="error" class="feedback feedback--warning" role="alert">{{ error }} <button :disabled="loading" @click="load">重新加载公告</button></p>
    <p v-if="actionError" class="feedback feedback--warning" role="alert">{{ actionError }}</p>
    <p v-if="notice" class="feedback feedback--success" role="status">{{ notice }}</p>
    <article v-if="featured && !denied" :id="`announcement-${featured.id}`" tabindex="-1" class="announcement-featured" :class="{ 'is-confirmed': featured.confirmed || !featured.confirmationRequired }">
      <span class="announcement-eyebrow">{{ featured.confirmationRequired && !featured.confirmed ? '请留意这条公告' : '课程公告' }}</span><h4>{{ featured.title }}</h4><time :datetime="featured.publishedAt">{{ dateLabel(featured.publishedAt) }}</time><p class="announcement-body">{{ featured.body }}</p>
      <button v-if="featured.confirmationRequired && !featured.confirmed" class="button button--primary" :disabled="busy || loading" @click="confirm(featured)">我知道了</button><small v-else>{{ featured.confirmed ? '已确认' : '无需确认' }}</small>
      <p v-if="data && data.unconfirmedCount > 1" class="announcement-more">还有 {{ data.unconfirmedCount - (featured.confirmationRequired && !featured.confirmed ? 1 : 0) }} 条未确认公告</p>
    </article>
    <button v-if="data && data.total > (featured ? 1 : 0)" class="announcement-toggle" :aria-expanded="expanded" aria-controls="other-announcements" @click="expanded = !expanded">{{ expanded ? '收起其他公告' : '展开其他公告' }} · {{ data.total - (featured ? 1 : 0) }}</button>
    <div v-if="expanded && !denied" id="other-announcements"><article v-for="item in others" :id="`announcement-${item.id}`" :key="item.id" tabindex="-1" class="announcement-item"><h4>{{ item.title }}</h4><time :datetime="item.publishedAt">{{ dateLabel(item.publishedAt) }}</time><p class="announcement-body">{{ item.body }}</p><button v-if="item.confirmationRequired && !item.confirmed" class="button button--quiet" :disabled="busy || loading" @click="confirm(item)">我知道了</button><small v-else>{{ item.confirmed ? '已确认' : '无需确认' }}</small></article>
      <nav v-if="data && data.total > data.size" class="pagination" aria-label="公告分页"><button :disabled="loading || page <= 1" @click="page--; load()">上一页</button><span>第 {{ page }} 页</span><button :disabled="loading || page * data.size >= data.total" @click="page++; load()">下一页</button></nav>
    </div>
    <p v-if="!loading && !error && data?.total === 0" class="portal-empty">老师暂未发布课程公告。</p>
  </section>
</template>
