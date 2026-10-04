<script setup lang="ts">
/**
 * 教师课程工作台列表；数据来自受保护的 /api/v1/teacher/courses，只展示当前教师拥有的课程。
 * 支持分页和发布/下架，revision 冲突或权限/网络失败时显示错误且不在本地伪造成功状态。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseAccessType, CourseLevel, CoursePublicationStatus, TeacherCoursePage } from "@edu/api-client";
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { teacherCourseApi } from "../course-api";
import { canPublishCourse, publicationControls } from "../publication-controls";

const route = useRoute();
const router = useRouter();
const pageSize = 10;
const page = ref<TeacherCoursePage | null>(null);
const loading = ref(false);
const actionId = ref("");
const errorMessage = ref("");
const successMessage = ref("");
const failedCoverIds = ref<Set<string>>(new Set());
const pageCount = computed(() => Math.max(1, Math.ceil((page.value?.total ?? 0) / pageSize)));

watch(() => route.fullPath, load, { immediate: true });

async function load() {
  loading.value = true;
  errorMessage.value = "";
  failedCoverIds.value = new Set();
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
  if (!canPublishCourse(course)) return;
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

/** 图片授权失效或 OSS 加载失败时只降级当前卡片封面，不破坏课程信息与操作布局。 */
function markCoverFailed(courseId: string) {
  failedCoverIds.value = new Set(failedCoverIds.value).add(courseId);
}

function showCover(course: TeacherCoursePage["items"][number]): boolean {
  return Boolean(course.coverUrl) && !failedCoverIds.value.has(course.id);
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
      <div class="teacher-course-card__cover">
        <img v-if="showCover(course)" :src="course.coverUrl!" :alt="`${course.title}封面`"
          @error="markCoverFailed(course.id)">
        <div v-else class="teacher-course-card__cover-placeholder" role="img" :aria-label="`${course.title}暂无可用封面`">
          <span aria-hidden="true">▧</span>
          <strong>课程封面</strong>
          <small>{{ course.category.name }}</small>
        </div>
      </div>
      <div class="teacher-course-card__content">
        <div class="teacher-course-card__heading">
          <div>
            <div class="teacher-course-card__status">
              <span :class="`status-pill status-pill--${course.publicationStatus.toLowerCase()}`">
                {{ statusLabel(course.publicationStatus) }}
              </span>
              <span v-if="course.hasUnpublishedChanges" class="status-pill status-pill--changes">有未发布修改</span>
            </div>
            <h3>{{ course.title }}</h3>
          </div>
          <span class="teacher-course-card__version">{{ course.version }} · r{{ course.revision }}</span>
        </div>
        <p>{{ course.summary || "尚未填写课程摘要。" }}</p>
        <dl class="teacher-course-card__meta">
          <div><dt>分类</dt><dd>{{ course.category.name }}</dd></div>
          <div><dt>难度</dt><dd>{{ levelLabel(course.level) }}</dd></div>
          <div><dt>访问</dt><dd>{{ accessLabel(course.accessType) }}</dd></div>
        </dl>
        <div class="teacher-course-card__actions">
          <RouterLink class="button button--quiet" :to="{ name: 'teacher-course-edit', params: { courseId: course.id } }">编辑信息</RouterLink>
          <RouterLink class="button button--quiet" :to="{ name: 'teacher-course-curriculum', params: { courseId: course.id } }">编排目录</RouterLink>
          <RouterLink class="button button--quiet" :to="{ name: 'teacher-course-announcements', params: { courseId: course.id }, query: { coursePage: String(currentPage()) } }">课程公告</RouterLink>
          <button v-if="publicationControls(course).showPublish" class="button button--primary" type="button"
            :disabled="actionId === course.id || !canPublishCourse(course)" @click="publish(course)">
            {{ publicationControls(course).publishLabel }}
          </button>
          <button v-if="publicationControls(course).showUnpublish" class="button button--danger" type="button"
            :disabled="actionId === course.id" @click="unpublish(course)">下架课程</button>
          <small v-if="course.hasUnpublishedChanges && !canPublishCourse(course)">发布前需确保每章至少有一个课时。</small>
        </div>
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
