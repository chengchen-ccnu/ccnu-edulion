<script setup lang="ts">
/**
 * 教师新建课程页；分类来自公开分类 API，提交到 POST /api/v1/teacher/courses。
 * clientRequestId 保证重复提交返回同一草稿；成功跳转编辑页，校验、权限和网络失败保留表单并提示。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CategorySummary, CreateCourseRequest } from "@edu/api-client";
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import CourseMetadataFields from "../components/CourseMetadataFields.vue";
import { teacherCourseApi } from "../course-api";
import { authStore } from "../auth";

const router = useRouter();
const categories = ref<CategorySummary[]>([]);
const saving = ref(false);
const errorMessage = ref("");
const fieldErrors = ref<Record<string, string>>({});
const form = reactive<CreateCourseRequest>({
  clientRequestId: crypto.randomUUID(),
  title: "",
  summary: "",
  description: "",
  coverUrl: "",
  instructorDisplayName: authStore.state.currentUser?.displayName ?? "",
  categoryId: "",
  level: "BEGINNER",
  accessType: "PUBLIC"
});

onMounted(async () => {
  try {
    categories.value = await teacherCourseApi.categories();
    if (!form.categoryId && categories.value.length === 1) form.categoryId = categories.value[0].id;
  } catch (error) {
    errorMessage.value = messageFor(error, "课程分类加载失败");
  }
});

async function submit() {
  saving.value = true;
  errorMessage.value = "";
  fieldErrors.value = {};
  try {
    const course = await teacherCourseApi.create({ ...form });
    await router.push({ name: "teacher-course-edit", params: { courseId: course.id }, query: { created: "1" } });
  } catch (error) {
    if (error instanceof GatewayRequestError) fieldErrors.value = error.fieldErrors;
    errorMessage.value = messageFor(error, "课程创建失败");
  } finally {
    saving.value = false;
  }
}

function messageFor(error: unknown, fallback: string): string {
  return error instanceof GatewayRequestError ? error.message : fallback;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-courses' }">← 返回课程管理</RouterLink>
  <section class="editor-shell">
    <header class="editor-heading">
      <div><span class="section-kicker">NEW COURSE</span><h2>创建课程草稿</h2></div>
      <span>步骤 1 / 2 · 基本信息</span>
    </header>
    <p class="editor-intro">课程会以草稿创建。完成目录编排并主动发布前，学生端不可见。</p>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <form @submit.prevent="submit">
      <CourseMetadataFields v-model:title="form.title" v-model:summary="form.summary"
        v-model:description="form.description" v-model:cover-url="form.coverUrl"
        v-model:instructor-display-name="form.instructorDisplayName" v-model:category-id="form.categoryId"
        v-model:level="form.level" v-model:access-type="form.accessType"
        :categories="categories" :field-errors="fieldErrors" />
      <footer class="editor-actions">
        <RouterLink class="button button--quiet" :to="{ name: 'teacher-courses' }">取消</RouterLink>
        <button class="button button--primary" type="submit" :disabled="saving || !categories.length">
          {{ saving ? "正在创建…" : "创建并继续编辑" }}
        </button>
      </footer>
    </form>
  </section>
</template>
