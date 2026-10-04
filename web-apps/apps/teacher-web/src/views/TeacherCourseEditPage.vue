<script setup lang="ts">
/**
 * 教师课程元数据编辑页；读取和更新当前教师拥有的课程编辑快照。
 * 提交携带 expectedRevision，服务端冲突时要求重新加载；已发布课程的首次编辑由后端复制新草稿。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CategorySummary, TeacherCourse, UpdateCourseRequest } from "@edu/api-client";
import { onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";

import CourseMetadataFields from "../components/CourseMetadataFields.vue";
import MediaUploadField from "../components/MediaUploadField.vue";
import { teacherCourseApi } from "../course-api";

const route = useRoute();
const course = ref<TeacherCourse | null>(null);
const categories = ref<CategorySummary[]>([]);
const loading = ref(true);
const saving = ref(false);
const coverBusy = ref(false);
const errorMessage = ref("");
const successMessage = ref(route.query.created === "1" ? "课程草稿已创建，请继续完善信息。" : "");
const fieldErrors = ref<Record<string, string>>({});
const form = reactive<UpdateCourseRequest>({ expectedRevision: 0, title: "", summary: "", description: "",
  coverMediaId: null, instructorDisplayName: "", categoryId: "", level: "BEGINNER", accessType: "PUBLIC" });

onMounted(load);

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const courseId = String(route.params.courseId);
    const [snapshot, options] = await Promise.all([teacherCourseApi.detail(courseId), teacherCourseApi.categories()]);
    course.value = snapshot;
    categories.value = options;
    syncForm(snapshot);
  } catch (error) {
    errorMessage.value = messageFor(error, "课程编辑信息加载失败");
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (!course.value) return;
  saving.value = true;
  errorMessage.value = "";
  successMessage.value = "";
  fieldErrors.value = {};
  try {
    const updated = await teacherCourseApi.update(course.value.id, { ...form });
    course.value = updated;
    syncForm(updated);
    successMessage.value = updated.publicationStatus === "PUBLISHED"
      ? "元数据已保存为新的未发布版本，线上课程尚未改变。" : "课程元数据已保存。";
  } catch (error) {
    if (error instanceof GatewayRequestError) fieldErrors.value = error.fieldErrors;
    errorMessage.value = messageFor(error, "课程元数据保存失败");
  } finally {
    saving.value = false;
  }
}

function syncForm(snapshot: TeacherCourse) {
  Object.assign(form, { expectedRevision: snapshot.revision, title: snapshot.title, summary: snapshot.summary,
    description: snapshot.description, coverMediaId: snapshot.coverMediaId,
    instructorDisplayName: snapshot.instructorDisplayName, categoryId: snapshot.category.id,
    level: snapshot.level, accessType: snapshot.accessType });
}

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof GatewayRequestError && error.status === 409) return "课程版本已变化，请重新加载后再编辑。";
  if (error instanceof GatewayRequestError && error.status === 404) return "课程不存在或不属于当前教师。";
  return error instanceof GatewayRequestError ? error.message : fallback;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-courses' }">← 返回课程管理</RouterLink>
  <div v-if="loading" class="teacher-loading">正在读取课程编辑版本…</div>
  <section v-else-if="course" class="editor-shell">
    <header class="editor-heading">
      <div><span class="section-kicker">COURSE METADATA</span><h2>编辑基本信息</h2></div>
      <div class="version-stack"><strong>{{ course.version }}</strong><span>revision {{ course.revision }}</span></div>
    </header>
    <nav class="editor-subnav" aria-label="课程编辑上下文"><strong>基本信息</strong>
      <RouterLink :to="{ name: 'teacher-course-curriculum', params: { courseId: course.id } }">章节与课时</RouterLink>
      <RouterLink :to="{ name: 'teacher-course-announcements', params: { courseId: course.id }, query: { from: 'edit' } }">课程公告</RouterLink>
    </nav>
    <p v-if="course.publishedVersion && course.hasUnpublishedChanges" class="version-notice">
      正在编辑新版本 {{ course.draftVersion }}；当前线上 {{ course.publishedVersion }} 不会被直接覆盖。
    </p>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>
    <form @submit.prevent="submit">
      <MediaUploadField v-model="form.coverMediaId" purpose="COURSE_COVER" accept="image/jpeg,image/png,image/webp"
        label="课程封面" :current-url="course.coverUrl"
        :checkpoint-key="`teacher-course-cover:${course.id}`" @busy-change="coverBusy = $event" />
      <CourseMetadataFields v-model:title="form.title" v-model:summary="form.summary"
        v-model:description="form.description"
        v-model:instructor-display-name="form.instructorDisplayName" v-model:category-id="form.categoryId"
        v-model:level="form.level" v-model:access-type="form.accessType"
        :categories="categories" :field-errors="fieldErrors" />
      <footer class="editor-actions">
        <RouterLink class="button button--quiet" :to="{ name: 'teacher-course-curriculum', params: { courseId: course.id } }">下一步：编排目录</RouterLink>
        <button class="button button--primary" type="submit" :disabled="saving || coverBusy">{{ saving ? "保存中…" : "保存元数据" }}</button>
      </footer>
    </form>
  </section>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
