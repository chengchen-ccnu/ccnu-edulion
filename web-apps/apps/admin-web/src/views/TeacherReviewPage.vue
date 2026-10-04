<script setup lang="ts">
/**
 * 教师资格审核页：输入为白名单URL中的分页/状态/排序/申请及面板，精确身份仅存内存。
 * 列表只读，主动详情开启审核；材料通过IAM短期授权读取为私有Blob，历史只读。
 * 决定保存原请求快照，未知结果最多三次手动重试；权限变化/离页清理私有数据与所有请求、计时器、URL。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AdminTeacherApplicationDetail, AdminTeacherApplicationPage, TeacherReviewDecisionRequest, TeacherReviewRound } from "@edu/api-client";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from "vue-router";
import { resolveInternalRedirect } from "@edu/auth";
import { authStore } from "../auth";
import { teacherReviewApi as api } from "../teacher-review-api";
import type { ReviewQuery } from "../teacher-review-api";

const route = useRoute(); const router = useRouter();
const labels = { SUBMITTED: "待审核", UNDER_REVIEW: "审核中", APPROVED: "已批准", REJECTED: "已驳回" };
const page = computed(() => typeof route.query.page === "string" && /^[1-9]\d{0,4}$/.test(route.query.page)
  && Number(route.query.page) <= 50001 ? Number(route.query.page) : 1);
const status = computed(() => Object.hasOwn(labels, String(route.query.status)) ? route.query.status as ReviewQuery["status"] : undefined);
const order = computed(() => route.query.order === "OLDEST" ? "OLDEST" : "NEWEST");
const selectedId = computed(() => typeof route.query.applicationId === "string" && /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(route.query.applicationId) ? route.query.applicationId : "");
const panel = computed(() => route.query.panel === "history" ? "history" : "detail");
const exactInput = ref(""); const exact = ref("");
const list = ref<AdminTeacherApplicationPage | null>(null); const detail = ref<AdminTeacherApplicationDetail | null>(null);
const loading = ref(false); const detailLoading = ref(false); const materialLoading = ref(false); const busy = ref(false);
const listError = ref(""); const detailError = ref(""); const materialError = ref(""); const writeError = ref(""); const notice = ref("");
const fields = ref<Record<string, string>>({}); const denied = ref(false); const cooling = ref(false); const conflict = ref(false);
const queryFields = ref<Record<string, string>>({});
const reason = ref(""); const confirmed = ref(false); const readable = ref(false);
const previewUrl = ref(""); const previewMime = ref(""); const previewRoundId = ref("");
const pane = ref<HTMLElement | null>(null); let returnFocus: HTMLElement | null = null;
let listController: AbortController | undefined; let detailController: AbortController | undefined;
let materialController: AbortController | undefined; let writeController: AbortController | undefined;
let cooldownTimer: ReturnType<typeof setTimeout> | undefined; let expiryTimer: ReturnType<typeof setTimeout> | undefined;
let writeTimer: ReturnType<typeof setTimeout> | undefined;
const temporaryUrls = new Set<string>();
/** 已发送命令与草稿独立；任何重试使用保存的生成DTO，不从刷新后的详情或输入重建。 */
const command = ref<{ applicationId: string; body: TeacherReviewDecisionRequest; attempts: number } | null>(null);
const unresolved = ref(false);
const reconciled = ref(false);
const administrator = computed(() => authStore.state.currentUser?.roles.includes("ADMIN")
  && authStore.state.currentUser.permissions.includes("administration:access"));
const currentRound = computed(() => detail.value?.rounds.reduce<TeacherReviewRound | undefined>((latest, round) =>
  !latest || round.roundNumber > latest.roundNumber ? round : latest, undefined));
const actionable = computed(() => detail.value && ["SUBMITTED", "UNDER_REVIEW"].includes(detail.value.status));
const locked = computed(() => !administrator.value || denied.value || detailLoading.value || busy.value || cooling.value || unresolved.value || conflict.value);
const canApprove = computed(() => !locked.value && actionable.value && currentRound.value?.materialAvailable && readable.value && confirmed.value);
const canRetry = computed(() => administrator.value && !denied.value && !busy.value && !cooling.value && !conflict.value
  && unresolved.value && command.value && command.value.attempts < 3);
const pages = computed(() => Math.max(1, Math.ceil((list.value?.total ?? 0) / 20)));

/** 清理当前材料及确认，短期URL从不进入DOM；只展示Blob，旧轮预览不构成批准当前轮的证据。 */
function clearMaterial() {
  materialController?.abort(); clearTimeout(expiryTimer);
  temporaryUrls.forEach(url => URL.revokeObjectURL(url)); temporaryUrls.clear();
  previewUrl.value = ""; previewMime.value = ""; previewRoundId.value = "";
  readable.value = false; confirmed.value = false; materialLoading.value = false;
}
/** 权限或身份变化中止并发响应并抹去私有输入；未决标记保留提醒，旧身份命令不可重试。 */
function clearProtected() {
  if (busy.value && command.value) unresolved.value = true;
  listController?.abort(); detailController?.abort(); writeController?.abort(); clearMaterial();
  clearTimeout(cooldownTimer); clearTimeout(writeTimer); cooling.value = false;
  list.value = null; detail.value = null; exact.value = ""; exactInput.value = ""; reason.value = "";
  command.value = null; fields.value = {}; queryFields.value = {}; notice.value = ""; materialError.value = "";
  loading.value = false; detailLoading.value = false; busy.value = false; denied.value = true;
}
function authorizationFailed(error: unknown) {
  return error instanceof GatewayRequestError && [401, 403].includes(error.status)
    || error instanceof Error && error.message === "AUTHENTICATION_REQUIRED";
}
/** 使用通用文案，避免输出联系方式/材料地址；429仅恢复手动按钮，不自动发送决定。 */
function errorText(error: unknown): string {
  if (authorizationFailed(error)) return "登录失效或当前账号没有管理权限，请重新登录。";
  if (error instanceof GatewayRequestError) {
    if (error.status === 429) {
      const seconds = Math.max(1, error.retryAfterSeconds ?? 30); cooling.value = true; clearTimeout(cooldownTimer);
      cooldownTimer = setTimeout(() => { cooling.value = false; }, Math.min(seconds * 1000, 2147483647));
      return `请求过于频繁，请至少等待 ${seconds} 秒后重试。`;
    }
    return ({ 404: "申请或材料不存在，或当前不可访问。", 409: "申请版本或状态已变化，请刷新详情后重新决定。",
      422: "请求校验失败，请检查以下字段或请求错误。", 503: "材料依赖或服务暂不可用，请稍后重试。" } as Record<number, string>)[error.status]
      ?? "服务请求失败，请稍后重试。";
  }
  return error instanceof TypeError ? "网络不可用，请恢复网络后重试。" : "请求未完成，请稍后重试。";
}

/** 只读取一页列表；失败保留同查询已成功区域，不为每行读取详情或材料。 */
async function loadList() {
  if (!administrator.value || denied.value) return;
  listController?.abort(); const controller = new AbortController(); listController = controller;
  loading.value = true; listError.value = ""; queryFields.value = {};
  try {
    const result = await api.list({ page: page.value, size: 20, status: status.value, order: order.value, exact: exact.value || undefined }, controller.signal);
    if (!controller.signal.aborted) list.value = result;
  } catch (error) {
    if (controller.signal.aborted) return;
    listError.value = errorText(error);
    if (error instanceof GatewayRequestError) queryFields.value = error.fieldErrors;
    if (authorizationFailed(error)) clearProtected();
  } finally { if (!controller.signal.aborted) loading.value = false; }
}
/** 仅主动打开、真实深链接或手动刷新详情触发首次审核；刷新清除材料确认，409保留驳回草稿。 */
async function loadDetail() {
  detailController?.abort(); clearMaterial(); detailError.value = ""; materialError.value = "";
  if (!selectedId.value || !administrator.value || denied.value) { detail.value = null; return; }
  const controller = new AbortController(); detailController = controller; detailLoading.value = true;
  try {
    const result = await api.detail(selectedId.value, controller.signal);
    if (controller.signal.aborted) return;
    detail.value = result;
    if (list.value) list.value.items = list.value.items.map(item => item.id === result.id
      ? { ...item, status: result.status, revision: result.revision } : item);
  } catch (error) {
    if (controller.signal.aborted) return;
    detail.value = null; detailError.value = errorText(error); if (authorizationFailed(error)) clearProtected();
  } finally { if (!controller.signal.aborted) detailLoading.value = false; }
}
/** 导航仅保存非敏感白名单；查询身份永不复制到URL，列表筛选与详情/历史可刷新恢复。 */
async function navigate(changes: Record<string, string | undefined>) {
  await router.push({ name: "admin-teacher-review", query: { page: String(page.value), status: status.value,
    order: order.value, applicationId: selectedId.value || undefined, panel: selectedId.value ? panel.value : undefined, ...changes } });
}
async function search() {
  if (busy.value || unresolved.value || cooling.value || denied.value) return;
  exact.value = exactInput.value.trim(); list.value = null;
  if (page.value !== 1) await navigate({ page: "1" }); else await loadList();
}
function choose(id: string, event: MouseEvent) {
  returnFocus = event.currentTarget as HTMLElement; void navigate({ applicationId: id, panel: "detail" });
}
async function closeDetail() {
  await navigate({ applicationId: undefined, panel: undefined });
  await nextTick();
  if (!selectedId.value) {
    const target = returnFocus?.isConnected ? returnFocus : document.querySelector<HTMLElement>(".review-rows button");
    target?.focus();
  }
}

/** 按当前轮次申请原件并实际读取为Blob；一次手动操作无无限重试，失败会撤销当前材料批准资格。 */
async function readMaterial(round: TeacherReviewRound, action: "PREVIEW" | "DOWNLOAD") {
  if (!detail.value || !round.materialAvailable || locked.value || materialLoading.value) return;
  clearMaterial(); materialError.value = "";
  const controller = new AbortController(); materialController = controller; materialLoading.value = true;
  const revision = detail.value.revision; const applicationId = detail.value.id;
  try {
    const grant = await api.material(applicationId, { roundId: round.id, action }, controller.signal);
    const response = await fetch(grant.url, { method: grant.method, headers: grant.headers, signal: controller.signal,
      credentials: "omit", cache: "no-store", referrerPolicy: "no-referrer" });
    if (!response.ok) throw new Error("MATERIAL_UNAVAILABLE");
    const blob = await response.blob();
    if (controller.signal.aborted) return;
    if (!blob.size || !["image/png", "image/jpeg", "image/webp", "application/pdf"].includes(grant.mimeType)
      || Date.parse(grant.expiresAt) <= Date.now()) throw new Error("MATERIAL_UNAVAILABLE");
    // PDF先检查文件头，图片必须等浏览器成功解码才允许人工确认；下载同样要求实际收到完整文件。
    if (grant.mimeType === "application/pdf" && !(await blob.slice(0, 5).text()).startsWith("%PDF-")) throw new Error("MATERIAL_UNAVAILABLE");
    if (controller.signal.aborted) return;
    const url = URL.createObjectURL(new Blob([blob], { type: grant.mimeType })); temporaryUrls.add(url);
    previewRoundId.value = round.id; previewMime.value = grant.mimeType;
    if (action === "DOWNLOAD") {
      const link = document.createElement("a"); link.href = url; link.download = `资格材料-${round.roundNumber}.${grant.mimeType === "application/pdf" ? "pdf" : grant.mimeType.split("/")[1]}`;
      link.click(); readable.value = round.id === currentRound.value?.id && revision === detail.value?.revision;
      notice.value = "材料已读取并交给浏览器下载，请人工核对原件。";
    } else previewUrl.value = url;
    expiryTimer = setTimeout(clearMaterial, Math.min(Math.max(0, Date.parse(grant.expiresAt) - Date.now()), 2147483647));
  } catch (error) {
    if (controller.signal.aborted) return;
    clearMaterial(); materialError.value = authorizationFailed(error) || error instanceof GatewayRequestError
      ? errorText(error) : "材料无法读取或文件不可预览，请重试或联系支持；当前不能批准。";
    if (authorizationFailed(error)) { clearProtected(); listError.value = errorText(error); }
  } finally { if (!controller.signal.aborted) materialLoading.value = false; }
}
function previewLoaded() { readable.value = previewRoundId.value === currentRound.value?.id; }
function previewFailed() { clearMaterial(); materialError.value = "材料无法预览，当前不能批准。"; }

/** 首次提交需当前事实及人工确认；驳回理由10～1000字，批准二次确认授能后才保存稳定命令。 */
async function decide(decision: TeacherReviewDecisionRequest["decision"]) {
  if (locked.value || !actionable.value || !detail.value) return;
  fields.value = {};
  if (decision === "APPROVED" && !canApprove.value) return;
  if (decision === "REJECTED" && (reason.value.trim().length < 10 || reason.value.trim().length > 1000)) {
    fields.value.rejectionReason = "请填写10～1000字、申请人可理解的驳回理由。"; return;
  }
  if (!window.confirm(decision === "APPROVED" ? "确认已经人工核对当前资质材料，并批准此账号获得教学能力？决定不可修改。"
    : "确认驳回此轮申请？理由将向申请人展示，决定不可修改。")) return;
  command.value = { applicationId: detail.value.id, attempts: 0, body: { clientRequestId: crypto.randomUUID(),
    expectedRevision: detail.value.revision, decision, materialConfirmed: decision === "APPROVED",
    ...(decision === "REJECTED" ? { rejectionReason: reason.value.trim() } : {}) } };
  await executeCommand();
}
/** 发送原命令，超时/网络/5xx保留未知结果；取消浏览器请求不能证明IAM事务未提交。 */
async function executeCommand() {
  const snapshot = command.value;
  if (!snapshot || snapshot.attempts >= 3 || busy.value || !administrator.value || denied.value || cooling.value || conflict.value) return;
  snapshot.attempts++; busy.value = true; reconciled.value = false; writeError.value = ""; fields.value = {};
  const controller = new AbortController(); writeController = controller;
  writeTimer = setTimeout(() => {
    controller.abort(); busy.value = false; unresolved.value = true;
    writeError.value = "决定请求超时，结果未知。请重试原决定或核对最新详情。";
  }, 15000);
  try {
    const result = await api.decide(snapshot.applicationId, snapshot.body, controller.signal);
    if (controller.signal.aborted) return;
    clearTimeout(writeTimer); detail.value = result; unresolved.value = false; command.value = null; reason.value = ""; clearMaterial();
    notice.value = "审核决定已保存。结果消息由系统异步投递。"; await loadList();
  } catch (error) {
    if (controller.signal.aborted) return;
    reconciled.value = false; writeError.value = errorText(error);
    if (error instanceof GatewayRequestError) fields.value = error.fieldErrors;
    if (authorizationFailed(error)) { unresolved.value = true; clearProtected(); }
    else if (error instanceof GatewayRequestError && error.status === 409) {
      conflict.value = true; confirmed.value = false; readable.value = false;
      // 原命令不能改版本后重放，等待明确刷新、核对和重新决定。
    } else if (!(error instanceof GatewayRequestError) || error.status >= 500) {
      unresolved.value = true; clearMaterial(); writeError.value += " 决定结果未知，请保留原请求重试或核对详情。";
    } else if (!unresolved.value) command.value = null;
  } finally { clearTimeout(writeTimer); if (!controller.signal.aborted) busy.value = false; }
}
/** 冲突/未决只能显式核对详情；刷新本身不消除未知标记，不自动采用新版本或发新决定。 */
async function reconcile() {
  await loadDetail();
  reconciled.value = !!detail.value && !detailError.value;
  if (reconciled.value) notice.value = "已读取最新详情，请核对当前轮次与决定历史，再明确结束旧请求。";
}
function acknowledge() {
  if (!reconciled.value || !detail.value || detailLoading.value || busy.value || !window.confirm("已核对最新轮次和历史？结束旧请求后，若仍待审，需要重新读取材料并明确决定。")) return;
  command.value = null; unresolved.value = false; conflict.value = false; clearMaterial(); writeError.value = ""; fields.value = {};
}
/** 所有路由离开/切换申请均保护草稿与未决结果；同一申请切面板不丢输入或请求。 */
function protectLeave() {
  return !(busy.value || unresolved.value || reason.value.trim()) || window.confirm("当前有未保存理由或结果未决的审核决定。离开将清除本地输入，服务端决定可能已保存。确认离开？");
}
onBeforeRouteLeave(() => protectLeave());
onBeforeRouteUpdate(to => String(to.query.applicationId ?? "") === selectedId.value || protectLeave());
function beforeUnload(event: BeforeUnloadEvent) {
  if (busy.value || unresolved.value || reason.value.trim()) { event.preventDefault(); event.returnValue = ""; }
}
window.addEventListener("beforeunload", beforeUnload);
// 刷新/直接深链接也移除未知敏感参数，与统一登录的回跳白名单保持一致。
const safeLocation = resolveInternalRedirect(route.fullPath)?.requestedPath;
if (safeLocation && safeLocation !== route.fullPath) void router.replace(safeLocation);
watch(() => [page.value, status.value, order.value], () => { list.value = null; void loadList(); }, { immediate: true });
watch(selectedId, async () => {
  detail.value = null; reason.value = ""; command.value = null; unresolved.value = false; conflict.value = false;
  writeController?.abort(); clearTimeout(writeTimer); busy.value = false; writeError.value = ""; fields.value = {}; notice.value = "";
  await loadDetail(); if (selectedId.value) { await nextTick(); pane.value?.focus(); }
}, { immediate: true });
watch(() => [authStore.state.currentUser?.id, authStore.state.currentUser?.roles.join(","), authStore.state.currentUser?.permissions.join(",")], () => {
  clearProtected(); listError.value = "身份或管理权限已变化，请重新进入审核页面。";
});
onBeforeUnmount(() => { clearProtected(); window.removeEventListener("beforeunload", beforeUnload); });
</script>

<template>
  <section class="teacher-review" aria-labelledby="review-title">
    <header><span class="badge">资格审核</span><h2 id="review-title">教师审核</h2>
      <p>主动打开详情才开始审核。审核中不独占；批准前请人工核对当前轮资质材料。</p></header>
    <p v-if="notice" role="status">{{ notice }}</p>
    <form class="review-filters" @submit.prevent="search">
      <label>申请状态<select :value="status ?? ''" :disabled="denied || busy || cooling" @change="navigate({ status: ($event.target as HTMLSelectElement).value || undefined, page: '1' })">
        <option value="">全部状态</option><option v-for="(label, value) in labels" :key="value" :value="value">{{ label }}</option></select></label>
      <label>提交顺序<select :value="order" :disabled="denied || busy || cooling" @change="navigate({ order: ($event.target as HTMLSelectElement).value, page: '1' })">
        <option value="NEWEST">最新优先</option><option value="OLDEST">最早优先</option></select></label>
      <label class="exact-query">精确查询<input v-model="exactInput" maxlength="254" autocomplete="off" placeholder="申请编号、邮箱、手机号或内部账号" :aria-invalid="!!queryFields.exact" aria-describedby="query-errors" :disabled="denied || busy || unresolved" /></label>
      <button :disabled="loading || denied || busy || unresolved || cooling">查询</button>
    </form>
    <p>精确查询仅在本页保留，刷新后需重新输入。</p>
    <div id="query-errors"><p v-for="(message, field) in queryFields" :key="field" role="alert">{{ field }}：{{ message }}</p></div>
    <div class="review-layout">
      <section class="review-list" aria-label="教师申请列表" :aria-busy="loading">
        <p v-if="loading" role="status" class="review-skeleton">正在读取申请列表…</p>
        <div v-if="listError" role="alert"><p>{{ listError }}</p><button :disabled="denied || cooling || loading" @click="loadList">重试列表</button></div>
        <p v-if="list && !list.items.length">{{ list.total ? '当前页没有申请，请返回上一页或调整筛选。' : '没有符合条件的申请，可调整筛选或精确查询。' }}</p>
        <ol v-if="list" class="review-rows">
          <li v-for="item in list.items" :key="item.id" :class="{ selected: item.id === selectedId }">
            <div><strong>{{ item.displayName }}</strong><span class="status-chip">{{ labels[item.status] }}</span></div>
            <p>{{ item.account }}</p><small>申请编号 {{ item.id }}<br />提交于 {{ new Date(item.submittedAt).toLocaleString() }}</small>
            <button :disabled="denied || busy" :aria-label="`打开 ${item.displayName} 的审核详情`" @click="choose(item.id, $event)">打开详情</button>
          </li>
        </ol>
        <nav v-if="list" class="review-pagination" aria-label="申请分页">
          <button :disabled="page <= 1 || loading || denied || cooling" @click="navigate({ page: String(page - 1) })">上一页</button>
          <span>第 {{ page }} / {{ pages }} 页 · {{ list.total }} 条</span>
          <button :disabled="page >= pages || page >= 50001 || loading || denied || cooling" @click="navigate({ page: String(page + 1) })">下一页</button>
        </nav>
      </section>
      <section v-if="selectedId" ref="pane" class="review-detail" tabindex="-1" aria-label="申请审核详情" :aria-busy="detailLoading">
        <div class="review-actions"><button @click="closeDetail">关闭详情</button>
          <button :disabled="detailLoading || busy || denied || cooling" @click="reconcile">刷新详情并核对</button></div>
        <p v-if="detailLoading" role="status" class="review-skeleton">正在读取审核详情…</p>
        <p v-if="detailError" role="alert">{{ detailError }}</p>
        <template v-if="detail">
          <h3>{{ detail.displayName }} · {{ labels[detail.status] }}</h3><p>申请编号 {{ detail.id }} · 版本 {{ detail.revision }}</p>
          <div class="review-actions" aria-label="详情面板"><button :aria-pressed="panel === 'detail'" @click="navigate({ panel: 'detail' })">当前轮次</button>
            <button :aria-pressed="panel === 'history'" @click="navigate({ panel: 'history' })">只读历史</button></div>
          <article v-for="round in panel === 'history' ? detail.rounds : currentRound ? [currentRound] : []" :key="round.id" class="review-round">
            <h4>第 {{ round.roundNumber }} 轮 · {{ labels[round.status] }}</h4>
            <p class="statement">{{ round.statement }}</p>
            <dl><dt>提交时间</dt><dd>{{ new Date(round.submittedAt).toLocaleString() }}</dd>
              <dt>首次查看者</dt><dd>{{ round.firstViewedBy ?? '尚未查看' }}</dd>
              <dt>首次查看时间</dt><dd>{{ round.firstViewedAt ? new Date(round.firstViewedAt).toLocaleString() : '尚未查看' }}</dd>
              <dt>决定者</dt><dd>{{ round.decidedBy ?? '尚未决定' }}</dd>
              <dt>决定时间</dt><dd>{{ round.decidedAt ? new Date(round.decidedAt).toLocaleString() : '尚未决定' }}</dd>
              <dt>材料保留期限</dt><dd>{{ round.retainUntil ? new Date(round.retainUntil).toLocaleString() : '持续保留' }}</dd></dl>
            <p v-if="round.rejectionReason" class="statement">驳回理由：{{ round.rejectionReason }}</p>
            <p v-if="!round.materialAvailable">本轮材料已不可用，历史决定仍可查看；当前轮材料不可用时不能批准。</p>
            <div class="review-actions"><button :disabled="!round.materialAvailable || locked || materialLoading" @click="readMaterial(round, 'PREVIEW')">预览第 {{ round.roundNumber }} 轮材料</button>
              <button :disabled="!round.materialAvailable || locked || materialLoading" @click="readMaterial(round, 'DOWNLOAD')">下载第 {{ round.roundNumber }} 轮材料</button></div>
          </article>
          <p v-if="materialLoading" role="status">正在授权并读取材料…</p><p v-if="materialError" role="alert">{{ materialError }}</p>
          <div v-if="previewUrl" class="material-preview">
            <p>第 {{ detail.rounds.find(round => round.id === previewRoundId)?.roundNumber }} 轮材料，仅供本次审核。</p>
            <iframe v-if="previewMime === 'application/pdf'" :src="previewUrl" title="资格材料PDF预览" @load="previewLoaded" @error="previewFailed" />
            <img v-else :src="previewUrl" alt="教师资格材料原件" @load="previewLoaded" @error="previewFailed" />
            <button @click="clearMaterial">关闭材料预览</button>
          </div>
          <form v-if="actionable && panel === 'detail'" class="review-decision" @submit.prevent="decide('REJECTED')">
            <h4>审核决定</h4><p>批准会授予同一账号教学能力，保留学习能力；最终决定不可修改。</p>
            <label><input v-model="confirmed" type="checkbox" :disabled="locked || !readable || !currentRound?.materialAvailable" />已人工核对当前轮资质材料</label>
            <button type="button" :disabled="!canApprove" @click="decide('APPROVED')">批准申请</button>
            <label for="rejection-reason">驳回理由（10～1000字）</label>
            <textarea id="rejection-reason" v-model="reason" maxlength="1000" :disabled="locked" :aria-invalid="!!fields.rejectionReason" aria-describedby="reason-error" />
            <p id="reason-error" role="alert">{{ fields.rejectionReason }}</p><button :disabled="locked">驳回申请</button>
          </form>
          <p v-else-if="panel === 'detail'">此轮已经决定，仅供只读查看。</p>
        </template>
        <p v-if="busy" role="status">正在保存决定，请等待确认…</p>
        <p v-if="writeError" role="alert">{{ writeError }}</p>
        <p v-for="(message, field) in fields" v-show="field !== 'rejectionReason'" :key="field" role="alert">{{ field }}：{{ message }}</p>
        <div v-if="unresolved || conflict" class="review-pending" role="status">
          <p>{{ conflict ? '存在并发冲突，不能覆盖旧决定。' : '审核结果未决，请保留原请求核对，避免重复决定。' }}</p>
          <p v-if="command && command.attempts >= 3">原决定已尝试三次，请核对最新详情，不能继续重试。</p>
          <button :disabled="!canRetry" @click="executeCommand">重试原决定</button>
          <button :disabled="!reconciled || !detail || detailLoading || busy || denied" @click="acknowledge">已核对，结束旧请求并重新决定</button>
        </div>
      </section>
      <aside v-else class="review-detail"><h3>选择申请</h3><p>打开一条申请查看材料和审核历史。列表加载不会开始审核。</p></aside>
    </div>
  </section>
</template>
