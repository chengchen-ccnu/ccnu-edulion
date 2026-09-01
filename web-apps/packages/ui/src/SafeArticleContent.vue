<script setup lang="ts">
/**
 * ARTICLE 正文安全渲染器；输入是受控 Markdown、OpenAPI 正文图片投影及可选授权解析函数。
 * 所有短期地址只用于抓取 Blob；图片全部成功落到 Blob URL 后才发出 rendered，失败则阻止完成事实写入。
 */
import type { CourseMediaAccessGrant, LessonInlineImage } from "@edu/api-client";
import "katex/dist/katex.min.css";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

import { renderArticleMarkdown } from "./render-article-markdown";

export type ArticleImageResolver = (mediaId: string, signal: AbortSignal) => Promise<CourseMediaAccessGrant>;
export type ArticleContentImage = Pick<LessonInlineImage, "mediaId"> & Partial<Pick<LessonInlineImage, "grant" | "resourceId">>;

const props = defineProps<{
  source: string;
  images: ArticleContentImage[];
  resolveImage?: ArticleImageResolver;
}>();
const emit = defineEmits<{ rendered: []; renderError: [message: string] }>();
const host = ref<HTMLElement | null>(null);
const html = ref("");
const errorMessage = ref("");
let generation = 0;
let controller: AbortController | null = null;
const objectUrls = new Set<string>();

watch(() => [props.source, props.images, props.resolveImage] as const, render, { immediate: true, deep: true });
onBeforeUnmount(cleanup);

async function render() {
  const currentGeneration = ++generation;
  cleanupRuntime();
  errorMessage.value = "";
  try {
    html.value = renderArticleMarkdown(props.source);
    await nextTick();
    if (currentGeneration !== generation) return;
    const placeholders = [...(host.value?.querySelectorAll<HTMLElement>("[data-article-media-id]") ?? [])];
    const imageMap = new Map(props.images.map(image => [image.mediaId.toLowerCase(), image]));
    controller = new AbortController();
    await Promise.all(placeholders.map(node => hydrateImage(node, imageMap, controller!.signal)));
    if (currentGeneration === generation && !controller.signal.aborted) emit("rendered");
  } catch (error) {
    if (currentGeneration !== generation || controller?.signal.aborted) return;
    errorMessage.value = error instanceof Error ? error.message : "正文渲染失败。";
    emit("renderError", errorMessage.value);
  }
}

async function hydrateImage(node: HTMLElement, imageMap: Map<string, ArticleContentImage>, signal: AbortSignal) {
  const mediaId = node.dataset.articleMediaId?.toLowerCase() ?? "";
  const image = imageMap.get(mediaId);
  if (!image) throw new Error("正文图片引用无效，未记录完成状态。");
  const grant = props.resolveImage ? await props.resolveImage(mediaId, signal) : image.grant;
  if (!grant) throw new Error("正文图片尚未绑定，保存目录后可读取预览。");
  const response = await fetch(grant.url, { method: grant.method, headers: grant.headers, signal, cache: "no-store" });
  if (!response.ok) throw new Error("正文图片暂时无法读取，未记录完成状态。");
  const objectUrl = URL.createObjectURL(await response.blob());
  objectUrls.add(objectUrl);
  const rendered = document.createElement("img");
  rendered.alt = node.dataset.articleAlt ?? "";
  rendered.decoding = "async";
  await waitForImage(rendered, objectUrl, signal);
  rendered.loading = "lazy";
  node.replaceWith(rendered);
}

/** 等待浏览器真正解码 Blob 图片；损坏内容和离页中止都必须阻止 ARTICLE 完成事实。 */
function waitForImage(image: HTMLImageElement, source: string, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const finish = (error?: Error) => {
      image.onload = null;
      image.onerror = null;
      signal.removeEventListener("abort", abort);
      error ? reject(error) : resolve();
    };
    const abort = () => finish(new DOMException("正文图片加载已取消。", "AbortError"));
    image.onload = () => finish();
    image.onerror = () => finish(new Error("正文图片解码失败，未记录完成状态。"));
    signal.addEventListener("abort", abort, { once: true });
    image.src = source;
    if (signal.aborted) abort();
  });
}

function cleanup() {
  generation += 1;
  cleanupRuntime();
}

function cleanupRuntime() {
  controller?.abort();
  controller = null;
  objectUrls.forEach(url => URL.revokeObjectURL(url));
  objectUrls.clear();
}
</script>

<template>
  <div class="safe-article-content">
    <div ref="host" class="safe-article-content__body" v-html="html" />
    <p v-if="errorMessage" class="safe-article-content__error" role="alert">{{ errorMessage }}</p>
  </div>
</template>

<style scoped>
.safe-article-content__body { color: #27354d; font-size: 1rem; line-height: 1.8; overflow-wrap: anywhere; }
.safe-article-content__body :deep(h1),.safe-article-content__body :deep(h2),.safe-article-content__body :deep(h3) { margin: 1.4em 0 .55em; color: #17243a; line-height: 1.3; }
.safe-article-content__body :deep(table) { display: block; width: 100%; overflow-x: auto; border-collapse: collapse; }
.safe-article-content__body :deep(th),.safe-article-content__body :deep(td) { padding: 8px 11px; border: 1px solid #d8e0ec; text-align: left; }
.safe-article-content__body :deep(pre) { overflow-x: auto; padding: 15px; color: #eef4ff; background: #172238; border-radius: 10px; }
.safe-article-content__body :deep(code) { font-family: ui-monospace,SFMono-Regular,Consolas,monospace; }
.safe-article-content__body :deep(img) { display: block; max-width: 100%; height: auto; margin: 18px auto; border-radius: 10px; }
.safe-article-content__body :deep(a) { color: #1f5eff; text-decoration: underline; text-underline-offset: 3px; }
.safe-article-content__body :deep(.article-inline-image) { display: block; padding: 24px; color: #65728a; background: #f1f4f9; border-radius: 10px; text-align: center; }
.safe-article-content__error { padding: 11px 13px; color: #922b3a; background: #fff2f4; border-radius: 9px; }
</style>
