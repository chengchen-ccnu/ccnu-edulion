<script setup lang="ts">
/**
 * 公共课程详情页；按路由 courseId 调用 GET /api/v1/courses/{courseId}。
 * 成功展示当前发布版本和目录预览，并在已恢复 learning:access 会话后组合真实报名与进度。
 * 报名写入 Learning 后才进入既有 `/learn/...`；本页不申请播放授权、不持有 HLS 或短期 URL。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CourseAccessType, CourseDetail, CourseLevel, CourseProgress, Enrollment } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { authStore } from "../auth";
import { studentCourseApi } from "../course-api";
import { courseLearningEntries, firstAccessibleLearningEntry } from "../course-learning";
import { resumeLessonId } from "../learning-model";
import { isAuthenticationRequired, studentLearningApi } from "../learning-api";
import CourseAnnouncements from "../components/CourseAnnouncements.vue";
import CourseStatusNotice from "../components/CourseStatusNotice.vue";

const route = useRoute();
const router = useRouter();
const course = ref<CourseDetail | null>(null);
const enrollment = ref<Enrollment | null>(null);
const progress = ref<CourseProgress | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const personalizationLoading = ref(false);
const personalizationError = ref("");
const actionMessage = ref("");
const enrolling = ref(false);
const coverFailed = ref(false);
const supportEmail = ref<string | undefined>();
let detailAbort: AbortController | null = null;
let personalizationAbort: AbortController | null = null;
let enrollAbort: AbortController | null = null;
let loadGeneration = 0;
const hasLearningAccess = computed(() => authStore.state.currentUser?.permissions.includes("learning:access") ?? false);
const canUseLearningLinks = computed(() => authStore.state.status !== "authenticated" || hasLearningAccess.value);
const learningEntries = computed(() => course.value ? courseLearningEntries(course.value, Boolean(enrollment.value)) : []);
const firstLearningEntry = computed(() => course.value
  ? firstAccessibleLearningEntry(course.value, Boolean(enrollment.value)) : null);
const resumeId = computed(() => course.value
  ? resumeLessonId(course.value, progress.value, Boolean(enrollment.value)) : null);

watch(() => route.params.courseId, loadCourse, { immediate: true });
watch([() => course.value?.id, () => authStore.state.currentUser?.id, () => authStore.state.status, hasLearningAccess], loadPersonalization);
onBeforeUnmount(cancelRequests);

/** 按路由读取公开课程；暂停只显示通用状态/支持邮箱，切课和离页中止旧结果。 */
async function loadCourse(courseId: unknown) {
  cancelRequests();
  personalizationLoading.value = false;
  enrolling.value = false;
  const generation = ++loadGeneration;
  if (typeof courseId !== "string") return;
  loading.value = true;
  course.value = null;
  enrollment.value = null;
  progress.value = null;
  coverFailed.value = false;
  errorMessage.value = "";
  supportEmail.value = undefined;
  const controller = new AbortController();
  detailAbort = controller;
  try {
    const detail = await studentCourseApi.detail(courseId, controller.signal);
    if (!controller.signal.aborted && generation === loadGeneration) course.value = detail;
  } catch (error) {
    if (!controller.signal.aborted && generation === loadGeneration) {
      errorMessage.value = error instanceof GatewayRequestError && error.status === 404
        ? "课程不存在、尚未发布或已经下架。"
        : error instanceof GatewayRequestError && error.status === 429
          ? "课程详情请求过于频繁，请稍后手动重试。"
          : error instanceof TypeError ? "网络连接不可用，请检查网络后重试。"
            : error instanceof GatewayRequestError && error.code === "COURSE.SUSPENDED" ? "课程暂不可用"
              : error instanceof GatewayRequestError ? error.message : "课程详情加载失败，请稍后重试。";
      if (error instanceof GatewayRequestError && error.code === "COURSE.SUSPENDED") supportEmail.value = error.supportEmail;
    }
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
}

/** 公共详情只在会话已恢复且具备学习能力时读取本人报名/进度；匿名浏览不触发受保护请求。 */
async function loadPersonalization() {
  personalizationAbort?.abort();
  personalizationAbort = null;
  personalizationLoading.value = false;
  enrollment.value = null;
  progress.value = null;
  personalizationError.value = "";
  actionMessage.value = "";
  if (!course.value || authStore.state.status !== "authenticated" || !hasLearningAccess.value) return;
  const currentCourse = course.value;
  const controller = new AbortController();
  personalizationAbort = controller;
  personalizationLoading.value = true;
  try {
    const activeEnrollment = await studentLearningApi.findActiveEnrollment(currentCourse.id, controller.signal);
    if (controller.signal.aborted) return;
    enrollment.value = activeEnrollment;
    if (currentCourse.accessType === "PUBLIC" || enrollment.value) {
      const value = await studentLearningApi.progress(currentCourse.id, controller.signal);
      if (!controller.signal.aborted) progress.value = value;
    }
  } catch (error) {
    if (!controller.signal.aborted) personalizationError.value = personalizedErrorMessage(error);
  } finally {
    if (!controller.signal.aborted) personalizationLoading.value = false;
  }
}

/** 报名成功后使用服务端事实更新页面，再进入最近课时；Course 播放投影可能短暂最终一致。 */
async function enrollAndStart() {
  if (!course.value || !hasLearningAccess.value || enrolling.value) return;
  enrollAbort?.abort();
  const controller = new AbortController();
  enrollAbort = controller;
  enrolling.value = true;
  personalizationError.value = "";
  actionMessage.value = "";
  try {
    enrollment.value = await studentLearningApi.enroll(course.value.id, controller.signal);
    actionMessage.value = "报名成功，播放访问权限正在同步；若暂时收到 403，请稍候手动重试。";
    try {
      progress.value = await studentLearningApi.progress(course.value.id, controller.signal);
    } catch (error) {
      if (!(error instanceof GatewayRequestError && error.status === 403)) throw error;
    }
    const lessonId = resumeId.value ?? firstLearningEntry.value?.lesson.id;
    if (lessonId) await router.push(`/learn/${course.value.id}/${lessonId}`);
  } catch (error) {
    if (!controller.signal.aborted) personalizationError.value = personalizedErrorMessage(error);
  } finally {
    if (!controller.signal.aborted) enrolling.value = false;
  }
}

function learningEntryFor(lessonId: string) {
  return learningEntries.value.find(item => item.lesson.id === lessonId) ?? null;
}

function cancelDetailRequest() {
  detailAbort?.abort();
  detailAbort = null;
}

function cancelRequests() {
  cancelDetailRequest();
  personalizationAbort?.abort();
  enrollAbort?.abort();
}

function personalizedErrorMessage(error: unknown): string {
  if (isAuthenticationRequired(error)) return "登录状态已失效，请重新登录后继续。";
  if (error instanceof TypeError) return "当前离线，课程公开信息仍可浏览；报名与进度恢复后可重试。";
  if (!(error instanceof GatewayRequestError)) return "报名或进度暂时无法读取，请稍后重试。";
  if (error.status === 401) return "登录状态已失效，请重新登录后继续。";
  if (error.status === 403) return "当前账号没有学习权限，或该报名尚未生效。";
  if (error.status === 404) return "报名或课程学习事实不存在、已下架或不可见。";
  if (error.status === 409) return "报名状态已变化，请刷新后重试。";
  if (error.status === 422) return "报名请求不符合当前课程规则，请检查后重试。";
  if (error.status === 429) return "报名请求过于频繁，有限重试仍未成功，请稍后手动重试。";
  if (error.status === 503) return "课程或学习服务暂时不可用，请稍后重试。";
  return error.message;
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
  <CourseStatusNotice :course-id="String(route.params.courseId)" :notification-id="typeof route.query.notificationId === 'string' ? route.query.notificationId : undefined" />
  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <p v-if="supportEmail">如需帮助，请联系 {{ supportEmail }}</p>
  <button v-if="errorMessage" class="button button--quiet" @click="loadCourse(route.params.courseId)">重新加载课程</button>
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
        <section v-if="enrollment && progress" class="detail-course-progress" aria-label="课程完成度">
          <div>
            <span>课程完成度</span>
            <strong>{{ progress.completionPercent }}%</strong>
          </div>
          <div class="learning-progress-bar" :aria-label="`课程完成度 ${progress.completionPercent}%`">
            <span :style="{ width: `${progress.completionPercent}%` }" />
          </div>
          <small>已完成 {{ progress.completedLessonCount }} / {{ progress.lessonCount }} 个课时</small>
        </section>
        <RouterLink v-if="enrollment && resumeId" class="detail-learning-action"
          :to="`/learn/${course.id}/${resumeId}`">
          {{ progress?.lessons.length ? "继续学习" : "开始学习" }}
          <span aria-hidden="true">→</span>
        </RouterLink>
        <button v-else-if="authStore.state.status === 'authenticated' && hasLearningAccess && learningEntries.length"
          class="detail-learning-action" type="button" :disabled="enrolling || personalizationLoading"
          @click="enrollAndStart">
          {{ enrolling ? "正在报名…" : course.accessType === "PUBLIC" ? "开始学习" : "报名并开始学习" }}
          <span aria-hidden="true">→</span>
        </button>
        <RouterLink v-else-if="authStore.state.status !== 'authenticated' && firstLearningEntry"
          class="detail-learning-action" :to="`/learn/${course.id}/${firstLearningEntry.lesson.id}`">
          {{ course.accessType === "PUBLIC" ? "开始学习" : "试看课程" }}<span aria-hidden="true">→</span>
        </RouterLink>
        <RouterLink v-else-if="authStore.state.status !== 'authenticated' && learningEntries.length"
          class="detail-learning-action" :to="{ name: 'login', query: { redirect: `/courses/${course.id}` } }">
          登录后报名<span aria-hidden="true">→</span>
        </RouterLink>
        <p v-else-if="authStore.state.status === 'authenticated' && !hasLearningAccess" class="detail-access-note">
          当前账号没有 <code>learning:access</code>，不能报名或进入学习空间。
        </p>
        <p v-else-if="course.accessType === 'ENROLLMENT_REQUIRED'" class="detail-access-note">本课程需要报名后访问，当前没有可试看视频。</p>
        <p v-else class="detail-access-note">当前发布版本暂时没有可播放的视频课时。</p>
        <RouterLink v-if="course.accessType === 'ENROLLMENT_REQUIRED' && !enrollment
            && authStore.state.status === 'authenticated' && hasLearningAccess
            && learningEntries.some(item => item.lesson.previewable)"
          class="detail-preview-link" :to="`/learn/${course.id}/${learningEntries.find(item => item.lesson.previewable)!.lesson.id}`">
          先试看课程
        </RouterLink>
        <p v-if="actionMessage" class="feedback feedback--success" role="status">{{ actionMessage }}</p>
        <nav v-if="enrollment && hasLearningAccess" class="course-task-entries" aria-label="课程任务入口">
          <RouterLink class="course-task-entry" :to="{ path: `/learn/${course.id}/tasks`, query: { type: 'PRACTICE' } }">
            课程练习<span aria-hidden="true">→</span></RouterLink>
          <RouterLink class="course-task-entry" :to="`/learn/${course.id}/tasks`">
            课程作业与考试<span aria-hidden="true">→</span></RouterLink>
        </nav>
        <p v-if="personalizationError" class="feedback feedback--warning" role="alert">
          {{ personalizationError }}
          <button type="button" @click="loadPersonalization">重试</button>
        </p>
      </div>
    </section>

    <div class="detail-layout">
      <article class="detail-copy">
        <span class="section-kicker">ABOUT THIS COURSE</span>
        <h3>课程介绍</h3>
        <p class="markdown-copy">{{ course.description || "课程介绍正在完善中。" }}</p>
      </article>

      <CourseAnnouncements v-if="enrollment && hasLearningAccess" :course-id="course.id"
        :announcement-id="typeof route.query.announcementId === 'string' ? route.query.announcementId : undefined" />
      <p v-else-if="route.query.announcementId && !personalizationLoading" class="feedback feedback--warning" role="status">
        公告仅对当前有效报名的学习账号开放，请登录或检查报名状态。
      </p>

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
                <RouterLink v-if="canUseLearningLinks && learningEntryFor(lesson.id)?.accessible" class="lesson-play"
                  :to="`/learn/${course.id}/${lesson.id}`">
                  {{ lesson.previewable && course.accessType === "ENROLLMENT_REQUIRED" ? "试看" : "进入学习" }}
                </RouterLink>
                <span v-else-if="learningEntryFor(lesson.id)" class="lesson-locked">需报名</span>
              </li>
            </ol>
          </li>
        </ol>
        <div v-else class="empty-inline">该课程暂未提供目录。</div>
        <p class="scope-note">目录仅预览当前发布版本；报名、进度与继续学习来自 Learning，正式视频仍只在独立学习页打开。课程练习、作业与考试需报名后从本课程入口进入。</p>
      </section>
    </div>
  </template>
</template>
