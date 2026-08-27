<script setup lang="ts">
/**
 * 短期授权 HLS 播放器；输入是一次性授权获取函数和可选业务错误映射，输出是原生 video 播放体验。
 * 组件优先使用浏览器原生 HLS，其他支持 MSE 的浏览器使用 hls.js；授权 URL、请求头仅保存在内存。
 * 共享组件不判断教师或学生权限；403 等角色语义由调用页面传入，并只向外发出领域中立播放样本。
 * 切换资源或卸载时会中止授权请求、清理刷新定时器、销毁播放器并移除 video 的临时地址。
 */
import type { CourseMediaPlaybackGrant } from "@edu/api-client";
import type Hls from "hls.js";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

import { playbackErrorMessage, playbackRefreshDelay, requestPlaybackGrant } from "./playback-runtime";
import { createPlaybackProgressTracker } from "./playback-progress";
import type { PlaybackProgressSample } from "./playback-progress";

const props = defineProps<{
  grantKey: string;
  grantProvider: (signal: AbortSignal) => Promise<CourseMediaPlaybackGrant>;
  errorMessageProvider?: (error: unknown) => string;
  initialPositionSeconds?: number;
  title: string;
}>();
const emit = defineEmits<{
  playbackProgress: [sample: PlaybackProgressSample];
  positionChange: [positionSeconds: number];
  durationChange: [durationSeconds: number];
}>();

const video = ref<HTMLVideoElement | null>(null);
const loading = ref(false);
const errorMessage = ref("");
let hls: Hls | null = null;
let activeHeaders: Record<string, string> = {};
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let grantAbort: AbortController | null = null;
let generation = 0;
let lastPositionSecond = -1;
const progressTracker = createPlaybackProgressTracker(sample => emit("playbackProgress", sample));

watch(() => props.grantKey, () => {
  progressTracker.flush();
  progressTracker.reset();
  void loadGrant(false);
}, { immediate: true });
onBeforeUnmount(cleanup);

/** 获取或刷新播放授权；429 最多退避两次，其他错误立即停止，避免形成无界刷新循环。 */
async function loadGrant(preservePosition: boolean) {
  const currentGeneration = ++generation;
  if (preservePosition) clearGrantRequest();
  else clearRuntime(true);
  loading.value = !preservePosition;
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
    if (!controller.signal.aborted && currentGeneration === generation) {
      errorMessage.value = props.errorMessageProvider?.(error) ?? playbackErrorMessage(error);
    }
  } finally {
    if (currentGeneration === generation) loading.value = false;
  }
}

async function attachGrant(grant: CourseMediaPlaybackGrant, preservePosition: boolean,
    isCurrent: () => boolean): Promise<boolean> {
  const element = video.value;
  if (!element) return false;
  // 初始位置只在首次装载/切换资源时读取；播放中的进度回写不得再次驱动 video.currentTime。
  const previousTime = preservePosition ? element.currentTime : Math.max(0, props.initialPositionSeconds ?? 0);
  const wasPaused = element.paused;
  const requiredHeaders = grant.headers ?? {};
  activeHeaders = requiredHeaders;
  const restore = () => {
    if (previousTime > 0 && Number.isFinite(previousTime)) element.currentTime = previousTime;
    reportDuration();
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
  if (!hls) {
    hls = new HlsClass({
      xhrSetup(xhr) {
        Object.entries(activeHeaders).forEach(([name, value]) => xhr.setRequestHeader(name, value));
      }
    });
    hls.on(HlsClass.Events.ERROR, (_event, data) => {
      if (!data.fatal) return;
      if (data.type === HlsModule.ErrorTypes.NETWORK_ERROR) errorMessage.value = "视频流暂时无法读取，请重新获取播放授权。";
      else errorMessage.value = "浏览器无法继续解码该视频，请重试或更换浏览器。";
      clearRuntime(false);
    });
    hls.attachMedia(element);
  }
  hls.once(HlsClass.Events.MANIFEST_PARSED, restore);
  // 后台授权刷新复用现有 MediaSource，只替换短期清单，避免提前销毁播放器造成黑帧。
  hls.loadSource(grant.url);
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
  progressTracker.flush();
  generation += 1;
  clearRuntime(true);
  progressTracker.reset();
}

function handlePlay() {
  if (video.value) progressTracker.play(video.value.currentTime);
}

function handleTimeUpdate() {
  if (!video.value) return;
  const position = video.value.currentTime;
  progressTracker.observe(position);
  const positionSecond = Math.floor(position);
  // 进度条与笔记时间只需秒级精度，避免 timeupdate 高频触发整页响应式重绘并干扰视频合成。
  if (positionSecond !== lastPositionSecond) {
    lastPositionSecond = positionSecond;
    emit("positionChange", position);
  }
}

function reportDuration() {
  const duration = video.value?.duration;
  if (duration && Number.isFinite(duration)) emit("durationChange", duration);
}

function handlePause() {
  if (video.value && !video.value.ended) progressTracker.pause(video.value.currentTime);
}

function handleEnded() {
  if (video.value) progressTracker.complete(video.value.currentTime);
}

/** 允许业务页面从笔记时间点定位播放器；目标仍由原生 video 按媒体时长约束。 */
function seekTo(positionSeconds: number) {
  if (!video.value || !Number.isFinite(positionSeconds)) return;
  video.value.currentTime = Math.max(0, positionSeconds);
  lastPositionSecond = Math.floor(video.value.currentTime);
  emit("positionChange", video.value.currentTime);
}

defineExpose({ seekTo });

function clearRuntime(removeSource: boolean) {
  clearGrantRequest();
  hls?.destroy();
  hls = null;
  activeHeaders = {};
  lastPositionSecond = -1;
  if (removeSource && video.value) {
    video.value.pause();
    video.value.removeAttribute("src");
    video.value.load();
  }
}

function clearGrantRequest() {
  grantAbort?.abort();
  grantAbort = null;
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = null;
}

</script>

<template>
  <section class="hls-player" :aria-busy="loading">
    <video ref="video" controls playsinline preload="auto" :aria-label="title"
      @loadedmetadata="reportDuration" @durationchange="reportDuration"
      @play="handlePlay" @timeupdate="handleTimeUpdate" @pause="handlePause" @ended="handleEnded" />
    <div v-if="loading" class="hls-player__status" role="status">正在获取短期播放授权…</div>
    <div v-else-if="errorMessage" class="hls-player__error" role="alert">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="retry">重新尝试</button>
    </div>
  </section>
</template>

<style scoped>
.hls-player { display: grid; gap: 10px; width: 100%; }
.hls-player video { display: block; width: 100%; aspect-ratio: 16 / 9; background: #101522; border-radius: 14px; }
.hls-player__status,.hls-player__error { padding: 11px 13px; border-radius: 9px; font-size: .8rem; }
.hls-player__status { color: #365b91; background: #edf4ff; }
.hls-player__error { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #922b3a; background: #fff2f4; }
.hls-player__error button { min-height: 36px; padding: 0 12px; color: inherit; background: #fff; border: 1px solid #efbdc5; border-radius: 8px; cursor: pointer; }
</style>
