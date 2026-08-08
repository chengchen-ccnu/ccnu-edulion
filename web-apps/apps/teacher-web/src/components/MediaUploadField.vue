<script setup lang="ts">
/**
 * 教师媒体上传控件；props 指定 OpenAPI 用途、会话键和可选展示投影，emit 只回写 READY mediaId。
 * 本地图片预览使用 Blob URL，并在替换、移除和卸载时 revoke；检查点不包含任何签名 URL。
 */
import type { MediaPurpose } from "@edu/api-client";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";

import { teacherMediaApi } from "../media-api";
import { MediaUploader } from "../media-upload";
import type { MediaUploadViewState } from "../media-upload";

const props = defineProps<{
  modelValue: string | null;
  purpose: Extract<MediaPurpose, "COURSE_COVER" | "LESSON_VIDEO">;
  checkpointKey: string;
  accept: string;
  label: string;
  currentUrl?: string | null;
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string | null]; "busy-change": [value: boolean] }>();

const input = ref<HTMLInputElement | null>(null);
const selectedFile = ref<File | null>(null);
const previewUrl = ref<string | null>(null);
const state = ref<MediaUploadViewState>({ stage: "IDLE", progress: 0, uploadedBytes: 0, totalBytes: 0,
  statusLabel: "", errorMessage: "" });
const uploader = new MediaUploader({ api: teacherMediaApi, purpose: props.purpose,
  checkpointKey: props.checkpointKey, onChange: next => {
    state.value = next;
    if (next.stage === "READY" && next.mediaId) emit("update:modelValue", next.mediaId);
  } });
const busy = computed(() => ["RESTORING", "HASHING", "CREATING", "UPLOADING", "COMPLETING", "PROCESSING"]
  .includes(state.value.stage));
const percent = computed(() => Math.round(state.value.progress * 100));
const displayUrl = computed(() => previewUrl.value ?? props.currentUrl ?? null);
const placeholderLabel = computed(() => state.value.fileName ?? (props.modelValue
  ? props.purpose === "COURSE_COVER" ? "已绑定课程封面，展示地址暂不可用" : "已绑定课时视频"
  : "尚未选择文件"));

onMounted(() => { void uploader.restore(); });
onBeforeUnmount(() => { uploader.dispose(); revokePreview(); emit("busy-change", false); });
watch(busy, value => emit("busy-change", value), { immediate: true });

async function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  selectedFile.value = file;
  if (props.purpose === "COURSE_COVER") {
    revokePreview();
    previewUrl.value = URL.createObjectURL(file);
  }
  const resumesSameFile = state.value.stage === "NEEDS_FILE"
    || state.value.stage === "FAILED" && state.value.fileName === file.name && state.value.totalBytes === file.size;
  if (!resumesSameFile) {
    if (state.value.uploadId && state.value.stage !== "READY") await uploader.cancel();
    uploader.clearCheckpoint();
  }
  await startUpload();
}

async function startUpload() {
  if (!selectedFile.value || busy.value) return;
  try { await uploader.upload(selectedFile.value); } catch { /* 状态机已经提供可理解的页面错误。 */ }
}

async function cancel() {
  await uploader.cancel();
}

async function remove() {
  if (busy.value || state.value.uploadId && state.value.stage !== "READY") await uploader.cancel();
  uploader.clearCheckpoint();
  emit("update:modelValue", null);
  selectedFile.value = null;
  if (input.value) input.value.value = "";
  revokePreview();
  state.value = { stage: "IDLE", progress: 0, uploadedBytes: 0, totalBytes: 0,
    statusLabel: props.purpose === "COURSE_COVER"
      ? "课程封面已从当前编辑表单移除，保存后才会解除课程引用。"
      : "课时视频已从当前编辑表单移除，保存后才会解除目录引用。",
    errorMessage: "" };
}

function browse() {
  input.value?.click();
}

function revokePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = null;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}
</script>

<template>
  <section class="media-upload-field" :aria-busy="busy">
    <header>
      <div><strong>{{ label }}</strong><small>{{ purpose === "COURSE_COVER" ? "图片直传 OSS，READY 后绑定" : "视频直传 OSS 并转码 HLS，READY 后绑定" }}</small></div>
      <span v-if="modelValue" class="media-upload-field__ready">READY</span>
    </header>
    <img v-if="purpose === 'COURSE_COVER' && displayUrl" class="media-upload-field__preview" :src="displayUrl" alt="课程封面预览">
    <div v-else class="media-upload-field__placeholder">
      <strong>{{ placeholderLabel }}</strong>
      <span v-if="state.totalBytes">{{ formatBytes(state.totalBytes) }}</span>
    </div>
    <input ref="input" class="visually-hidden" type="file" :accept="accept" :disabled="disabled || busy" @change="chooseFile">
    <div v-if="busy || state.progress > 0" class="media-upload-field__progress">
      <progress :value="state.progress" max="1" :aria-label="`${label}上传进度 ${percent}%`" />
      <span>{{ percent }}%<template v-if="state.stage === 'UPLOADING'"> · {{ formatBytes(state.uploadedBytes) }} / {{ formatBytes(state.totalBytes) }}</template></span>
    </div>
    <p v-if="state.statusLabel" class="media-upload-field__status" role="status">{{ state.statusLabel }}</p>
    <p v-if="state.errorMessage" class="media-upload-field__error" role="alert">{{ state.errorMessage }}</p>
    <div class="media-upload-field__actions">
      <button type="button" :disabled="disabled || busy" @click="browse">{{ modelValue ? "替换" : "选择文件" }}</button>
      <button v-if="state.stage === 'FAILED' && selectedFile" type="button" :disabled="busy" @click="startUpload">有限重试</button>
      <button v-if="busy || state.stage === 'NEEDS_FILE'" type="button" @click="cancel">取消上传</button>
      <button v-if="modelValue || displayUrl" class="danger" type="button" :disabled="busy" @click="remove">移除</button>
    </div>
  </section>
</template>

<style scoped>
.media-upload-field { display: grid; gap: 12px; padding: 16px; background: #f8faff; border: 1px solid #dce5f1; border-radius: 14px; }
.media-upload-field header { display: flex; align-items: start; justify-content: space-between; gap: 12px; }
.media-upload-field header div { display: grid; gap: 4px; }.media-upload-field header small { color: #738099; font-size: .68rem; font-weight: 500; }
.media-upload-field__ready { padding: 4px 8px; color: #116b4b; background: #e5f7ef; border-radius: 999px; font-size: .65rem; font-weight: 850; }
.media-upload-field__preview { width: min(100%, 440px); aspect-ratio: 16 / 9; object-fit: cover; background: #e9eef6; border-radius: 10px; }
.media-upload-field__placeholder { display: flex; justify-content: space-between; gap: 12px; padding: 20px; color: #64718a; background: #eef3fa; border-radius: 10px; font-size: .78rem; }
.media-upload-field__progress { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 10px; align-items: center; font-size: .72rem; }
.media-upload-field__progress progress { width: 100%; accent-color: var(--edu-color-primary); }
.media-upload-field__status,.media-upload-field__error { margin: 0; font-size: .72rem; line-height: 1.55; }.media-upload-field__status { color: #4c5d79; }.media-upload-field__error { color: #a12b40; }
.media-upload-field__actions { display: flex; flex-wrap: wrap; gap: 8px; }.media-upload-field__actions button { min-height: 36px; padding: 0 12px; color: var(--edu-color-primary); background: #fff; border: 1px solid #cbd9ed; border-radius: 8px; font-weight: 750; cursor: pointer; }.media-upload-field__actions button:disabled { cursor: not-allowed; opacity: .45; }.media-upload-field__actions .danger { color: #a52b3e; border-color: #efcbd1; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
</style>
