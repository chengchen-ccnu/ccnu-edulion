<script setup lang="ts">
/**
 * 管理员邀请单项面板：数据来自Gateway生成契约，不构造邀请列表。
 * 邮箱/理由/原写载荷仅在内存；202不代表送达，未知结果最多三次手动精确重放。
 * 发送与接受分别展示，409重读后由操作者决定，隐藏/离线/身份变化停止请求和轮询。
 */
import { createRequestScope, GatewayRequestError } from "@edu/api-client";
import type { AdminInvitation, AdminInvitationCreateRequest, AdminInvitationResendRequest } from "@edu/api-client";
import { buildAdminInvitationAcceptanceUrl, sanitizeAccountPermissionsQuery } from "@edu/auth";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from "vue-router";
import { adminInvitationsApi as api } from "../admin-invitations-api";
import { authStore } from "../auth";

const props = withDefaults(defineProps<{ pollIntervalMs?: number; maxPolls?: number }>(), { pollIntervalMs: 2000, maxPolls: 30 });
const router = useRouter(); const route = useRoute();
const scope = createRequestScope(); const readScope = createRequestScope();
const safeQuery = computed(() => sanitizeAccountPermissionsQuery(route.query));
const id = computed(() => safeQuery.value.invitationId ?? "");
const knownId = ref(""); const email = ref(""); const reason = ref(""); const resendReason = ref("");
const invitation = ref<AdminInvitation | null>(null); const busy = ref(false); const loading = ref(false);
const error = ref(""); const readError = ref(""); const fields = ref<Record<string, string>>({});
const denied = ref(false); const conflict = ref(false); const unknown = ref(false); const notice = ref("");
const offline = ref(!navigator.onLine); const hidden = ref(document.hidden); const coolingUntil = ref(0);
const now = ref(Date.now()); const polls = ref(0); const pane = ref<HTMLElement | null>(null);
const acceptUrl = buildAdminInvitationAcceptanceUrl();
/** 保存原逻辑请求及目标UUID；重放不得使用后来修改的表单、revision或新的clientRequestId。 */
type Snapshot = { sends: number } & ({ kind: "create"; body: AdminInvitationCreateRequest }
  | { kind: "resend"; id: string; body: AdminInvitationResendRequest });
const snapshot = ref<Snapshot | null>(null);
const allowed = computed(() => !denied.value && authStore.state.status === "authenticated"
  && authStore.state.currentUser?.roles.includes("ADMIN") && authStore.state.currentUser.permissions.includes("administration:access"));
const cooling = computed(() => now.value < coolingUntil.value);
const locked = computed(() => !allowed.value || offline.value || hidden.value || cooling.value || busy.value || unknown.value);
const canResend = computed(() => !locked.value && !conflict.value && !loading.value && !readError.value && !!invitation.value
  && invitation.value.acceptanceStatus !== "ACCEPTED" && invitation.value.sendAttempts < 6
  && now.value >= Date.parse(invitation.value.resendAfter));
const delivery = { PENDING: "等待SMTP结果", SENT: "SMTP已接受", FAILED: "邮件发送失败" };
const acceptance = { AWAITING: "等待接受", EXPIRED: "邀请已过期", ACCEPTED: "已接受并开户" };
let pollTimer: ReturnType<typeof setTimeout> | undefined; let clockTimer: ReturnType<typeof setInterval> | undefined;
let generation = 0; let disposed = false;

function stopPoll() { clearTimeout(pollTimer); }
/** 读写取消不等于后端回滚；原载荷保留到显式重放/离页，身份变化则立即清私有内存。 */
function suspend() { stopPoll(); generation++; readScope.cancel(); scope.cancel(); loading.value = false; }
function clearPrivate() {
  suspend(); invitation.value = null; snapshot.value = null; email.value = ""; reason.value = ""; resendReason.value = "";
  knownId.value = ""; fields.value = {}; error.value = ""; readError.value = ""; notice.value = "";
  busy.value = false; unknown.value = false; denied.value = true;
}
/** 不显示后端任意正文；字段仅保留契约中的邮箱/理由安全提示，429按Retry-After阻止手动请求。 */
function describe(failure: unknown) {
  if (failure instanceof Error && failure.message === "AUTHENTICATION_REQUIRED") {
    clearPrivate(); return "登录失效或缺少管理权限，请重新登录。";
  }
  if (failure instanceof GatewayRequestError) {
    if ([401, 403].includes(failure.status)) { clearPrivate(); return "登录失效或缺少管理权限，请重新登录。"; }
    fields.value = Object.fromEntries(Object.entries(failure.fieldErrors).filter(([key]) => ["email", "reason", "expectedRevision"].includes(key)));
    if (failure.status === 429) {
      coolingUntil.value = Date.now() + Math.max(1, failure.retryAfterSeconds ?? 30) * 1000; stopPoll();
      return "请求过于频繁，请等待 Retry-After 冷却结束后手动操作。";
    }
    return ({ 404: "邀请不存在或当前不可访问。", 409: "邮箱已绑定，或邀请版本/状态冲突。请重新查询核对，不能转换已有账号或覆盖旧决定。",
      422: "请求校验未通过，请核对邮箱、邀请ID及10～500字理由。" } as Record<number, string>)[failure.status]
      ?? "服务暂不可用，请稍后核对。";
  }
  return "请求未完成，网络、超时或页面中断不表示服务端未提交。";
}
/** PENDING每2秒最多30次，只查询，不自动发信；到限/错误/隐藏后等待手动查询。 */
function schedulePoll() {
  stopPoll();
  if (allowed.value && !offline.value && !hidden.value && !cooling.value && !busy.value && !readError.value
    && invitation.value?.deliveryStatus === "PENDING" && polls.value < Math.min(100, Math.max(1, props.maxPolls))) {
    pollTimer = setTimeout(() => { polls.value++; void queryInvitation(false); }, Math.max(500, props.pollIntervalMs));
  }
}
/** 按URL中的合法UUID读取当前事实；核对返回ID及取消代次，旧响应不能覆盖新邀请。 */
async function queryInvitation(manual = true) {
  if (!id.value || !allowed.value || offline.value || hidden.value || cooling.value || busy.value) return;
  stopPoll(); readScope.cancel(); const epoch = ++generation; const target = id.value;
  if (manual) polls.value = 0;
  loading.value = true; readError.value = "";
  try {
    const value = await readScope.run(15000, signal => api.get(target, signal));
    if (epoch !== generation || disposed) return;
    if (value.invitationId !== target) throw new Error("INVITATION_MISMATCH");
    invitation.value = value; conflict.value = false; schedulePoll();
  } catch (failure) {
    if (epoch === generation && !disposed) { readError.value = describe(failure); invitation.value = null; }
  } finally { if (epoch === generation) loading.value = false; }
}
async function lookup() {
  if (locked.value) return;
  if (!/^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(knownId.value.trim())) { readError.value = "请输入完整的邀请UUID。"; return; }
  if (knownId.value.trim() === id.value) await queryInvitation();
  else await router.push({ name: "admin-users", query: { panel: "invite", invitationId: knownId.value.trim() } });
}
function validReason(value: string) { return value.trim().length >= 10 && value.trim().length <= 500; }
/** 邮箱规范值和理由经明确确认后固定；邀请人不能为受邀者设置密码。 */
async function create() {
  if (locked.value) return;
  fields.value = {}; error.value = "";
  const normalized = email.value.trim().toLowerCase();
  if (normalized.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) { fields.value.email = "请填写有效邮箱。"; return; }
  if (!validReason(reason.value)) { fields.value.reason = "理由须为10～500字。"; return; }
  if (!window.confirm("确认邀请此邮箱所有者成为独立管理员？邮件发送后无法撤回，已有账号不能转换。")) return;
  snapshot.value = { kind: "create", sends: 0, body: { clientRequestId: crypto.randomUUID(), email: normalized, reason: reason.value.trim() } };
  await sendSnapshot();
}
/** 重发只用最新服务端revision并二次确认；总预算、冷却与凭证失效由服务端裁决。 */
async function resend() {
  if (!canResend.value || !invitation.value) return;
  if (!validReason(resendReason.value)) { fields.value.reason = "重发理由须为10～500字。"; return; }
  if (!window.confirm("确认重新发信？旧邮件凭证立即失效，新邀请有效期重新起算24小时。")) return;
  snapshot.value = { kind: "resend", id: invitation.value.invitationId, sends: 0, body: {
    clientRequestId: crypto.randomUUID(), expectedRevision: invitation.value.revision, reason: resendReason.value.trim() } };
  await sendSnapshot();
}
/** 同一逻辑写入最多三次手动发送；未知结果锁住新建/重发，不从查询404推断未创建。 */
async function sendSnapshot() {
  const saved = snapshot.value;
  if (!saved || saved.sends >= 3 || busy.value || !allowed.value || offline.value || hidden.value || cooling.value
    || saved.kind === "resend" && conflict.value) return;
  stopPoll(); saved.sends++; busy.value = true; unknown.value = true; error.value = ""; fields.value = {};
  try {
    const value = await scope.run(45000, signal => saved.kind === "create" ? api.create(saved.body, signal) : api.resend(saved.id, saved.body, signal));
    if (disposed || snapshot.value !== saved || !allowed.value) return;
    if (saved.kind === "resend" && value.invitationId !== saved.id) throw new Error("INVITATION_MISMATCH");
    unknown.value = false; snapshot.value = null; email.value = ""; reason.value = ""; resendReason.value = "";
    invitation.value = value; notice.value = "已收到202提交回执；邮件及接受状态分别以服务端事实为准。";
    busy.value = false;
    await router.replace({ name: "admin-users", query: { panel: "invite", invitationId: value.invitationId } });
    if (id.value === value.invitationId) { invitation.value = value; polls.value = 0; schedulePoll(); }
  } catch (failure) {
    if (disposed || snapshot.value !== saved) return;
    if (failure instanceof GatewayRequestError && failure.status < 500) {
      unknown.value = false; snapshot.value = null;
      // 新建409没有可读的邀请ID，不假装支持邮箱找回；只锁住重发的旧revision。
      if (failure.status === 409 && saved.kind === "resend") conflict.value = true;
    }
    error.value = describe(failure);
  } finally { if (!disposed) busy.value = false; }
}
async function copyAddress() {
  try { await navigator.clipboard.writeText(acceptUrl); notice.value = "已复制通用开户地址，不含任何邀请信息。"; }
  catch { notice.value = "无法自动复制，请选中下方通用地址手动复制。"; }
}
/** 离页会丢失敏感草稿与原请求；无邀请ID时不能自动找回，需从邮件取得ID。 */
function protectLeave() { return !(email.value || reason.value || resendReason.value || unknown.value || busy.value)
  || window.confirm("仍有内存草稿或未确认写入。离开后无法恢复原请求；无邀请ID时可从收件邮件取得ID再查询。确认离开？"); }
function beforeUnload(event: BeforeUnloadEvent) { if (email.value || reason.value || resendReason.value || unknown.value || busy.value) { event.preventDefault(); event.returnValue = ""; } }
onBeforeRouteLeave(protectLeave);
onBeforeRouteUpdate(to => {
  const next = sanitizeAccountPermissionsQuery(to.query);
  return next.panel === "invite" && next.invitationId === id.value || protectLeave();
});
watch(id, () => {
  suspend(); snapshot.value = null; unknown.value = false; busy.value = false;
  email.value = ""; reason.value = ""; resendReason.value = "";
  invitation.value = null; knownId.value = id.value; readError.value = ""; conflict.value = false;
  void queryInvitation(); if (id.value) void nextTick(() => pane.value?.focus());
}, { immediate: true });
watch(() => route.fullPath, () => {
  if (route.hash || JSON.stringify(route.query) !== JSON.stringify(safeQuery.value)) void router.replace({ name: "admin-users", query: safeQuery.value, hash: "" });
}, { immediate: true });
watch(() => `${authStore.state.status}:${authStore.state.currentUser?.id}:${authStore.state.currentUser?.roles.join(',')}:${authStore.state.currentUser?.permissions.join(',')}`,
  () => clearPrivate());
function visibilityChanged() { hidden.value = document.hidden; if (hidden.value) suspend(); }
function connectionChanged() { offline.value = !navigator.onLine; if (offline.value) suspend(); }
document.addEventListener("visibilitychange", visibilityChanged); window.addEventListener("online", connectionChanged);
window.addEventListener("offline", connectionChanged); window.addEventListener("beforeunload", beforeUnload);
clockTimer = setInterval(() => { now.value = Date.now(); }, 1000);
onBeforeUnmount(() => {
  disposed = true; clearPrivate(); clearInterval(clockTimer);
  document.removeEventListener("visibilitychange", visibilityChanged); window.removeEventListener("online", connectionChanged);
  window.removeEventListener("offline", connectionChanged); window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <main class="container account-permissions invitation-page">
    <p class="eyebrow">账号治理</p><h2>管理员邀请</h2>
    <p>邀请尚未绑定账号的邮箱所有者。管理员是独立身份；此处只按已知ID查询单条邀请。</p>
    <p v-if="!allowed" role="alert">登录失效或缺少管理权限，请重新登录。</p>
    <p v-if="offline" role="status">当前离线，已停止请求；恢复后请手动查询。</p>
    <p v-if="cooling" role="status">请求冷却中，还需 {{ Math.max(1, Math.ceil((coolingUntil - now) / 1000)) }} 秒。</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="allowed">
      <section class="account-detail" aria-label="通用开户地址">
        <h3>给受邀者的通用开户链接</h3><p>请另行提供此地址。收件人手动填写邮件中的邀请ID、邮箱与一次性凭证。</p>
        <a :href="acceptUrl" target="_blank" rel="noopener noreferrer">{{ acceptUrl }}</a>
        <button type="button" @click="copyAddress">复制通用开户地址</button>
        <p>链接不含邀请信息。SMTP已接受也不保证收件箱送达，请联系收件人核对。</p>
      </section>
      <section v-if="unknown || busy" class="account-warning" aria-label="邀请未决请求">
        <p>{{ busy ? '提交中，最多等待45秒；请勿重复发信。' : '写入结果未知，可能已创建或重发。只能精确重放原请求，不能自动另建邀请。' }}</p>
        <button :disabled="busy || offline || cooling || hidden || !snapshot || snapshot.sends >= 3" @click="sendSnapshot">重放原请求（{{ snapshot?.sends ?? 0 }}/3）</button>
        <p>页面关闭后原载荷不会保存。没有邀请ID时无法自动找回，可从收件邮件取得ID查询。</p>
      </section>
      <p v-if="error" role="alert" class="panel-error">{{ error }}</p>
      <p v-for="(value, key) in fields" :key="key" role="alert" class="panel-error">{{ key }}：{{ value }}</p>
      <div class="invitation-grid">
        <form class="account-detail" @submit.prevent="create">
          <h3>创建邀请</h3>
          <label>受邀邮箱<input v-model="email" type="email" maxlength="254" autocomplete="off" :disabled="locked" required></label>
          <label>邀请理由（10～500字）<textarea v-model="reason" minlength="10" maxlength="500" :disabled="locked" required /></label>
          <button :disabled="locked">确认创建邀请</button>
        </form>
        <section ref="pane" class="account-detail" tabindex="-1" aria-label="单条邀请状态">
          <h3>查询邀请</h3>
          <form @submit.prevent="lookup"><label>已知邀请ID<input v-model="knownId" autocomplete="off" :disabled="locked" required></label><button :disabled="locked || loading">查询邀请</button></form>
          <button v-if="id" :disabled="busy || loading || offline || hidden || cooling" @click="queryInvitation()">刷新邀请状态</button>
          <p v-if="loading" role="status" class="account-skeleton">正在读取邀请事实…</p>
          <p v-if="readError" role="alert" class="panel-error">{{ readError }}</p>
          <p v-if="!id && !loading">输入邮件或创建回执中的邀请ID。没有全量列表，也不支持按邮箱查找。</p>
          <template v-if="invitation">
            <dl class="account-facts"><dt>邀请ID</dt><dd>{{ invitation.invitationId }}</dd><dt>脱敏邮箱</dt><dd>{{ invitation.maskedEmail }}</dd>
              <dt>邮件发送状态</dt><dd>{{ delivery[invitation.deliveryStatus] }}</dd><dt>邀请接受状态</dt><dd>{{ acceptance[invitation.acceptanceStatus] }}</dd>
              <dt>已发送次数</dt><dd>{{ invitation.sendAttempts }}/6</dd><dt>邀请版本</dt><dd>{{ invitation.revision }}</dd>
              <dt>服务端到期时间</dt><dd>{{ new Date(invitation.expiresAt).toLocaleString() }}</dd>
              <dt>服务端可重发时间</dt><dd>{{ new Date(invitation.resendAfter).toLocaleString() }}</dd></dl>
            <p v-if="invitation.deliveryStatus === 'SENT'">SMTP服务已接受，不代表收件箱送达，也不代表已开户。</p>
            <p v-if="invitation.deliveryStatus === 'PENDING'">发送结果尚未确认，不承诺后台会再次发送。</p>
            <p v-if="invitation.deliveryStatus === 'PENDING' && polls >= Math.min(100, Math.max(1, props.maxPolls))">自动查询已达上限，请手动刷新。</p>
            <p v-if="invitation.sendAttempts >= 6">六次发送预算已耗尽。</p>
            <p v-if="invitation.acceptanceStatus === 'ACCEPTED'">邀请已消费，不可重发。</p>
            <form @submit.prevent="resend"><label>重发理由（10～500字）<textarea v-model="resendReason" minlength="10" maxlength="500" :disabled="locked" required /></label>
              <p>重发会立即作废旧凭证并重新起算24小时；至少60秒冷却，以服务端裁决为准。</p><button :disabled="!canResend">确认重发邀请</button></form>
          </template>
          <p v-if="conflict" role="alert">状态有冲突，请先刷新邀请状态，再由您决定是否重发。</p>
        </section>
      </div>
    </template>
  </main>
</template>
