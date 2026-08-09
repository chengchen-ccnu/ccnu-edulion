<script setup lang="ts">
/**
 * 课程学习媒体壳；输入为受保护路由中的 courseId/lessonId，读取当前发布目录并通过课程域申请播放授权。
 * 页面输出播放器、当前课时、目录和前后课导航；不推断报名，不保存进度、短期 URL 或 mediaId。
 * 切换深链接或卸载时会中止目录请求，播放器组件同步销毁 HLS、定时器、授权请求和 video source。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseDetail } from "@edu/api-client";
import { HlsVideoPlayer, playbackErrorMessage } from "@edu/ui";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { studentCourseApi } from "../course-api";
import { courseLearningEntries, findLearningEntry } from "../course-learning";

const route = useRoute();
const course = ref<CourseDetail | null>(null);
const loading = ref(false);
const errorMessage = ref("");
let detailAbort: AbortController | null = null;
let loadGeneration = 0;

const entries = computed(() => course.value ? courseLearningEntries(course.value) : []);
const current = computed(() => course.value && typeof route.params.lessonId === "string"
  ? findLearningEntry(course.value, route.params.lessonId) : null);
const currentIndex = computed(() => current.value
  ? entries.value.findIndex(item => item.lesson.id === current.value?.lesson.id) : -1);
const previous = computed(() => currentIndex.value > 0 ? entries.value[currentIndex.value - 1] : null);
const next = computed(() => currentIndex.value >= 0 && currentIndex.value < entries.value.length - 1
  ? entries.value[currentIndex.value + 1] : null);

watch(() => [route.params.courseId, route.params.lessonId], loadCourse, { immediate: true });
onBeforeUnmount(cancelDetailRequest);

/** 深链接和刷新都重新读取发布课程；后返回的旧请求不会覆盖新路由。 */
async function loadCourse([courseId, lessonId]: unknown[]) {
  cancelDetailRequest();
  const generation = ++loadGeneration;
  course.value = null;
  errorMessage.value = "";
  if (typeof courseId !== "string" || typeof lessonId !== "string") {
    errorMessage.value = "课程学习地址无效。";
    return;
  }
  loading.value = true;
  const controller = new AbortController();
  detailAbort = controller;
  try {
    const detail = await studentCourseApi.detail(courseId, controller.signal);
    if (controller.signal.aborted || generation !== loadGeneration) return;
    course.value = detail;
    if (!findLearningEntry(detail, lessonId)) errorMessage.value = "课时或视频资源不存在，可能已更新或下架。";
  } catch (error) {
    if (!controller.signal.aborted && generation === loadGeneration) errorMessage.value = detailErrorMessage(error);
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
}

/** 播放授权始终使用路由定位到的课程资源行，学生端无 owner 预览分支。 */
function getStudentGrant(signal: AbortSignal) {
  if (!course.value || !current.value) throw new Error("VIDEO_NOT_SELECTED");
  return studentCourseApi.playbackGrant(course.value.id, current.value.lesson.id, current.value.resourceId, signal);
}

/** T11 前将报名制非试看 403 解释为明确阶段边界，其余状态沿用共享播放器提示。 */
function studentPlaybackErrorMessage(error: unknown): string {
  return error instanceof GatewayRequestError && error.status === 403
    ? "当前课时需要报名；报名能力将在 T11 接通，现阶段只能播放试看内容。"
    : playbackErrorMessage(error);
}

function detailErrorMessage(error: unknown): string {
  if (error instanceof GatewayRequestError && error.status === 404) return "课程不存在、尚未发布或已经下架。";
  if (error instanceof TypeError) return "网络连接不可用，请检查网络后重试。";
  return error instanceof GatewayRequestError ? error.message : "学习页加载失败，请稍后重试。";
}

function cancelDetailRequest() {
  detailAbort?.abort();
  detailAbort = null;
}
</script>

<template>
  <div class="learning-topbar">
    <RouterLink v-if="course" :to="`/courses/${course.id}`">← 退出学习</RouterLink>
    <RouterLink v-else to="/courses">← 返回课程中心</RouterLink>
    <span v-if="course">{{ course.title }} · 线上版本 {{ course.publishedVersion }}</span>
  </div>

  <div v-if="loading" class="learning-loading" role="status">正在恢复课程与课时…</div>
  <section v-else-if="errorMessage && !current" class="learning-error" role="alert">
    <span class="section-kicker">LEARNING SPACE</span>
    <h2>暂时无法打开这一课</h2>
    <p>{{ errorMessage }}</p>
    <RouterLink v-if="course" class="button button--quiet" :to="`/courses/${course.id}`">返回课程详情</RouterLink>
    <button v-else class="button button--quiet" type="button"
      @click="loadCourse([route.params.courseId, route.params.lessonId])">重新加载</button>
  </section>

  <div v-else-if="course && current" class="learning-layout">
    <main class="learning-main">
      <header class="learning-heading">
        <div>
          <span class="section-kicker">CURRENT LESSON</span>
          <h2>{{ current.lesson.title }}</h2>
          <p>{{ current.chapterTitle }} · 第 {{ current.lesson.sequence }} 课</p>
        </div>
        <span v-if="current.lesson.previewable" class="learning-preview-badge">试看</span>
      </header>

      <HlsVideoPlayer :grant-key="`${course.id}:${current.lesson.id}:${current.resourceId}`"
        :title="`${current.lesson.title}视频`" :grant-provider="getStudentGrant"
        :error-message-provider="studentPlaybackErrorMessage" />

      <nav class="lesson-navigation" aria-label="课时导航">
        <RouterLink v-if="previous?.accessible" :to="`/learn/${course.id}/${previous.lesson.id}`">
          <small>上一课</small><strong>{{ previous.lesson.title }}</strong>
        </RouterLink>
        <span v-else class="lesson-navigation__disabled">
          <small>上一课</small><strong>{{ previous ? "需要报名" : "已经是第一课" }}</strong>
        </span>
        <RouterLink v-if="next?.accessible" :to="`/learn/${course.id}/${next.lesson.id}`">
          <small>下一课</small><strong>{{ next.lesson.title }}</strong>
        </RouterLink>
        <span v-else class="lesson-navigation__disabled">
          <small>下一课</small><strong>{{ next ? "需要报名" : "已经是最后一课" }}</strong>
        </span>
      </nav>
    </main>

    <aside class="learning-sidebar" aria-label="课程目录">
      <details open>
        <summary><span>课程目录</span><small>{{ entries.length }} 个视频课时</small></summary>
        <ol>
          <li v-for="entry in entries" :key="entry.lesson.id"
            :class="{ 'is-current': entry.lesson.id === current.lesson.id }">
            <RouterLink v-if="entry.accessible" :to="`/learn/${course.id}/${entry.lesson.id}`"
              :aria-current="entry.lesson.id === current.lesson.id ? 'page' : undefined">
              <span>{{ entry.chapterSequence }}.{{ entry.lesson.sequence }}</span>
              <strong>{{ entry.lesson.title }}</strong>
              <small>{{ entry.lesson.previewable ? "试看" : "视频" }}</small>
            </RouterLink>
            <div v-else aria-label="该课时需要报名">
              <span>{{ entry.chapterSequence }}.{{ entry.lesson.sequence }}</span>
              <strong>{{ entry.lesson.title }}</strong>
              <small>需报名</small>
            </div>
          </li>
        </ol>
      </details>
      <p>本阶段只提供视频学习与短期授权刷新，不记录进度，也不展示笔记、练习或 AI 功能。</p>
    </aside>
  </div>
</template>
