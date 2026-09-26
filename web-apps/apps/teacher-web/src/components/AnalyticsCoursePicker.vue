<script setup lang="ts">
/** 只列出教师课程 API 返回的本人课程；选中项输出课程 ID，详情仍由 Learning owner 校验。 */
import { ref, watch } from "vue";
import type { TeacherCoursePage } from "@edu/api-client";
import { teacherCourseApi } from "../course-api";
import { analyticsError } from "../learning-analytics-state";
defineProps<{ courseId: string }>();
const emit = defineEmits<{ select: [courseId: string] }>();
const courses = ref<TeacherCoursePage | null>(null);
const page = ref(1);
const error = ref("");
const loading = ref(false);
watch(page, load, { immediate: true });
async function load() {
  loading.value = true; error.value = "";
  try { courses.value = await teacherCourseApi.list({ page: page.value, size: 20 }); }
  catch (cause) { error.value = analyticsError(cause); }
  finally { loading.value = false; }
}
</script>
<template>
  <section class="analytics-course-picker" aria-label="选择本人课程">
    <label>课程 <select :value="courseId" @change="emit('select', ($event.target as HTMLSelectElement).value)">
      <option value="">请选择课程</option>
      <option v-if="courseId && !courses?.items.some(course => course.id === courseId)" :value="courseId">当前深链课程</option>
      <option v-for="course in courses?.items ?? []" :key="course.id" :value="course.id">{{ course.title }}</option>
    </select></label>
    <div class="analytics-course-picker__pages"><button type="button" :disabled="page <= 1 || loading" @click="page--">上一组课程</button><span>第 {{ page }} 页</span><button type="button" :disabled="loading || !courses || page * 20 >= courses.total" @click="page++">下一组课程</button></div>
    <p v-if="error" role="alert">课程列表读取失败：{{ error }} <button type="button" @click="load">重试</button></p>
  </section>
</template>
