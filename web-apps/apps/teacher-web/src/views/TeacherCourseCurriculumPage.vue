<script setup lang="ts">
/**
 * 教师章节课时与发布管理页；数据来自教师课程详情、目录替换和发布接口。
 * 页面在提交前重排连续 sequence，保存整套目录并携带 revision；权限、发布校验或并发冲突均显示错误。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { ChapterInput, LessonInput, TeacherCourse } from "@edu/api-client";
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";

import { teacherCourseApi } from "../course-api";

const route = useRoute();
const course = ref<TeacherCourse | null>(null);
const chapters = ref<ChapterInput[]>([]);
const loading = ref(true);
const saving = ref(false);
const publishing = ref(false);
const dirty = ref(false);
const errorMessage = ref("");
const successMessage = ref("");
const canPublish = computed(() => Boolean(course.value) && !dirty.value && chapters.value.length > 0
  && chapters.value.every(chapter => chapter.lessons.length > 0));

onMounted(load);

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const snapshot = await teacherCourseApi.detail(String(route.params.courseId));
    course.value = snapshot;
    chapters.value = snapshot.chapters.map(chapter => ({
      title: chapter.title,
      sequence: chapter.sequence,
      lessons: chapter.lessons.map(lesson => ({ title: lesson.title, type: lesson.type,
        sequence: lesson.sequence, previewable: lesson.previewable,
        resources: lesson.resources.map(resource => ({ mediaId: resource.mediaId,
          purpose: resource.purpose, sequence: resource.sequence })) }))
    }));
    dirty.value = false;
  } catch (error) {
    errorMessage.value = messageFor(error, "课程目录加载失败");
  } finally {
    loading.value = false;
  }
}

function addChapter() {
  chapters.value.push({ title: `第 ${chapters.value.length + 1} 章`, sequence: chapters.value.length + 1, lessons: [] });
  dirty.value = true;
}

function removeChapter(index: number) {
  if (!window.confirm("确认删除该章节及其全部课时？保存后才会提交到服务端。")) return;
  chapters.value.splice(index, 1);
  resequence();
  dirty.value = true;
}

function moveChapter(index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= chapters.value.length) return;
  const [chapter] = chapters.value.splice(index, 1);
  chapters.value.splice(target, 0, chapter);
  resequence();
  dirty.value = true;
}

function addLesson(chapter: ChapterInput) {
  chapter.lessons.push({ title: `第 ${chapter.lessons.length + 1} 课`, type: "VIDEO",
    sequence: chapter.lessons.length + 1, previewable: false, resources: [] });
  dirty.value = true;
}

function removeLesson(chapter: ChapterInput, index: number) {
  chapter.lessons.splice(index, 1);
  resequenceLessons(chapter);
  dirty.value = true;
}

function moveLesson(chapter: ChapterInput, index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= chapter.lessons.length) return;
  const [lesson] = chapter.lessons.splice(index, 1);
  chapter.lessons.splice(target, 0, lesson);
  resequenceLessons(chapter);
  dirty.value = true;
}

function resequence() {
  chapters.value.forEach((chapter, index) => {
    chapter.sequence = index + 1;
    resequenceLessons(chapter);
  });
}

function resequenceLessons(chapter: ChapterInput) {
  chapter.lessons.forEach((lesson, index) => { lesson.sequence = index + 1; });
}

async function save() {
  if (!course.value) return;
  saving.value = true;
  errorMessage.value = "";
  successMessage.value = "";
  resequence();
  try {
    const updated = await teacherCourseApi.replaceCurriculum(course.value.id, {
      expectedRevision: course.value.revision,
      chapters: chapters.value.map(chapter => ({ ...chapter,
        lessons: chapter.lessons.map((lesson: LessonInput) => ({ ...lesson })) }))
    });
    course.value = updated;
    dirty.value = false;
    successMessage.value = updated.publishedVersion
      ? `目录已保存到 ${updated.draftVersion ?? updated.version}，线上版本尚未改变。` : "课程目录已保存。";
  } catch (error) {
    errorMessage.value = messageFor(error, "课程目录保存失败");
  } finally {
    saving.value = false;
  }
}

async function updatePublication(status: "PUBLISHED" | "UNPUBLISHED") {
  if (!course.value || dirty.value) return;
  const prompt = status === "PUBLISHED"
    ? `确认发布“${course.value.title}”的 ${course.value.version} 版本？`
    : `确认下架“${course.value.title}”？学生端将立即不可见。`;
  if (!window.confirm(prompt)) return;
  publishing.value = true;
  errorMessage.value = "";
  successMessage.value = "";
  try {
    await teacherCourseApi.updatePublication(course.value.id, { status, expectedRevision: course.value.revision });
    await load();
    successMessage.value = status === "PUBLISHED" ? "课程已经发布到学生端。" : "课程已经下架。";
  } catch (error) {
    errorMessage.value = messageFor(error, status === "PUBLISHED" ? "课程发布失败" : "课程下架失败");
  } finally {
    publishing.value = false;
  }
}

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof GatewayRequestError && error.status === 409) return "课程版本已变化，请重新加载后再编排。";
  if (error instanceof GatewayRequestError && error.status === 422) return error.message;
  if (error instanceof GatewayRequestError && error.status === 404) return "课程不存在或不属于当前教师。";
  return error instanceof GatewayRequestError ? error.message : fallback;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-courses' }">← 返回课程管理</RouterLink>
  <div v-if="loading" class="teacher-loading">正在读取章节课时…</div>
  <section v-else-if="course" class="editor-shell curriculum-editor">
    <header class="editor-heading">
      <div><span class="section-kicker">CURRICULUM EDITOR</span><h2>{{ course.title }}</h2></div>
      <div class="version-stack"><strong>{{ course.version }}</strong><span>revision {{ course.revision }}</span></div>
    </header>
    <div class="editor-subnav">
      <RouterLink :to="{ name: 'teacher-course-edit', params: { courseId: course.id } }">基本信息</RouterLink>
      <strong>章节与课时</strong>
      <span>资源与测评绑定不在 T06 范围</span>
    </div>
    <p v-if="course.publishedVersion && course.hasUnpublishedChanges" class="version-notice">
      当前线上版本为 {{ course.publishedVersion }}；发布 {{ course.draftVersion }} 前，学生端目录不会改变。
    </p>
    <p v-if="dirty" class="feedback feedback--warning" role="status">有尚未保存的目录修改。保存后才能发布或下架。</p>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>

    <form @submit.prevent="save" @input="dirty = true">
      <ol v-if="chapters.length" class="curriculum-editor__chapters">
        <li v-for="(chapter, chapterIndex) in chapters" :key="chapterIndex" class="curriculum-chapter">
          <header>
            <span class="order-number">{{ String(chapter.sequence).padStart(2, "0") }}</span>
            <label><span>章节名称</span><input v-model="chapter.title" required maxlength="120"></label>
            <div class="order-actions">
              <button type="button" :disabled="chapterIndex === 0" title="章节上移" @click="moveChapter(chapterIndex, -1)">↑</button>
              <button type="button" :disabled="chapterIndex === chapters.length - 1" title="章节下移" @click="moveChapter(chapterIndex, 1)">↓</button>
              <button class="danger-link" type="button" @click="removeChapter(chapterIndex)">删除章</button>
            </div>
          </header>
          <ol class="curriculum-lessons">
            <li v-for="(lesson, lessonIndex) in chapter.lessons" :key="lessonIndex">
              <span class="lesson-order">{{ lesson.sequence }}</span>
              <label><span>课时名称</span><input v-model="lesson.title" required maxlength="120"></label>
              <label><span>目录类型</span><select v-model="lesson.type">
                <option value="VIDEO">视频</option><option value="ARTICLE">图文</option>
                <option value="ASSESSMENT">测评目录项</option>
              </select></label>
              <label class="preview-toggle"><input v-model="lesson.previewable" type="checkbox"><span>允许试看</span></label>
              <div class="order-actions">
                <button type="button" :disabled="lessonIndex === 0" title="课时上移" @click="moveLesson(chapter, lessonIndex, -1)">↑</button>
                <button type="button" :disabled="lessonIndex === chapter.lessons.length - 1" title="课时下移" @click="moveLesson(chapter, lessonIndex, 1)">↓</button>
                <button class="danger-link" type="button" @click="removeLesson(chapter, lessonIndex)">删除</button>
              </div>
            </li>
          </ol>
          <button class="add-row" type="button" @click="addLesson(chapter)">＋ 添加课时</button>
        </li>
      </ol>
      <div v-else class="curriculum-empty">
        <span>目录还是空的</span><h3>从第一个章节开始</h3><p>发布要求至少一个章节，且每章至少包含一个课时。</p>
      </div>
      <button class="add-chapter" type="button" @click="addChapter">＋ 添加章节</button>
      <p class="scope-note">课时只保存标题、顺序、目录类型和试看标识；不会上传或播放视频，也不会创建题目、报名或学习进度。</p>
      <footer class="editor-actions editor-actions--split">
        <div>
          <button v-if="course.publicationStatus === 'PUBLISHED'" class="button button--danger" type="button"
            :disabled="publishing || dirty" @click="updatePublication('UNPUBLISHED')">下架课程</button>
          <button v-else class="button button--publish" type="button"
            :disabled="publishing || !canPublish" @click="updatePublication('PUBLISHED')">
            {{ course.publicationStatus === "UNPUBLISHED" && !course.hasUnpublishedChanges ? "重新发布" : "发布当前版本" }}
          </button>
        </div>
        <button class="button button--primary" type="submit" :disabled="saving || !dirty">{{ saving ? "保存中…" : "保存目录" }}</button>
      </footer>
    </form>
  </section>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
