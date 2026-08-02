<script setup lang="ts">
/**
 * 学生端公开课程详情页；按路由 courseId 调用 GET /api/v1/courses/{courseId}。
 * 成功展示当前发布版本和目录，资源不存在/下架或网络失败时展示错误，不读取教师草稿。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseAccessType, CourseDetail, CourseLevel } from "@edu/api-client";
import { ref, watch } from "vue";
import { useRoute } from "vue-router";

import { studentCourseApi } from "../course-api";

const route = useRoute();
const course = ref<CourseDetail | null>(null);
const loading = ref(false);
const errorMessage = ref("");

watch(() => route.params.courseId, loadCourse, { immediate: true });

async function loadCourse(courseId: unknown) {
  if (typeof courseId !== "string") return;
  loading.value = true;
  course.value = null;
  errorMessage.value = "";
  try {
    course.value = await studentCourseApi.detail(courseId);
  } catch (error) {
    errorMessage.value = error instanceof GatewayRequestError && error.status === 404
      ? "课程不存在、尚未发布或已经下架。"
      : error instanceof GatewayRequestError ? error.message : "课程详情加载失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
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
  <RouterLink class="back-link" to="/courses">← 返回课程列表</RouterLink>
  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <div v-if="loading" class="detail-loading">正在读取课程线上版本…</div>
  <template v-else-if="course">
    <section class="detail-hero">
      <img :src="course.coverUrl" :alt="`${course.title}封面`">
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
              </li>
            </ol>
          </li>
        </ol>
        <div v-else class="empty-inline">该课程暂未提供目录。</div>
        <p class="scope-note">本页仅展示课程营销信息与目录，不包含视频播放、报名、进度、题目或智能答疑。</p>
      </section>
    </div>
  </template>
</template>
