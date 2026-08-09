<script setup lang="ts">
/**
 * 公共课程详情页；按路由 courseId 调用 GET /api/v1/courses/{courseId}。
 * 成功展示当前发布版本和目录预览，资源不存在/下架或网络失败时展示错误，不读取教师草稿。
 * 正式播放只通过 `/learn/:courseId/:lessonId` 打开；本页不申请授权、不持有 HLS 或短期 URL。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseAccessType, CourseDetail, CourseLevel } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { studentCourseApi } from "../course-api";
import { courseLearningEntries, firstAccessibleLearningEntry } from "../course-learning";

const route = useRoute();
const course = ref<CourseDetail | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const coverFailed = ref(false);
let detailAbort: AbortController | null = null;
let loadGeneration = 0;
const learningEntries = computed(() => course.value ? courseLearningEntries(course.value) : []);
const firstLearningEntry = computed(() => course.value ? firstAccessibleLearningEntry(course.value) : null);

watch(() => route.params.courseId, loadCourse, { immediate: true });
onBeforeUnmount(cancelDetailRequest);

async function loadCourse(courseId: unknown) {
  cancelDetailRequest();
  const generation = ++loadGeneration;
  if (typeof courseId !== "string") return;
  loading.value = true;
  course.value = null;
  coverFailed.value = false;
  errorMessage.value = "";
  const controller = new AbortController();
  detailAbort = controller;
  try {
    const detail = await studentCourseApi.detail(courseId, controller.signal);
    if (!controller.signal.aborted && generation === loadGeneration) course.value = detail;
  } catch (error) {
    if (!controller.signal.aborted && generation === loadGeneration) {
      errorMessage.value = error instanceof GatewayRequestError && error.status === 404
        ? "课程不存在、尚未发布或已经下架。"
        : error instanceof TypeError ? "网络连接不可用，请检查网络后重试。"
          : error instanceof GatewayRequestError ? error.message : "课程详情加载失败，请稍后重试。";
    }
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
}

function learningEntryFor(lessonId: string) {
  return learningEntries.value.find(item => item.lesson.id === lessonId) ?? null;
}

function cancelDetailRequest() {
  detailAbort?.abort();
  detailAbort = null;
}

function levelLabel(level: CourseLevel): string {
  return { BEGINNER: "入门", INTERMEDIATE: "进阶", ADVANCED: "高级" }[level];
}

function accessLabel(accessType: CourseAccessType): string {
  return accessType === "PUBLIC" ? "公开浏览" : "需报名后访问学习内容";
}

function lessonTypeLabel(type: CourseDetail["chapters"][number]["lessons"][number]["type"]): string {
  return { VIDEO: "视频", ARTICLE: "图文", ASSESSMENT: "测评" }[type];
}
</script>

<template>
  <RouterLink class="back-link" to="/courses">← 返回课程中心</RouterLink>
  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <div v-if="loading" class="detail-loading">正在读取课程线上版本…</div>
  <template v-else-if="course">
    <section class="detail-hero">
      <div class="detail-hero__cover">
        <img v-if="course.coverUrl && !coverFailed" :src="course.coverUrl" :alt="`${course.title}封面`"
          @error="coverFailed = true">
        <div v-else class="detail-hero__cover-placeholder" role="img" :aria-label="`${course.title}暂无封面`">
          <span aria-hidden="true">▧</span><strong>课程封面</strong>
        </div>
      </div>
      <div class="detail-hero__content">
        <div class="meta-row">
          <span>{{ course.category.name }}</span>
          <span>{{ levelLabel(course.level) }}</span>
          <span>{{ accessLabel(course.accessType) }}</span>
        </div>
        <h2>{{ course.title }}</h2>
        <p>{{ course.summary }}</p>
        <div class="detail-hero__teacher">
          <span>授课教师</span>
          <strong>{{ course.teacher.displayName }}</strong>
          <small>线上版本 {{ course.publishedVersion }}</small>
        </div>
        <RouterLink v-if="firstLearningEntry" class="detail-learning-action"
          :to="`/learn/${course.id}/${firstLearningEntry.lesson.id}`">
          {{ course.accessType === "PUBLIC" ? "开始学习" : "试看课程" }}
          <span aria-hidden="true">→</span>
        </RouterLink>
        <p v-else-if="course.accessType === 'ENROLLMENT_REQUIRED'" class="detail-access-note">
          本课程需要报名，当前没有可试看视频；报名能力将在 T11 接通。
        </p>
        <p v-else class="detail-access-note">当前发布版本暂时没有可播放的视频课时。</p>
      </div>
    </section>

    <div class="detail-layout">
      <article class="detail-copy">
        <span class="section-kicker">ABOUT THIS COURSE</span>
        <h3>课程介绍</h3>
        <p class="markdown-copy">{{ course.description || "课程介绍正在完善中。" }}</p>
      </article>

      <section class="curriculum-panel">
        <header>
          <div>
            <span class="section-kicker">CURRICULUM</span>
            <h3>课程目录</h3>
          </div>
          <span>{{ course.chapters.length }} 章 · {{ course.chapters.reduce((sum, item) => sum + item.lessons.length, 0) }} 课时</span>
        </header>
        <ol v-if="course.chapters.length" class="chapter-list">
          <li v-for="chapter in course.chapters" :key="chapter.id" class="chapter-item">
            <div class="chapter-item__heading">
              <span>{{ String(chapter.sequence).padStart(2, "0") }}</span>
              <h4>{{ chapter.title }}</h4>
              <small>{{ chapter.lessons.length }} 课时</small>
            </div>
            <ol class="lesson-list">
              <li v-for="lesson in chapter.lessons" :key="lesson.id">
                <span>{{ lesson.sequence }}</span>
                <strong>{{ lesson.title }}</strong>
                <small>{{ lessonTypeLabel(lesson.type) }}</small>
                <em v-if="lesson.previewable">可试看</em>
                <RouterLink v-if="learningEntryFor(lesson.id)?.accessible" class="lesson-play"
                  :to="`/learn/${course.id}/${lesson.id}`">
                  {{ lesson.previewable && course.accessType === "ENROLLMENT_REQUIRED" ? "试看" : "进入学习" }}
                </RouterLink>
                <span v-else-if="learningEntryFor(lesson.id)" class="lesson-locked">需报名</span>
              </li>
            </ol>
          </li>
        </ol>
        <div v-else class="empty-inline">该课程暂未提供目录。</div>
        <p class="scope-note">目录仅预览当前发布版本；视频会在独立学习页打开。本阶段不记录进度，也不实现报名、题目或智能答疑。</p>
      </section>
    </div>
  </template>
</template>
