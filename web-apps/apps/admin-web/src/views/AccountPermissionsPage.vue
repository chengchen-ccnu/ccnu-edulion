<script setup lang="ts">
/**
 * 账号权限页消费脱敏分页、按需详情与最近命令；URL只存定位，关键词/理由/写入快照只在内存。
 * 四动作必须预览并二次确认；未知写入最多发送三次原载荷，协调查询有界且可取消。
 * IAM提交、Course确认和异步消息分别展示；身份变化清私有状态，离页清请求与全部计时器。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AccountPermissionAction, AccountPermissionCommand, AccountPermissionCommandRequest,
  AccountPermissionPreview, AdminUser, AdminUserPage, PermissionCoordinationRetryRequest } from "@edu/api-client";
import { sanitizeAccountPermissionsQuery } from "@edu/auth";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from "vue-router";
import { authStore } from "../auth";
import { accountPermissionsApi as api } from "../account-permissions-api";

const props = withDefaults(defineProps<{ pollIntervalMs?: number; maxPolls?: number }>(), { pollIntervalMs: 2000, maxPolls: 30 });
const route = useRoute(); const router = useRouter();
const query = computed(() => sanitizeAccountPermissionsQuery(route.query));
const page = computed(() => Number(query.value.page ?? 1));
const selectedId = computed(() => query.value.userId ?? "");
const labels: Record<AccountPermissionAction, string> = { REVOKE_TEACHING: "撤销教学能力", RESTORE_TEACHING: "恢复教学能力", BAN: "封禁账号", UNBAN: "解封账号" };
const coordinationLabels = { NOT_REQUIRED: "无需课程协调", PENDING: "课程协调处理中", FAILED: "课程协调失败", APPLIED: "Course已确认" };
const keywordInput = ref(""); const keyword = ref(""); const reason = ref(""); const confirmed = ref(false);
const list = ref<AdminUserPage | null>(null); const detail = ref<AdminUser | null>(null);
const command = ref<AccountPermissionCommand | null>(null); const preview = ref<AccountPermissionPreview | null>(null);
const latest = ref<AccountPermissionCommand | null>(null);
const listLoading = ref(false); const detailLoading = ref(false); const statusLoading = ref(false); const busy = ref(false);
const listError = ref(""); const detailError = ref(""); const statusError = ref(""); const writeError = ref("");
const fields = ref<Record<string, string>>({}); const notice = ref(""); const denied = ref(false); const cooling = ref(false);
const conflict = ref(false); const expired = ref(false); const unknown = ref(false); const polls = ref(0);
const reconciled = ref(false); const previewLoading = ref(false);
const offline = ref(!navigator.onLine); const hidden = ref(document.hidden);
const pane = ref<HTMLElement | null>(null); let returnFocus: HTMLElement | null = null;
/** 写命令和补偿各自保存原DTO；未知结果查询不能推断该请求是否提交，需管理员核对后明确结束。 */
type WriteSnapshot = { userId: string; sends: number } & (
  { kind: "change"; body: AccountPermissionCommandRequest } |
  { kind: "retry"; commandId: string; body: PermissionCoordinationRetryRequest });
const snapshot = ref<WriteSnapshot | null>(null);
const administrator = computed(() => authStore.state.currentUser?.roles.includes("ADMIN")
  && authStore.state.currentUser.permissions.includes("administration:access"));
const allowed = computed(() => administrator.value && !denied.value);
const unresolved = computed(() => unknown.value || busy.value);
const latestUnsettled = computed(() => !!latest.value && ["PENDING", "FAILED"].includes(latest.value.coordinationStatus));
const locked = computed(() => !allowed.value || offline.value || cooling.value || detailLoading.value || statusLoading.value
  || previewLoading.value || unresolved.value || conflict.value || !!statusError.value || !!detailError.value || latestUnsettled.value);
const pages = computed(() => Math.max(1, Math.ceil((list.value?.total ?? 0) / 20)));
const actions = computed<AccountPermissionAction[]>(() => {
  const user = detail.value; if (!user) return [];
  if (user.status === "DISABLED") return ["UNBAN"];
  if (user.roles.includes("ADMIN")) return user.id === authStore.state.currentUser?.id ? [] : ["BAN"];
  return user.permissions.includes("teaching:access") ? ["REVOKE_TEACHING", "BAN"] : ["RESTORE_TEACHING", "BAN"];
});
const canSubmit = computed(() => !locked.value && !!preview.value && !expired.value && confirmed.value
  && reason.value.trim().length >= 10 && reason.value.trim().length <= 500);
const canCompensate = computed(() => allowed.value && !offline.value && !cooling.value && !unresolved.value && !conflict.value
  && !statusLoading.value && !statusError.value && command.value?.coordinationStatus === "FAILED"
  && command.value.attempts >= 3 && command.value.attempts < command.value.maxAttempts);
const canResend = computed(() => allowed.value && !offline.value && !cooling.value && !busy.value && unknown.value
  && !conflict.value && !!snapshot.value && snapshot.value.sends < 3);
let listController: AbortController | undefined; let readController: AbortController | undefined;
let pollController: AbortController | undefined; let writeController: AbortController | undefined; let previewController: AbortController | undefined;
let pollTimer: ReturnType<typeof setTimeout> | undefined; let expiryTimer: ReturnType<typeof setTimeout> | undefined;
let cooldownTimer: ReturnType<typeof setTimeout> | undefined;
const requestTimers = new Set<ReturnType<typeof setTimeout>>();

/** 每次HTTP请求最多等待15秒；取消立即结束等待并清理计时器，超时写请求保留未知结果。 */
async function bounded<T>(controller: AbortController, send: () => Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let abort: (() => void) | undefined;
  try {
    return await Promise.race([send(), new Promise<T>((_, reject) => {
      abort = () => reject(new Error("CANCELLED")); controller.signal.addEventListener("abort", abort, { once: true });
      timer = setTimeout(() => { reject(new Error("TIMEOUT")); controller.abort(); }, 15000); requestTimers.add(timer);
    })]);
  } finally {
    if (timer) { clearTimeout(timer); requestTimers.delete(timer); }
    if (abort) controller.signal.removeEventListener("abort", abort);
  }
}
function stopPoll() { clearTimeout(pollTimer); pollController?.abort(); statusLoading.value = false; }
function clearPreview() { previewController?.abort(); clearTimeout(expiryTimer); preview.value = null; confirmed.value = false; expired.value = false; previewLoading.value = false; }
/** 清除私有事实和原载荷；在途/未知警示保留，旧身份没有重试资格，旧响应按取消信号丢弃。 */
function clearProtected() {
  if (busy.value) unknown.value = true;
  stopPoll(); listController?.abort(); readController?.abort(); writeController?.abort(); clearPreview(); clearTimeout(cooldownTimer);
  list.value = null; detail.value = null; command.value = null; latest.value = null; snapshot.value = null;
  reason.value = ""; keyword.value = ""; keywordInput.value = ""; fields.value = {}; notice.value = "";
  busy.value = false; listLoading.value = false; detailLoading.value = false; cooling.value = false; denied.value = true;
}
function isDenied(error: unknown) {
  return error instanceof GatewayRequestError && [401, 403].includes(error.status)
    || error instanceof Error && error.message === "AUTHENTICATION_REQUIRED";
}
/** 用稳定中文说明错误；429尊重Retry-After只恢复手动操作，不自动重发写命令。 */
function describeError(error: unknown): string {
  if (isDenied(error)) { clearProtected(); return "登录失效或当前账号没有管理权限，请重新登录。"; }
  if (error instanceof GatewayRequestError) {
    fields.value = error.fieldErrors;
    if (error.status === 429) {
      const seconds = Math.max(1, error.retryAfterSeconds ?? 30); cooling.value = true; stopPoll(); clearTimeout(cooldownTimer);
      cooldownTimer = setTimeout(() => { cooling.value = false; }, Math.min(seconds * 1000, 2147483647));
      return `请求过于频繁，请至少等待 ${seconds} 秒后手动重试。`;
    }
    return ({ 404: "账号或命令不存在，或当前不可访问。", 409: "版本、影响或协调状态已变化，请刷新核对，不能覆盖旧决定。",
      422: "请求校验失败，请检查字段；首次教学授予必须先通过教师审核。", 503: "课程、学习或安全依赖暂不可用，无法确认真实影响。" } as Record<number, string>)[error.status]
      ?? "服务暂不可用，请稍后重试。";
  }
  return error instanceof Error && error.message === "TIMEOUT" ? "请求超时，请查询服务端事实后核对。" : "网络不可用或请求未完成，请恢复网络后重试。";
}
function stateLabel(user: AdminUser) {
  if (user.roles.includes("ADMIN")) return user.status === "DISABLED" ? "管理员 · 已封禁" : "管理员";
  return user.status === "DISABLED" ? "封禁（无有效能力）" : user.permissions.includes("teaching:access") ? "学生＋教师" : "学生";
}
/** 仅取当前分页；查询变化时清旧列表，局部重试失败则保留同查询的已成功区域。 */
async function loadList() {
  if (!allowed.value || offline.value || cooling.value) return;
  listController?.abort(); const controller = new AbortController(); listController = controller;
  listLoading.value = true; listError.value = "";
  try {
    const result = await bounded(controller, () => api.list({ page: page.value, size: 20,
      status: query.value.status as "ACTIVE" | "DISABLED" | undefined, keyword: keyword.value || undefined }, controller.signal));
    if (!controller.signal.aborted) list.value = result;
  } catch (error) { if (listController === controller && (error as Error).message !== "CANCELLED") listError.value = describeError(error); }
  finally { if (listController === controller) listLoading.value = false; }
}
/** 拒绝把其他账号的命令呈现或用于补偿；不依赖clientRequestId与commandId的内部关系。 */
function validateCommand(value: AccountPermissionCommand, userId: string) {
  if (value.userId !== userId) throw new Error("COMMAND_ACCOUNT_MISMATCH");
  return value;
}
/** 重读目标真实能力及revision，同步当前分页投影；取消或切账号后不接纳旧响应。 */
async function refreshUser(controller: AbortController, userId: string) {
  const result = await bounded(controller, () => api.detail(userId, controller.signal));
  if (controller.signal.aborted || selectedId.value !== userId) return;
  if (result.id !== userId) throw new Error("ACCOUNT_MISMATCH");
  detail.value = result; detailError.value = "";
  if (list.value) list.value.items = list.value.items.map(item => item.id === userId ? result : item);
}
/** 重新进入先读账号与latest，再沿公开命令ID读取；204为空事实，部分失败只重试对应区域。 */
async function loadAccount() {
  stopPoll(); readController?.abort(); clearPreview();
  if (!selectedId.value || !allowed.value || offline.value || cooling.value) return;
  const userId = selectedId.value; const controller = new AbortController(); readController = controller;
  detailLoading.value = true; statusLoading.value = true; detailError.value = ""; statusError.value = "";
  try { await refreshUser(controller, userId); }
  catch (error) { if ((error as Error).message !== "CANCELLED" && readController === controller) { detail.value = null; detailError.value = describeError(error); } }
  finally { if (readController === controller) detailLoading.value = false; }
  if (controller.signal.aborted || !allowed.value) { statusLoading.value = false; return; }
  try {
    const value = await bounded(controller, () => api.latest(userId, controller.signal));
    if (controller.signal.aborted) return;
    latest.value = value ? validateCommand(value, userId) : null;
    const commandId = query.value.commandId || value?.commandId;
    command.value = commandId ? validateCommand(await bounded(controller, () => api.command(commandId, controller.signal)), userId) : null;
    if (controller.signal.aborted) return;
    if (latest.value?.commandId === command.value?.commandId) latest.value = command.value;
    // 协调可能在latest和按ID查询之间确认授能，必须重新读取revision与真实能力。
    if (command.value?.coordinationStatus === "APPLIED") await refreshUser(controller, userId);
    if (!controller.signal.aborted && detail.value && !detailError.value) reconciled.value = true;
    polls.value = 0; schedulePoll();
  } catch (error) {
    if ((error as Error).message !== "CANCELLED" && readController === controller) { command.value = null; statusError.value = describeError(error); }
  } finally { if (readController === controller) statusLoading.value = false; }
}
/** PENDING默认2秒最多30次；隐藏/离线/卸载停止，失败或次数耗尽必须手动查询。 */
function schedulePoll() {
  clearTimeout(pollTimer);
  if (allowed.value && !offline.value && !hidden.value && !cooling.value && command.value?.coordinationStatus === "PENDING"
    && polls.value < Math.min(100, Math.max(1, props.maxPolls))) pollTimer = setTimeout(() => void queryStatus(false), Math.max(500, props.pollIntervalMs));
}
/** 只读既有命令状态；手动查询重新给一轮有限查询预算，APPLIED重读账号，不发补偿命令。 */
async function queryStatus(manual = true) {
  if (!command.value || !allowed.value || offline.value || cooling.value || busy.value) return;
  stopPoll(); const controller = new AbortController(); pollController = controller;
  const userId = selectedId.value; const commandId = command.value.commandId;
  if (manual) polls.value = 0; else polls.value++;
  statusLoading.value = true; statusError.value = "";
  try {
    const value = validateCommand(await bounded(controller, () => api.command(commandId, controller.signal)), userId);
    if (controller.signal.aborted) return;
    command.value = value;
    if (latest.value?.commandId === value.commandId) latest.value = value;
    if (value.coordinationStatus === "APPLIED") await refreshUser(controller, userId);
    if (!controller.signal.aborted) schedulePoll();
  } catch (error) { if (pollController === controller && (error as Error).message !== "CANCELLED") statusError.value = describeError(error); }
  finally { if (pollController === controller) statusLoading.value = false; }
}
/** 当前账号/动作的服务端预览才是确认依据；过期撤销确认，依赖失败不呈现虚假的零影响。 */
async function makePreview(action: AccountPermissionAction) {
  if (locked.value || !detail.value || !actions.value.includes(action)) return;
  clearPreview(); writeError.value = ""; fields.value = {}; const controller = new AbortController(); previewController = controller;
  const userId = selectedId.value; const revision = detail.value.revision; previewLoading.value = true;
  try {
    const value = await bounded(controller, () => api.preview(userId, action, controller.signal));
    if (controller.signal.aborted) return;
    if (value.userId !== userId || value.action !== action || value.expectedRevision !== revision) { conflict.value = true; throw new Error("PREVIEW_CHANGED"); }
    preview.value = value; expired.value = Date.parse(value.expiresAt) <= Date.now();
    expiryTimer = setTimeout(() => { expired.value = true; confirmed.value = false; }, Math.max(0, Date.parse(value.expiresAt) - Date.now()));
  } catch (error) {
    if (previewController === controller && (error as Error).message !== "CANCELLED") {
      if (error instanceof GatewayRequestError && error.status === 409) conflict.value = true;
      writeError.value = describeError(error);
    }
  } finally { if (previewController === controller) previewLoading.value = false; }
}
/** 输入有效预览、10～500字原因和勾选；二次确认后固定原revision/载荷，取消不写服务端。 */
async function submit() {
  if (!canSubmit.value || !preview.value) return;
  const value = preview.value;
  if (Date.parse(value.expiresAt) <= Date.now()) { expired.value = true; confirmed.value = false; return; }
  if (!window.confirm(`确认${labels[value.action]}？涉及 ${value.affectedCourses} 门课程、${value.affectedStudents} 名去重在读学生。旧会话会失效。`)) return;
  snapshot.value = { kind: "change", userId: value.userId, sends: 0, body: { clientRequestId: crypto.randomUUID(), previewId: value.previewId,
    expectedRevision: value.expectedRevision, action: value.action, reason: reason.value.trim(), confirmed: true } };
  await sendSnapshot();
}
/** 输入当前FAILED事实；二次确认后保留原命令ID与expectedAttempts，一次补偿消耗一次服务端预算。 */
async function compensate() {
  if (!canCompensate.value || !command.value || !window.confirm("确认对原命令补偿一次？将消耗一次预算，不新建撤权周期。")) return;
  snapshot.value = { kind: "retry", userId: selectedId.value, commandId: command.value.commandId, sends: 0,
    body: { clientRequestId: crypto.randomUUID(), expectedAttempts: command.value.attempts } };
  await sendSnapshot();
}
/** 每个逻辑写入最多三次原载荷发送；5xx/超时不宣称失败回滚，不自动创建新命令或补偿周期。 */
async function sendSnapshot() {
  const saved = snapshot.value;
  if (!saved || saved.sends >= 3 || !allowed.value || busy.value || offline.value || cooling.value || conflict.value) return;
  stopPoll(); saved.sends++; busy.value = true; unknown.value = true; reconciled.value = false; writeError.value = ""; fields.value = {};
  const controller = new AbortController(); writeController = controller;
  try {
    const value = validateCommand(await bounded(controller, () => saved.kind === "change"
      ? api.change(saved.userId, saved.body, controller.signal) : api.retry(saved.commandId, saved.body, controller.signal)), saved.userId);
    if (controller.signal.aborted || snapshot.value !== saved) return;
    command.value = value; latest.value = value; unknown.value = false; snapshot.value = null;
    reason.value = ""; clearPreview(); notice.value = "IAM已提交决定，课程状态以查询事实为准；消息异步送达。";
    // 使用替换保留用户返回列表的历史；路由读取会再次核对最新账号及命令。
    await router.replace({ name: "admin-users", query: { ...query.value, userId: saved.userId, commandId: value.commandId, panel: "coordination" } });
    await loadAccount();
  } catch (error) {
    if (snapshot.value !== saved || (error as Error).message === "CANCELLED") return;
    if (error instanceof GatewayRequestError && error.status < 500) {
      unknown.value = false;
      if (error.status === 409) conflict.value = true;
      // 422/429确定拒绝，保留理由但重新预览；补偿拒绝须查询当前次数。
      snapshot.value = null; clearPreview();
    }
    writeError.value = describeError(error);
  } finally { if (writeController === controller) busy.value = false; }
}
/** 显式核对结束本地未知请求，不能靠latest猜测原命令ID；后续动作重新预览。 */
function finishUnknown() {
  if (busy.value || !unknown.value || !reconciled.value || detailLoading.value || statusLoading.value || detailError.value || statusError.value) return;
  if (!window.confirm("已核对当前账号和最近命令？结束本地未决请求后，如仍需变更必须重新预览。")) return;
  unknown.value = false; snapshot.value = null; clearPreview(); reason.value = ""; writeError.value = "";
}
async function refreshAndReview() { conflict.value = false; await loadAccount(); }
async function navigate(changes: Record<string, string | undefined>) {
  await router.push({ name: "admin-users", query: { ...query.value, ...changes } });
}
async function search() {
  if (unresolved.value || cooling.value || !allowed.value || offline.value) return;
  keyword.value = keywordInput.value.trim(); list.value = null;
  if (page.value !== 1) await navigate({ page: "1" }); else await loadList();
}
function choose(userId: string, event: MouseEvent) { returnFocus = event.currentTarget as HTMLElement; void navigate({ userId, commandId: undefined, panel: "detail" }); }
async function closeDetail() {
  await navigate({ userId: undefined, commandId: undefined, panel: undefined }); await nextTick();
  if (!selectedId.value) (returnFocus?.isConnected ? returnFocus : document.querySelector<HTMLElement>(".account-rows button"))?.focus();
}
/** 切账号或离页须明确放弃内存草稿/未知结果；重新进入只恢复服务端事实，不恢复敏感载荷。 */
function protectLeave() { return !(reason.value.trim() || unresolved.value) || window.confirm("仍有未保存理由或未确认写入结果，确认离开？可重新进入查询最近命令，但内存载荷不会保存。" ); }
function beforeUnload(event: BeforeUnloadEvent) { if (reason.value.trim() || unresolved.value) { event.preventDefault(); event.returnValue = ""; } }
onBeforeRouteLeave(protectLeave);
onBeforeRouteUpdate(to => sanitizeAccountPermissionsQuery(to.query).userId === selectedId.value || protectLeave());
watch([page, () => query.value.status], () => { list.value = null; void loadList(); }, { immediate: true });
watch([selectedId, () => query.value.commandId], ([userId], previous) => {
  if (userId !== previous?.[0]) {
    writeController?.abort(); busy.value = false;
    detail.value = null; command.value = null; latest.value = null; reason.value = ""; snapshot.value = null; unknown.value = false; conflict.value = false;
    writeError.value = ""; notice.value = ""; fields.value = {};
  }
  void loadAccount(); if (userId) void nextTick(() => pane.value?.focus());
}, { immediate: true });
watch(() => `${authStore.state.status}:${authStore.state.currentUser?.id}:${authStore.state.currentUser?.roles.join(",")}:${authStore.state.currentUser?.permissions.join(",")}`,
  (_, previous) => { if (previous !== undefined) clearProtected(); });
/** 规范化直接输入的URL；敏感查询不会由本页复制到后续历史或统一登录。 */
watch(() => route.fullPath, () => {
  const safe = query.value;
  if (route.hash || JSON.stringify(route.query) !== JSON.stringify(safe)) void router.replace({ name: "admin-users", query: safe, hash: "" });
}, { immediate: true });
function visibilityChanged() { hidden.value = document.hidden; if (hidden.value) stopPoll(); else schedulePoll(); }
function connectionChanged() { offline.value = !navigator.onLine; if (offline.value) { stopPoll(); readController?.abort(); listController?.abort(); clearPreview(); } else schedulePoll(); }
document.addEventListener("visibilitychange", visibilityChanged); window.addEventListener("online", connectionChanged);
window.addEventListener("offline", connectionChanged); window.addEventListener("beforeunload", beforeUnload);
onBeforeUnmount(() => {
  stopPoll(); listController?.abort(); readController?.abort(); writeController?.abort(); clearPreview(); clearTimeout(cooldownTimer);
  requestTimers.forEach(timer => clearTimeout(timer)); requestTimers.clear(); reason.value = ""; snapshot.value = null; keyword.value = "";
  document.removeEventListener("visibilitychange", visibilityChanged); window.removeEventListener("online", connectionChanged);
  window.removeEventListener("offline", connectionChanged); window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <main class="account-permissions container">
    <p class="eyebrow">账号治理</p><h2>账号权限</h2>
    <p>查询脱敏账号，核对权限变更影响。首次教学授予须通过教师审核；解封与恢复教学分别操作。</p>
    <p v-if="!allowed" role="alert" class="panel-error">登录失效或当前账号没有管理权限，请重新登录。</p>
    <p v-if="offline" role="status" class="panel-error">当前离线，已停止查询；恢复网络后请手动刷新核对。</p>
    <form v-if="allowed" class="account-filters" @submit.prevent="search">
      <label>账号状态<select :value="query.status ?? ''" :disabled="unresolved || cooling" @change="navigate({ status: ($event.target as HTMLSelectElement).value || undefined, page: '1' })"><option value="">全部状态</option><option value="ACTIVE">正常</option><option value="DISABLED">封禁</option></select></label>
      <label class="account-keyword">身份关键词（仅本页内存）<input v-model="keywordInput" maxlength="100" autocomplete="off" :disabled="unresolved" /></label>
      <button :disabled="unresolved || cooling || offline">查询账号</button>
    </form>
    <div v-if="allowed" class="account-layout">
      <section class="account-list" aria-label="账号分页">
        <p v-if="listLoading" role="status" class="account-skeleton">正在读取账号列表…</p>
        <p v-if="listError" role="alert" class="panel-error">{{ listError }}</p>
        <button :disabled="listLoading || cooling || offline" @click="loadList">刷新列表</button>
        <template v-if="list">
          <p v-if="!list.items.length">{{ keyword ? '没有匹配的账号，请调整关键词。' : '本页暂无账号，请调整状态或页码。' }}</p>
          <ul class="account-rows"><li v-for="user in list.items" :key="user.id" :class="{ selected: user.id === selectedId }">
            <strong>{{ user.displayName }}</strong><span class="status-chip">{{ stateLabel(user) }}</span>
            <p>{{ user.maskedIdentities.join(' · ') || '暂无已验证身份投影' }}</p>
            <small v-if="user.loginLocked">短期登录锁定（与封禁无关）</small>
            <button @click="choose(user.id, $event)">查看 {{ user.displayName }}</button>
          </li></ul>
          <nav class="account-pagination" aria-label="账号分页控制">
            <button :disabled="page <= 1 || listLoading || cooling || offline" @click="navigate({ page: String(page - 1) })">上一页</button>
            <span>第 {{ page }} / {{ pages }} 页 · 共 {{ list.total }} 个</span>
            <button :disabled="page >= pages || page >= 50001 || listLoading || cooling || offline" @click="navigate({ page: String(page + 1) })">下一页</button>
          </nav>
        </template>
      </section>
      <section v-if="selectedId" ref="pane" class="account-detail" tabindex="-1" aria-label="账号详情" @keydown.esc="closeDetail">
        <div class="account-actions"><button @click="closeDetail">关闭详情</button><button :disabled="detailLoading || statusLoading || busy || cooling || offline" @click="refreshAndReview">刷新账号与最近命令并核对</button></div>
        <p v-if="detailLoading" role="status" class="account-skeleton">正在读取当前账号…</p>
        <p v-if="detailError" role="alert" class="panel-error">{{ detailError }}</p>
        <template v-if="detail">
          <h3>{{ detail.displayName }}</h3><p>{{ stateLabel(detail) }}</p>
          <dl class="account-facts"><dt>账号ID</dt><dd>{{ detail.id }}</dd><dt>脱敏身份</dt><dd>{{ detail.maskedIdentities.join(' · ') }}</dd>
            <dt>当前能力</dt><dd>{{ detail.permissions.join('、') || '无有效能力' }}</dd><dt>登录安全</dt><dd>{{ detail.loginLocked ? '短期登录锁定，不等于账号封禁' : '未短期锁定' }}</dd>
            <dt>账号版本</dt><dd>{{ detail.revision }}</dd><dt>创建时间</dt><dd>{{ new Date(detail.createdAt).toLocaleString() }}</dd></dl>
          <div class="account-actions"><button @click="navigate({ panel: 'detail' })">权限操作</button><button @click="navigate({ panel: 'coordination' })">协调状态</button></div>
        </template>
        <p v-if="statusLoading" role="status">正在查询命令事实…</p><p v-if="statusError" role="alert" class="panel-error">{{ statusError }}</p>
        <p v-if="notice" role="status">{{ notice }}</p>
        <section v-if="unknown || busy" class="account-warning" aria-label="未决请求">
          <p>{{ busy ? '写请求处理中，请等待回执。' : '写入结果未知，可能已提交。请保留原请求重试或查询最近命令后人工核对。' }}</p>
          <button :disabled="!canResend" @click="sendSnapshot">重试原请求（{{ snapshot?.sends ?? 0 }}/3）</button>
          <button :disabled="busy || !reconciled || detailLoading || statusLoading || !!detailError || !!statusError" @click="finishUnknown">已核对，结束本地未决请求</button>
        </section>
        <p v-if="writeError" role="alert" class="panel-error">{{ writeError }}</p>
        <p v-for="(value, key) in fields" :key="key" role="alert" class="panel-error">{{ key }}：{{ value }}</p>
        <p v-if="conflict" role="alert">请先点击“刷新账号与最近命令并核对”，之后重新预览。</p>
        <section v-if="command" class="account-coordination" aria-label="课程协调事实">
          <h4>{{ coordinationLabels[command.coordinationStatus] }}</h4>
          <p>{{ labels[command.action] }} · IAM决定版本 {{ command.decidedRevision }}</p>
          <dl class="account-facts"><dt>命令ID</dt><dd>{{ command.commandId }}</dd><dt>课程数</dt><dd>{{ command.affectedCourses }}（{{ command.coordinationStatus === 'APPLIED' ? '实际改变' : '预览范围' }}）</dd>
            <dt>协调预算</dt><dd>{{ command.attempts }}/{{ command.maxAttempts }} 次（自动最多3次，手动最多再3次）</dd><dt v-if="command.lastErrorCode">错误分类</dt><dd v-if="command.lastErrorCode">{{ command.lastErrorCode }}</dd></dl>
          <p v-if="command.coordinationStatus === 'NOT_REQUIRED'">IAM已提交，此决定无需课程联动。</p>
          <p v-else-if="command.coordinationStatus === 'PENDING'">尚未确认课程联动。恢复教学须等待Course确认，再读取真实能力。</p>
          <p v-else-if="command.coordinationStatus === 'FAILED'">失败不会回滚权限决定；只允许对原命令有限补偿。</p>
          <p v-else>Course事实及Outbox已确认；不表示全部Learning消费者或通知已完成。</p>
          <p v-if="command.attempts >= command.maxAttempts && command.coordinationStatus === 'FAILED'">六次预算已耗尽，请交由后续运维处理。</p>
          <p v-if="command.coordinationStatus === 'PENDING' && polls >= Math.min(100, Math.max(1, props.maxPolls))">自动查询已达上限，请手动查询。</p>
          <div class="account-actions"><button :disabled="busy || statusLoading || cooling || offline" @click="queryStatus()">查询当前命令状态</button><button v-if="command.coordinationStatus === 'FAILED'" :disabled="!canCompensate" @click="compensate">补偿原命令一次</button></div>
        </section>
        <p v-else-if="!statusLoading && !statusError">账号尚无权限命令（204，无正文）。</p>
        <section v-if="detail && query.panel !== 'coordination'" class="account-change" aria-label="受控权限动作">
          <p v-if="detail.roles.includes('ADMIN')">管理员独立身份，仅允许受控封禁/解封；禁止自封禁及停用最后可用管理员。</p>
          <p v-else>解封只恢复学生能力。恢复教学需已有批准记录，服务端会重新核对；首次授予请进入教师审核。</p>
          <p v-if="latestUnsettled">最近命令尚未完成，请先查询或有限补偿，不得新建周期。</p>
          <div class="account-actions"><button v-for="action in actions" :key="action" :disabled="locked" @click="makePreview(action)">预览{{ labels[action] }}</button></div>
          <p v-if="previewLoading" role="status">正在读取真实影响预览…</p>
          <form v-if="preview" class="account-preview" @submit.prevent="submit">
            <h4>{{ labels[preview.action] }}影响预览</h4><p>{{ preview.affectedCourses }} 门课程 · {{ preview.affectedStudents }} 名去重 ACTIVE 学生</p>
            <p>有效至 {{ new Date(preview.expiresAt).toLocaleString() }}（五分钟预览）</p><p v-if="expired" role="alert">预览已过期，请重新预览并核对。</p>
            <label>变更理由（10～500字）<textarea v-model="reason" minlength="10" maxlength="500" :disabled="unresolved" /></label>
            <label><input v-model="confirmed" type="checkbox" :disabled="expired || unresolved" /> 我已核对影响范围与旧会话失效，理解恢复仅涉及原暂停周期</label>
            <button :disabled="!canSubmit">确认并提交{{ labels[preview.action] }}</button>
          </form>
        </section>
      </section>
      <section v-else class="account-detail"><p>从列表选择账号，或通过账号ID深链接按需打开详情。</p></section>
    </div>
  </main>
</template>
