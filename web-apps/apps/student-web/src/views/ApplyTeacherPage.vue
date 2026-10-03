<script setup lang="ts">
/**
 * 教师申请页：上传本人资质到 media-service 后提交申请事实。
 * 页面只创建/重提申请并展示 T16 的状态投影，不做审核决定、不授予教学能力，也不修改完整个人资料。
 */
import type { MediaAsset, MediaUploadGrant } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { accountSecurityApi } from "../account-security-api";
import { accountError, randomRequestId } from "../account-errors";
import { authStore } from "../auth";
import { applicationLabels, useTeacherApplicationStatus } from "../teacher-application-status";

const { application, loading, ready, teaching, error: statusError, refresh } = useTeacherApplicationStatus();
const statement = ref("");
const mediaId = ref("");
const selectedFile = ref<File | null>(null);
const uploadStatus = ref("");
const uploadError = ref("");
const errorMessage = ref("");
const successMessage = ref("");
const submitting = ref(false);
const uploading = ref(false);
let controller: AbortController | null = null;
let submitController: AbortController | null = null;
const hasTeachingAccess = computed(() => authStore.state.currentUser?.permissions.includes("teaching:access") ?? false);
const canSubmit = computed(() => ready.value && !hasTeachingAccess.value && !authStore.hasPermission("administration:access")
  && (!application.value || application.value.status === "REJECTED"));

watch(application, value => { if (value && !statement.value) statement.value = value.statement; });
onBeforeUnmount(() => { controller?.abort(); submitController?.abort(); });

/** 选择资质文件，校验大小后复用媒体上传；重新申请必须提供仍有效的新资质。 */
async function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  selectedFile.value = file; mediaId.value = ""; uploadError.value = ""; uploadStatus.value = "";
  if (file.size > 5 * 1024 * 1024) { uploadError.value = "资质文件不能超过 5 MB。"; return; }
  await uploadQualification(file);
}

/** 通过媒体短期授权上传资质；READY后保存mediaId，处理失败或离页立即停止轮询。 */
async function uploadQualification(file: File) {
  uploading.value = true; controller = new AbortController();
  try {
    uploadStatus.value = "正在计算文件校验值…";
    const digest = await sha256(file, controller.signal);
    const session = await accountSecurityApi.createMediaUpload({ clientRequestId: randomRequestId(), purpose: "TEACHER_QUALIFICATION", fileName: file.name, size: file.size, mimeType: file.type || "application/octet-stream", sha256: digest }, controller.signal);
    if (session.mediaId) { mediaId.value = session.mediaId; uploadStatus.value = "资质已就绪。"; return; }
    if (!session.uploadGrant || session.mode !== "SINGLE_PUT") throw new Error("QUALIFICATION_UPLOAD_MODE_UNSUPPORTED");
    uploadStatus.value = "正在安全上传资质文件…";
    await put(session.uploadGrant, file, controller.signal);
    const asset = await accountSecurityApi.completeUpload(session.uploadId, { sha256: digest, parts: [] }, controller.signal);
    const ready = await waitReady(asset, controller.signal);
    mediaId.value = ready.mediaId; uploadStatus.value = "资质已就绪，可以提交申请。";
  } catch (error) { if (!controller.signal.aborted) uploadError.value = error instanceof Error && error.message === "QUALIFICATION_UPLOAD_MODE_UNSUPPORTED" ? "当前资质文件需要分片上传，请选择较小的文件重试。" : accountError(error, "资质上传未完成，请稍后重试。").message; }
  finally { uploading.value = false; controller = null; }
}

/** 有限等待媒体校验，终止状态立即报错；取消后不再请求媒体服务。 */
async function waitReady(asset: MediaAsset, signal: AbortSignal): Promise<MediaAsset> {
  let current = asset;
  for (let attempt = 0; attempt < 80 && current.status !== "READY"; attempt += 1) {
    if (["FAILED", "DELETED", "EXPIRED", "REJECTED"].includes(current.status)) throw new Error("QUALIFICATION_UNAVAILABLE");
    await delay(1500, signal); await waitVisible(signal); current = await accountSecurityApi.getMedia(asset.mediaId, signal);
  }
  if (signal.aborted) throw new DOMException("Aborted", "AbortError");
  if (current.status !== "READY") throw new Error("QUALIFICATION_PROCESSING_TIMEOUT");
  return current;
}

/** 申请提交只记录审核事实；冲突后重新读取状态，教学入口仍依赖账号接口能力。 */
async function submit() {
  if (!canSubmit.value || submitting.value) return;
  const active = new AbortController(); submitController = active;
  errorMessage.value = ""; successMessage.value = ""; submitting.value = true;
  try { const result = await accountSecurityApi.submitTeacherApplication({ clientRequestId: randomRequestId(), expectedRevision: application.value?.revision ?? null, qualificationMediaId: mediaId.value, statement: statement.value }, active.signal); if (!active.signal.aborted) { application.value = result; successMessage.value = "申请已提交，请等待审核。"; await refresh(); } }
  catch (error) { if (!active.signal.aborted) { errorMessage.value = accountError(error, "申请未提交，请检查资质和说明后重试。").message; if ((error as { status?: number }).status === 409) await refresh(); } }
  finally { if (!active.signal.aborted) submitting.value = false; }
}

/** 本地计算上传校验值，不向其他领域发送文件内容。 */
async function sha256(file: File, signal: AbortSignal): Promise<string> { if (signal.aborted) throw new DOMException("Aborted", "AbortError"); const buffer = await file.arrayBuffer(); const digest = await crypto.subtle.digest("SHA-256", buffer); return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join(""); }
/** 仅使用媒体签发的上传方法和头；完成后移除取消监听。 */
function put(grant: MediaUploadGrant, file: File, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new DOMException("Aborted", "AbortError")); return; }
    const xhr = new XMLHttpRequest(); const abort = () => xhr.abort();
    const clean = () => signal.removeEventListener("abort", abort);
    xhr.open(grant.method, grant.url);
    Object.entries(grant.headers).forEach(([key, value]) => xhr.setRequestHeader(key, value));
    xhr.onload = () => { clean(); xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error("QUALIFICATION_UPLOAD_FAILED")); };
    xhr.onerror = () => { clean(); reject(new Error("QUALIFICATION_UPLOAD_NETWORK")); };
    xhr.onabort = () => { clean(); reject(new DOMException("Aborted", "AbortError")); };
    signal.addEventListener("abort", abort, { once: true }); xhr.send(file);
  });
}
/** 不可见时暂停处理状态查询；恢复可见或离页取消都会移除临时监听，不循环重试。 */
function waitVisible(signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.reject(new DOMException("Aborted", "AbortError"));
  if (!document.hidden) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const clean = () => { document.removeEventListener("visibilitychange", visible); signal.removeEventListener("abort", abort); };
    const visible = () => { if (!document.hidden) { clean(); resolve(); } };
    const abort = () => { clean(); reject(new DOMException("Aborted", "AbortError")); };
    document.addEventListener("visibilitychange", visible); signal.addEventListener("abort", abort, { once: true });
  });
}
/** 有界处理等待；到期和取消都移除监听，离页不留下定时器。 */
function delay(milliseconds: number, signal: AbortSignal): Promise<void> { return new Promise((resolve, reject) => { const abort = () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); }; const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, milliseconds); if (signal.aborted) abort(); else signal.addEventListener("abort", abort, { once: true }); }); }
</script>

<template>
  <section class="content-page apply-page" aria-labelledby="apply-title"><span class="badge">教师申请</span><h1 id="apply-title">申请教师能力</h1><p class="page-lead">分享你的教学经验与专业资质。审核进展会在这里更新，也会通过消息通知你。</p>
    <p v-if="loading" role="status">正在读取最新申请与账号能力…</p>
    <p v-if="statusError" class="panel-error" role="alert">{{ statusError }}</p><button class="button button--quiet" :disabled="loading" @click="refresh">刷新申请状态</button>
    <article v-if="application" class="application-status"><header><strong>当前申请：{{ applicationLabels[application.status] }}</strong></header><p v-if="application.rejectionReason">未通过原因：{{ application.rejectionReason }}</p><p>最近更新：{{ new Date(application.updatedAt).toLocaleString() }}</p></article>
    <a v-if="teaching" :href="teaching.href" class="button button--primary">进入教学空间 →</a>
    <p v-else-if="application?.status === 'APPROVED' && ready" class="account-notice" role="status">申请已通过，当前账号教学能力尚未就绪，请稍后刷新。</p>
    <p v-if="hasTeachingAccess" class="account-notice" role="status">当前账号已具备教学能力，本页只读展示申请事实，无需重复申请。</p>
    <p v-else-if="application && !canSubmit" class="account-notice" role="status">当前申请正在处理或已经通过，暂不能重复提交。</p>
    <form v-if="canSubmit" class="account-form application-form" @submit.prevent="submit"><label><span>资质文件</span><input type="file" accept="image/*,.pdf" :disabled="uploading" @change="chooseFile"><small>支持图片或 PDF，最大 5 MB；重新申请请重新上传有效资质。</small></label><p v-if="uploadStatus" class="account-hint" role="status">{{ uploadStatus }}</p><p v-if="uploadError" class="panel-error" role="alert">{{ uploadError }}</p><label><span>申请说明</span><textarea v-model="statement" minlength="20" maxlength="2000" rows="7" placeholder="请说明你的教学经历和申请理由（20～2000 字）" required></textarea></label><button class="button button--primary" type="submit" :disabled="uploading || submitting || !mediaId || statement.trim().length < 20">{{ submitting ? "正在提交…" : application?.status === 'REJECTED' ? "重新提交申请" : "提交申请" }}</button></form>
    <p v-if="successMessage" class="account-notice" role="status">{{ successMessage }}</p><p v-if="errorMessage" class="panel-error" role="alert">{{ errorMessage }}</p><nav class="account-links"><RouterLink to="/profile">返回个人中心</RouterLink><RouterLink to="/profile/security">账号安全</RouterLink></nav>
  </section>
</template>
