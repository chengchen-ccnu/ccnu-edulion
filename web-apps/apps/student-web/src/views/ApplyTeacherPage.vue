<script setup lang="ts">
/**
 * 教师申请页：上传本人资质到 media-service 后提交申请事实。
 * 页面只创建/重提申请并展示 T16 的状态投影，不做审核决定、不授予教学能力，也不修改完整个人资料。
 */
import type { MediaAsset, MediaUploadGrant, TeacherApplication } from "@edu/api-client";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { accountSecurityApi } from "../account-security-api";
import { accountError, randomRequestId } from "../account-errors";
import { authStore } from "../auth";

const application = ref<TeacherApplication | null>(null);
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
const hasTeachingAccess = computed(() => authStore.state.currentUser?.permissions.includes("teaching:access") ?? false);
const canSubmit = computed(() => !hasTeachingAccess.value
  && (!application.value || application.value.status === "REJECTED"));

onMounted(async () => { try { application.value = await accountSecurityApi.teacherApplication(); if (application.value) { mediaId.value = application.value.qualificationMediaId; statement.value = application.value.statement; } } catch (error) { errorMessage.value = accountError(error, "申请状态加载失败，请稍后重试。").message; } });
onBeforeUnmount(() => controller?.abort());

async function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  selectedFile.value = file; mediaId.value = ""; uploadError.value = ""; uploadStatus.value = "";
  if (file.size > 5 * 1024 * 1024) { uploadError.value = "资质文件不能超过 5 MB。"; return; }
  await uploadQualification(file);
}

async function uploadQualification(file: File) {
  uploading.value = true; controller = new AbortController();
  try {
    uploadStatus.value = "正在计算文件校验值…";
    const digest = await sha256(file, controller.signal);
    const session = await accountSecurityApi.createMediaUpload({ clientRequestId: randomRequestId(), purpose: "TEACHER_QUALIFICATION", fileName: file.name, size: file.size, mimeType: file.type || "application/octet-stream", sha256: digest });
    if (session.mediaId) { mediaId.value = session.mediaId; uploadStatus.value = "资质已就绪。"; return; }
    if (!session.uploadGrant || session.mode !== "SINGLE_PUT") throw new Error("QUALIFICATION_UPLOAD_MODE_UNSUPPORTED");
    uploadStatus.value = "正在安全上传资质文件…";
    await put(session.uploadGrant, file, controller.signal);
    const asset = await accountSecurityApi.completeUpload(session.uploadId, { sha256: digest, parts: [] });
    const ready = await waitReady(asset, controller.signal);
    mediaId.value = ready.mediaId; uploadStatus.value = "资质已就绪，可以提交申请。";
  } catch (error) { if (!controller.signal.aborted) uploadError.value = error instanceof Error && error.message === "QUALIFICATION_UPLOAD_MODE_UNSUPPORTED" ? "当前资质文件需要分片上传，请选择较小的文件重试。" : accountError(error, "资质上传未完成，请稍后重试。").message; }
  finally { uploading.value = false; controller = null; }
}

async function waitReady(asset: MediaAsset, signal: AbortSignal): Promise<MediaAsset> {
  let current = asset;
  for (let attempt = 0; attempt < 80 && current.status !== "READY"; attempt += 1) {
    await delay(1500, signal); current = await accountSecurityApi.getMedia(asset.mediaId, signal);
  }
  if (current.status !== "READY") throw new Error("QUALIFICATION_PROCESSING_TIMEOUT");
  return current;
}

async function submit() {
  errorMessage.value = ""; successMessage.value = ""; submitting.value = true;
  try { application.value = await accountSecurityApi.submitTeacherApplication({ clientRequestId: randomRequestId(), expectedRevision: application.value?.revision ?? null, qualificationMediaId: mediaId.value, statement: statement.value }); successMessage.value = "申请已提交，当前只记录为待审核状态。"; }
  catch (error) { errorMessage.value = accountError(error, "申请未提交，请检查资质和说明后重试。").message; }
  finally { submitting.value = false; }
}

async function sha256(file: File, signal: AbortSignal): Promise<string> { if (signal.aborted) throw new DOMException("Aborted", "AbortError"); const buffer = await file.arrayBuffer(); const digest = await crypto.subtle.digest("SHA-256", buffer); return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join(""); }
function put(grant: MediaUploadGrant, file: File, signal: AbortSignal): Promise<void> { return new Promise((resolve, reject) => { const xhr = new XMLHttpRequest(); const abort = () => xhr.abort(); xhr.open(grant.method, grant.url); Object.entries(grant.headers).forEach(([key, value]) => xhr.setRequestHeader(key, value)); xhr.onload = () => { signal.removeEventListener("abort", abort); xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error("QUALIFICATION_UPLOAD_FAILED")); }; xhr.onerror = () => reject(new Error("QUALIFICATION_UPLOAD_NETWORK")); xhr.onabort = () => reject(new DOMException("Aborted", "AbortError")); signal.addEventListener("abort", abort, { once: true }); xhr.send(file); }); }
function delay(milliseconds: number, signal: AbortSignal): Promise<void> { return new Promise((resolve, reject) => { const timer = setTimeout(resolve, milliseconds); signal.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("Aborted", "AbortError")); }, { once: true }); }); }
</script>

<template>
  <section class="content-page apply-page" aria-labelledby="apply-title"><span class="badge">教师申请</span><h1 id="apply-title">申请教师能力</h1><p class="page-lead">提交资质文件和申请说明。T12E 只负责提交事实，审核结果与教学能力授予由后续管理流程决定。</p>
    <article v-if="application" class="application-status"><header><strong>当前申请：{{ application.status }}</strong><span>版本 {{ application.revision }}</span></header><p v-if="application.rejectionReason">驳回原因：{{ application.rejectionReason }}</p><p>最近更新：{{ new Date(application.updatedAt).toLocaleString() }}</p></article>
    <p v-if="hasTeachingAccess" class="account-notice" role="status">当前账号已具备教学能力，本页只读展示申请事实，无需重复申请。</p>
    <p v-else-if="application && !canSubmit" class="account-notice" role="status">当前申请正在处理或已经通过，暂不能重复提交。</p>
    <form v-if="canSubmit" class="account-form application-form" @submit.prevent="submit"><label><span>资质文件</span><input type="file" accept="image/*,.pdf" :disabled="uploading" @change="chooseFile"><small>支持图片或 PDF，最大 5 MB；文件经 media-service 校验后只保存稳定 mediaId。</small></label><p v-if="uploadStatus" class="account-hint" role="status">{{ uploadStatus }}</p><p v-if="uploadError" class="panel-error" role="alert">{{ uploadError }}</p><label><span>申请说明</span><textarea v-model="statement" minlength="20" maxlength="2000" rows="7" placeholder="请说明你的教学经历和申请理由（20～2000 字）" required></textarea></label><button class="button button--primary" type="submit" :disabled="uploading || submitting || !mediaId || statement.trim().length < 20">{{ submitting ? "正在提交…" : application?.status === 'REJECTED' ? "重新提交申请" : "提交申请" }}</button></form>
    <p v-if="successMessage" class="account-notice" role="status">{{ successMessage }}</p><p v-if="errorMessage" class="panel-error" role="alert">{{ errorMessage }}</p><nav class="account-links"><RouterLink to="/profile">返回个人中心</RouterLink><RouterLink to="/profile/security">账号安全</RouterLink></nav>
  </section>
</template>
