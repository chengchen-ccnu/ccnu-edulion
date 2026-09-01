<script setup lang="ts">
/**
 * ARTICLE Markdown 编辑器；输入为草稿正文与已绑定图片，输出仅是正文变更和 READY 图片信息。
 * 工具栏不生成任意 HTML，正文图片必须通过同一 media-service 上传控件取得稳定 mediaId。
 */
import type { CourseMediaAccessGrant } from "@edu/api-client";
import { SafeArticleContent } from "@edu/ui";
import type { ArticleContentImage } from "@edu/ui";
import { ref } from "vue";

import MediaUploadField from "./MediaUploadField.vue";

const props = defineProps<{
  modelValue: string;
  images: ArticleContentImage[];
  lessonId?: string;
  checkpointKey: string;
  resolveImage: (mediaId: string, signal: AbortSignal) => Promise<CourseMediaAccessGrant>;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: string];
  imageReady: [value: { mediaId: string; fileName: string; mimeType: string; size: number; previewUrl: string | null }];
}>();
const textarea = ref<HTMLTextAreaElement | null>(null);
const uploader = ref<{ browse(): void } | null>(null);
const pendingMediaId = ref<string | null>(null);

function insert(prefix: string, suffix = "", placeholder = "正文") {
  const element = textarea.value;
  const start = element?.selectionStart ?? props.modelValue.length;
  const end = element?.selectionEnd ?? start;
  const selected = props.modelValue.slice(start, end) || placeholder;
  const value = `${props.modelValue.slice(0, start)}${prefix}${selected}${suffix}${props.modelValue.slice(end)}`;
  emit("update:modelValue", value);
  requestAnimationFrame(() => {
    element?.focus();
    const cursor = start + prefix.length + selected.length + suffix.length;
    element?.setSelectionRange(cursor, cursor);
  });
}

function insertTable() {
  insert("\n| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |\n", "", "");
}

function insertLink() {
  const href = window.prompt("请输入 HTTPS 链接：", "https://");
  if (!href) return;
  try {
    if (new URL(href).protocol !== "https:") throw new Error("HTTPS_REQUIRED");
  } catch {
    window.alert("只允许绝对 HTTPS 链接。");
    return;
  }
  insert("[", `](${href})`, "链接文字");
}

function requestImage() {
  if (!props.lessonId) return;
  uploader.value?.browse();
}

function handleImageReady(value: { mediaId: string; fileName: string; mimeType: string; size: number; previewUrl: string | null }) {
  pendingMediaId.value = value.mediaId;
  emit("imageReady", value);
  const alt = value.fileName.replace(/\.[^.]+$/, "") || "正文图片";
  insert("\n![", `](media:${value.mediaId})\n`, alt);
}
</script>

<template>
  <section class="article-editor">
    <div class="article-editor__toolbar" role="toolbar" aria-label="图文课时编辑工具">
      <button type="button" title="二级标题" @click="insert('\n## ', '\n', '标题')">标题</button>
      <button type="button" title="无序列表" @click="insert('\n- ', '\n', '列表项')">列表</button>
      <button type="button" title="GFM 表格" @click="insertTable">表格</button>
      <button type="button" title="LaTeX 公式" @click="insert('\n$$\n', '\n$$\n', 'x^2 + y^2')">LaTeX</button>
      <button type="button" title="代码块" @click="insert('\n```text\n', '\n```\n', '代码')">代码</button>
      <button type="button" title="HTTPS 链接" @click="insertLink">链接</button>
      <button type="button" title="上传并插入正文图片" :disabled="!lessonId" @click="requestImage">正文图片</button>
    </div>
    <div class="article-editor__panes">
      <label><span>Markdown 编辑区</span><textarea ref="textarea" :value="modelValue" rows="18" maxlength="100000"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" /></label>
      <section aria-label="图文课时实时预览"><strong>实时预览</strong>
        <SafeArticleContent :source="modelValue" :images="images" :resolve-image="resolveImage" /></section>
    </div>
    <MediaUploadField ref="uploader" class="article-editor__uploader" :model-value="pendingMediaId"
      purpose="ARTICLE_IMAGE" accept="image/jpeg,image/png,image/webp" label="正文图片上传"
      :checkpoint-key="checkpointKey" :disabled="!lessonId" @ready="handleImageReady"
      @update:model-value="pendingMediaId = $event" />
    <small v-if="!lessonId">请先显式保存目录生成课时 ID，再从工具栏上传正文图片。</small>
  </section>
</template>

<style scoped>
.article-editor { display: grid; gap: 12px; }
.article-editor__toolbar { display: flex; flex-wrap: wrap; gap: 7px; }
.article-editor__toolbar button { min-height: 36px; padding: 0 11px; color: #31527f; background: #fff; border: 1px solid #cad7e9; border-radius: 8px; cursor: pointer; }
.article-editor__toolbar button:focus-visible { outline: 3px solid rgba(31,94,255,.25); outline-offset: 2px; }
.article-editor__toolbar button:disabled { cursor: not-allowed; opacity: .45; }
.article-editor__panes { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1fr); gap: 14px; }
.article-editor__panes > label,.article-editor__panes > section { display: grid; align-content: start; gap: 7px; min-width: 0; }
.article-editor__panes textarea { width: 100%; min-height: 400px; padding: 14px; resize: vertical; color: #25344e; background: #fbfcff; border: 1px solid #d3ddec; border-radius: 10px; font-family: ui-monospace,SFMono-Regular,Consolas,monospace; line-height: 1.6; }
.article-editor__panes > section { overflow: auto; min-height: 400px; padding: 14px; background: #fff; border: 1px solid #dfe6f0; border-radius: 10px; }
.article-editor__uploader { max-width: 560px; }
@media (max-width: 900px) { .article-editor__panes { grid-template-columns: 1fr; } }
</style>
