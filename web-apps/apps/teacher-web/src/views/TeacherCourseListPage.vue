<script setup lang="ts">
import { GatewayRequestError } from "@edu/api-client";
import type { CourseAccessType, CourseLevel, CoursePublicationStatus, TeacherCoursePage } from "@edu/api-client";
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { teacherCourseApi } from "../course-api";

const route = useRoute();
const router = useRouter();
const pageSize = 10;
const page = ref<TeacherCoursePage | null>(null);
const loading = ref(false);
const actionId = ref("");
const errorMessage = ref("");
const successMessage = ref("");
const pageCount = computed(() => Math.max(1, Math.ceil((page.value?.total ?? 0) / pageSize)));

watch(() => route.fullPath, load, { immediate: true });

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    page.value = await teacherCourseApi.list({ page: currentPage(), size: pageSize });
  } catch (error) {
    errorMessage.value = messageFor(error, "教师课程列表加载失败");
  } finally {
    loading.value = false;
  }
}

async function changePage(target: number) {
  if (target < 1 || target > pageCount.value || target === currentPage()) return;
  await router.push({ name: "teacher-courses", query: target > 1 ? { page: String(target) } : {} });
}

async function publish(course: TeacherCoursePage["items"][number]) {
  if (!canPublish(course)) return;
  if (!window.confirm(`确认发布“${course.title}”的 ${course.version} 版本？`)) return;
  await updatePublication(course, "PUBLISHED");
}

async function unpublish(course: TeacherCoursePage["items"][number]) {
  if (!window.confirm(`确认下架“${course.title}”？学生端将立即不可见。`)) return;
  await updatePublication(course, "UNPUBLISHED");
}

async function updatePublication(course: TeacherCoursePage["items"][number], status: "PUBLISHED" | "UNPUBLISHED") {
  actionId.value = course.id;
  errorMessage.value = "";
  successMessage.value = "";
  try {
    await teacherCourseApi.updatePublication(course.id, { status, expectedRevision: course.revision });
    successMessage.value = status === "PUBLISHED" ? "课程已发布。" : "课程已下架。";
    await load();
  } catch (error) {
    errorMessage.value = messageFor(error, status === "PUBLISHED" ? "课程发布失败" : "课程下架失败");
  } finally {
    actionId.value = "";
  }
}

function canPublish(course: TeacherCoursePage["items"][number]): boolean {
  return course.hasUnpublishedChanges && course.chapters.length > 0
    && course.chapters.every(chapter => chapter.lessons.length > 0);
}

function currentPage(): number {
  const value = typeof route.query.page === "string" ? Number(route.query.page) : 1;
  return Number.isInteger(value) && value > 0 ? value : 1;
}

function statusLabel(status: CoursePublicationStatus): string {
  return { DRAFT: "草稿", PUBLISHED: "已发布", UNPUBLISHED: "已下架" }[status];
}

function levelLabel(level: CourseLevel): string {
  return { BEGINNER: "入门", INTERMEDIATE: "进阶", ADVANCED: "高级" }[level];
}

function accessLabel(accessType: CourseAccessType): string {
  return accessType === "PUBLIC" ? "公开" : "需报名";
}

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof GatewayRequestError && error.status === 409) return "课程已被其他操作更新，请刷新后重试。";
  return error instanceof GatewayRequestError ? error.message : fallback;
}
</script>

<template>
  <section class="teacher-page-heading">
    <div>
      <span class="section-kicker">COURSE MANAGEMENT</span>
      <h2>课程管理</h2>
      <p>管理你拥有的课程草稿、线上版本和下架状态。</p>
    </div>
    <RouterLink class="button button--primary" :to="{ name: 'teacher-course-new' }">＋ 创建课程</RouterLink>
  </section>

  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>

  <div v-if="loading" class="teacher-loading">正在加载你的课程…</div>
  <div v-else-if="page?.items.length" class="teacher-course-list">
    <article v-for="course in page.items" :key="course.id" class="teacher-course-card">
      <img :src="course.coverUrl" :alt="`${course.title}封面`">
      <div class="teacher-course-card__content">
        <div class="teacher-course-card__status">
          <span :class="`status-pill status-pill--${course.publicationStatus.toLowerCase()}`">
            {{ statusLabel(course.publicationStatus) }}
          </span>
          <span v-if="course.hasUnpublishedChanges" class="status-pill status-pill--changes">有未发布修改</span>
        </div>
        <h3>{{ course.title }}</h3>
        <p>{{ course.summary || "尚未填写课程摘要。" }}</p>
        <div class="teacher-course-card__meta">
          <span>{{ course.category.name }}</span><span>{{ levelLabel(course.level) }}</span>
          <span>{{ accessLabel(course.accessType) }}</span><span>{{ course.version }} · revision {{ course.revision }}</span>
        </div>
      </div>
      <div class="teacher-course-card__actions">
        <RouterLink class="button button--quiet" :to="{ name: 'teacher-course-edit', params: { courseId: course.id } }">编辑元数据</RouterLink>
        <RouterLink class="button button--quiet" :to="{ name: 'teacher-course-curriculum', params: { courseId: course.id } }">编排目录</RouterLink>
        <button v-if="course.publicationStatus === 'PUBLISHED'" class="button button--danger" type="button"
          :disabled="actionId === course.id" @click="unpublish(course)">下架</button>
        <button v-else-if="course.publicationStatus === 'UNPUBLISHED' && !course.hasUnpublishedChanges"
          class="button button--primary" type="button" :disabled="actionId === course.id" @click="publish(course)">重新发布</button>
        <button v-else class="button button--primary" type="button"
          :disabled="actionId === course.id || !canPublish(course)" @click="publish(course)">发布草稿</button>
        <small v-if="course.hasUnpublishedChanges && !canPublish(course)">发布前需确保每章至少有一个课时。</small>
      </div>
    </article>
  </div>
  <div v-else-if="page" class="teacher-empty">
    <span>还没有课程</span>
    <h3>创建第一门课程草稿</h3>
    <p>先填写元数据，再编排章节课时，最后发布到学生端。</p>
    <RouterLink class="button button--primary" :to="{ name: 'teacher-course-new' }">创建课程</RouterLink>
  </div>

  <nav v-if="page && page.total > pageSize" class="pagination" aria-label="教师课程分页">
    <button type="button" :disabled="currentPage() <= 1" @click="changePage(currentPage() - 1)">上一页</button>
    <span>第 {{ currentPage() }} / {{ pageCount }} 页</span>
    <button type="button" :disabled="currentPage() >= pageCount" @click="changePage(currentPage() + 1)">下一页</button>
  </nav>
</template>
