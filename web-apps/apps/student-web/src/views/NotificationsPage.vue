<script setup lang="ts">
/** 唯一站内消息页：四类安全摘要分页；进入前再次验权，公告不在此确认，普通消息交由目标成功加载后已读。 */
import type { NotificationPage, Notification } from "@edu/api-client";
import { invalidateNotificationCounts } from "@edu/auth";
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStore } from "../auth";
import { operationsApi } from "../operations-api";
import { notificationRoute } from "../notification-target";
import { dateLabel, pageNumber, usePortalFailure } from "../portal-state";

const route = useRoute(), router = useRouter();
const data = ref<NotificationPage | null>(null), loading = ref(false), busy = ref(false);
const opening = ref("");
const note = ref("");
const failure = usePortalFailure(), actionFailure = usePortalFailure();
const error = failure.error, actionError = actionFailure.error;
let controller: AbortController | null = null, actionController: AbortController | null = null;
const kindLabels: Record<Notification["kind"], string> = { COURSE_ANNOUNCEMENT: "课程公告", COURSE_TASK: "课程任务", TEACHER_REVIEW: "教师申请", COURSE_AVAILABILITY: "课程状态" };
const actionLabels: Record<Notification["action"], string> = { ANNOUNCEMENT: "待查看公告", OPENED: "任务开放", DEADLINE_CHANGED: "截止调整", ENDED: "任务结束", APPROVED: "审核通过", REJECTED: "审核未通过", PAUSED: "课程暂停", RESUMED: "课程恢复" };

/** 页码改变清空旧页；同页重试可保留安全摘要；账号切换立即清空并中止旧请求。 */
async function load() {
  if (authStore.state.status !== 'authenticated') return;
  if (!failure.allowed()) return;
  controller?.abort(); const active = new AbortController(); controller = active;
  const page = pageNumber(route.query.page);
  if (data.value?.page !== page) data.value = null;
  loading.value = true; error.value = "";
  try { const value = await operationsApi.notifications(page, active.signal); if (!active.signal.aborted) data.value = value; }
  catch (value) { if (!active.signal.aborted) { failure.fail(value); if ([401, 403, 404].includes((value as { status?: number }).status ?? 0)) data.value = null; } }
  finally { if (!active.signal.aborted) { loading.value = false; invalidateNotificationCounts(); } }
}
/** 打开前再次获取服务端授权目标；只导航固定路由，不在消息列表提前标已读。 */
async function open(message: Notification) {
  if (opening.value || busy.value || !actionFailure.allowed()) return;
  actionController?.abort(); const active = new AbortController(); actionController = active;
  opening.value = message.id; actionError.value = "";
  try {
    const target = await operationsApi.target(message.id, active.signal);
    if (!active.signal.aborted) await router.push(notificationRoute(target, message.id));
  } catch (value) { if (!active.signal.aborted) { actionFailure.fail(value); if ((value as { status?: number }).status === 404) await load(); } }
  finally { if (!active.signal.aborted) opening.value = ""; }
}
/** 全部已读只提交服务端既有命令；返回的公告数量是唯一剩余提示来源，不本地批量置零。 */
async function readAll() {
  if (busy.value || opening.value || !actionFailure.allowed()) return;
  const active = new AbortController(); actionController = active; busy.value = true; actionError.value = "";
  try {
    const result = await operationsApi.readAll(active.signal);
    if (!active.signal.aborted) {
      note.value = result.unconfirmedAnnouncementCount
        ? `普通消息已读，仍有 ${result.unconfirmedAnnouncementCount} 条公告需要进入课程点击“我知道了”。` : "普通消息已全部标为已读。";
      invalidateNotificationCounts(); await load();
    }
  } catch (value) { if (!active.signal.aborted) actionFailure.fail(value); }
  finally { if (!active.signal.aborted) busy.value = false; }
}
function setPage(page: number) { void router.push({ query: page > 1 ? { page: String(page) } : {} }); }
watch([() => route.query.page, () => authStore.state.currentUser?.id], () => { data.value = null; actionController?.abort(); busy.value = false; opening.value = ""; note.value = ""; actionError.value = ""; void load(); }, { immediate: true });
onBeforeUnmount(() => { controller?.abort(); actionController?.abort(); });
</script>

<template>
  <section class="notifications-page" aria-labelledby="messages-title">
    <header class="portal-heading"><div><span class="section-kicker">YOUR INBOX</span><h2 id="messages-title">消息</h2><p>课程的每个重要时刻，都在这里。</p></div><button class="button button--quiet" :disabled="busy || !!opening || !data" @click="readAll">{{ busy ? '正在处理…' : '全部标为已读' }}</button></header>
    <p class="message-explainer">公告需在课程页明确确认；打开消息不会代你点击“我知道了”。</p>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }} <button :disabled="loading" @click="load">重新加载</button></p>
    <p v-if="actionError" class="feedback feedback--error" role="alert">{{ actionError }}</p>
    <p v-if="note" class="feedback feedback--success" role="status">{{ note }}</p>
    <div v-if="loading && !data" class="portal-skeleton" role="status">正在读取消息…</div>
    <ol v-else-if="data?.items.length" class="message-list"><li v-for="message in data.items" :key="message.id" :class="{ 'message-unread': message.unread }">
      <span class="message-symbol" aria-hidden="true">{{ { COURSE_ANNOUNCEMENT: '≡', COURSE_TASK: '✓', TEACHER_REVIEW: '◇', COURSE_AVAILABILITY: '◷' }[message.kind] }}</span>
      <div class="message-copy"><div class="message-meta"><span>{{ kindLabels[message.kind] }}</span><span>{{ actionLabels[message.action] }}</span><time :datetime="message.occurredAt">{{ dateLabel(message.occurredAt) }}</time></div><h3>{{ message.title }}</h3><small>{{ message.confirmationRequired ? (message.unread ? '需进入课程确认' : '已确认') : message.unread ? '未读' : '已读' }}</small></div>
      <button class="button button--quiet" :disabled="!!opening || busy" @click="open(message)">{{ opening === message.id ? '正在打开…' : '查看' }} <span aria-hidden="true">→</span></button>
    </li></ol>
    <div v-else-if="!loading && !error" class="portal-empty"><span class="inbox-empty-mark" aria-hidden="true">✉</span><h3>暂时没有消息</h3><p>课程公告、任务、教师申请与课程状态消息会显示在这里。</p><RouterLink to="/courses">探索课程 →</RouterLink></div>
    <nav v-if="data && data.total > data.size" class="pagination" aria-label="消息分页"><button :disabled="loading || data.page <= 1" @click="setPage(data.page - 1)">上一页</button><span>第 {{ data.page }} 页</span><button :disabled="loading || data.page * data.size >= data.total" @click="setPage(data.page + 1)">下一页</button></nav>
  </section>
</template>
