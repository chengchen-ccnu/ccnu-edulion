<script setup lang="ts">
/**
 * 短期授权 HLS 播放器；输入是一次性授权获取函数，输出是原生 video 播放体验和可理解的失败反馈。
 * 组件优先使用浏览器原生 HLS，其他支持 MSE 的浏览器使用 hls.js；授权 URL、请求头仅保存在内存。
 * 切换资源或卸载时会中止授权请求、清理刷新定时器、销毁播放器并移除 video 的临时地址。
 */
import type { CourseMediaPlaybackGrant } from "@edu/api-client";
import type Hls from "hls.js";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

import { playbackErrorMessage, playbackRefreshDelay, requestPlaybackGrant } from "./playback-runtime";

const props = defineProps<{
  grantKey: string;
  grantProvider: (signal: AbortSignal) => Promise<CourseMediaPlaybackGrant>;
  title: string;
}>();

const video = ref<HTMLVideoElement | null>(null);
const loading = ref(false);
const errorMessage = ref("");
let hls: Hls | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let grantAbort: AbortController | null = null;
let generation = 0;

watch(() => props.grantKey, () => { void loadGrant(false); }, { immediate: true });
onBeforeUnmount(cleanup);

/** 获取或刷新播放授权；429 最多退避两次，其他错误立即停止，避免形成无界刷新循环。 */
async function loadGrant(preservePosition: boolean) {
  const currentGeneration = ++generation;
  clearRuntime(!preservePosition);
  loading.value = true;
  errorMessage.value = "";
  const controller = new AbortController();
  grantAbort = controller;
  try {
    const grant = await requestPlaybackGrant(props.grantProvider, controller.signal);
    if (currentGeneration !== generation || controller.signal.aborted) return;
    await nextTick();
    const attached = await attachGrant(grant, preservePosition,
      () => currentGeneration === generation && !controller.signal.aborted);
    if (!attached) return;
    scheduleRefresh(grant);
  } catch (error) {
    if (!controller.signal.aborted && currentGeneration === generation) errorMessage.value = playbackErrorMessage(error);
  } finally {
    if (currentGeneration === generation) loading.value = false;
  }
}

async function attachGrant(grant: CourseMediaPlaybackGrant, preservePosition: boolean,
    isCurrent: () => boolean): Promise<boolean> {
  const element = video.value;
  if (!element) return false;
  const previousTime = preservePosition ? element.currentTime : 0;
  const wasPaused = element.paused;
  const requiredHeaders = grant.headers ?? {};
  const restore = () => {
    if (previousTime > 0 && Number.isFinite(previousTime)) element.currentTime = previousTime;
    if (!wasPaused) void element.play().catch(() => undefined);
  };
  if (element.canPlayType("application/vnd.apple.mpegurl") && Object.keys(requiredHeaders).length === 0) {
    element.src = grant.url;
    element.addEventListener("loadedmetadata", restore, { once: true });
    element.load();
    return true;
  }
  const HlsModule = await import("hls.js");
  if (!isCurrent()) return false;
  const HlsClass = HlsModule.default;
  if (!HlsClass.isSupported()) throw new Error("HLS_UNSUPPORTED");
  hls = new HlsClass({
    xhrSetup(xhr) {
      Object.entries(requiredHeaders).forEach(([name, value]) => xhr.setRequestHeader(name, value));
    }
  });
  hls.on(HlsClass.Events.ERROR, (_event, data) => {
    if (!data.fatal) return;
    if (data.type === HlsModule.ErrorTypes.NETWORK_ERROR) errorMessage.value = "视频流暂时无法读取，请重新获取播放授权。";
    else errorMessage.value = "浏览器无法继续解码该视频，请重试或更换浏览器。";
    clearRuntime(false);
  });
  hls.on(HlsClass.Events.MANIFEST_PARSED, restore);
  hls.loadSource(grant.url);
  hls.attachMedia(element);
  return true;
}

function scheduleRefresh(grant: CourseMediaPlaybackGrant) {
  const delay = playbackRefreshDelay(grant.expiresAt);
  if (delay === null) {
    errorMessage.value = "播放授权已过期，请重新获取。";
    return;
  }
  refreshTimer = setTimeout(() => { void loadGrant(true); }, delay);
}

function retry() {
  void loadGrant(true);
}

function cleanup() {
  generation += 1;
  clearRuntime(true);
}

function clearRuntime(removeSource: boolean) {
  grantAbort?.abort();
  grantAbort = null;
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = null;
  hls?.destroy();
  hls = null;
  if (removeSource && video.value) {
    video.value.pause();
    video.value.removeAttribute("src");
    video.value.load();
  }
}

</script>

<template>
  <section class="hls-player" :aria-busy="loading">
    <video ref="video" controls playsinline preload="metadata" :aria-label="title" />
    <div v-if="loading" class="hls-player__status" role="status">正在获取短期播放授权…</div>
    <div v-else-if="errorMessage" class="hls-player__error" role="alert">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="retry">重新尝试</button>
    </div>
  </section>
</template>

<style scoped>
.hls-player { display: grid; gap: 10px; width: 100%; }
.hls-player video { width: 100%; aspect-ratio: 16 / 9; background: #101522; border-radius: 14px; }
.hls-player__status,.hls-player__error { padding: 11px 13px; border-radius: 9px; font-size: .8rem; }
.hls-player__status { color: #365b91; background: #edf4ff; }
.hls-player__error { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #922b3a; background: #fff2f4; }
.hls-player__error button { min-height: 36px; padding: 0 12px; color: inherit; background: #fff; border: 1px solid #efbdc5; border-radius: 8px; cursor: pointer; }
</style>
