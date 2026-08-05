<script setup lang="ts">
/**
 * 教师章节课时与发布管理页；数据来自教师课程详情、目录替换和发布接口。
 * 页面在提交前重排连续 sequence，保存整套目录并携带 revision；权限、发布校验或并发冲突均显示错误。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { ChapterInput, LessonInput, TeacherCourse } from "@edu/api-client";
import { HlsVideoPlayer } from "@edu/ui";
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";

import { teacherCourseApi } from "../course-api";
import MediaUploadField from "../components/MediaUploadField.vue";

type EditableResource = NonNullable<LessonInput["resources"]>[number] & { id?: string };
type EditableLesson = Omit<LessonInput, "resources"> & { id?: string; uiKey: string; resources: EditableResource[] };
type EditableChapter = Omit<ChapterInput, "lessons"> & { id?: string; uiKey: string; lessons: EditableLesson[] };
type TeacherVideoSelection = { lessonId: string; resourceId: string; title: string };

const route = useRoute();
const course = ref<TeacherCourse | null>(null);
const chapters = ref<EditableChapter[]>([]);
const loading = ref(true);
const saving = ref(false);
const publishing = ref(false);
const dirty = ref(false);
const errorMessage = ref("");
const successMessage = ref("");
const videoBusyKeys = ref<Set<string>>(new Set());
const selectedVideo = ref<TeacherVideoSelection | null>(null);
const canPublish = computed(() => Boolean(course.value) && !dirty.value && chapters.value.length > 0
  && chapters.value.every(chapter => chapter.lessons.length > 0
    && chapter.lessons.every(lesson => lesson.type !== "VIDEO" || Boolean(videoMediaId(lesson)))));

onMounted(load);

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const snapshot = await teacherCourseApi.detail(String(route.params.courseId));
    course.value = snapshot;
    syncChapters(snapshot);
    dirty.value = false;
  } catch (error) {
    errorMessage.value = messageFor(error, "课程目录加载失败");
  } finally {
    loading.value = false;
  }
}

function addChapter() {
  chapters.value.push({ uiKey: crypto.randomUUID(), title: `第 ${chapters.value.length + 1} 章`,
    sequence: chapters.value.length + 1, lessons: [] });
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

function addLesson(chapter: EditableChapter) {
  chapter.lessons.push({ uiKey: crypto.randomUUID(), title: `第 ${chapter.lessons.length + 1} 课`, type: "VIDEO",
    sequence: chapter.lessons.length + 1, previewable: false, resources: [] });
  dirty.value = true;
}

function removeLesson(chapter: EditableChapter, index: number) {
  chapter.lessons.splice(index, 1);
  resequenceLessons(chapter);
  dirty.value = true;
}

function moveLesson(chapter: EditableChapter, index: number, delta: number) {
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

function resequenceLessons(chapter: EditableChapter) {
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
      chapters: chapters.value.map(chapter => ({ title: chapter.title, sequence: chapter.sequence,
        lessons: chapter.lessons.map(lesson => ({ title: lesson.title, type: lesson.type,
          sequence: lesson.sequence, previewable: lesson.previewable,
          resources: lesson.resources.map(resource => ({ mediaId: resource.mediaId,
            purpose: resource.purpose, sequence: resource.sequence })) })) }))
    });
    course.value = updated;
    syncChapters(updated);
    dirty.value = false;
    successMessage.value = updated.publishedVersion
      ? `目录已保存到 ${updated.draftVersion ?? updated.version}，线上版本尚未改变。` : "课程目录已保存。";
  } catch (error) {
    errorMessage.value = messageFor(error, "课程目录保存失败");
  } finally {
    saving.value = false;
  }
}

/** 从课程快照构造本地可编辑投影；id 仅用于上传会话和 owner 播放，不进入目录写 DTO。 */
function syncChapters(snapshot: TeacherCourse) {
  chapters.value = snapshot.chapters.map(chapter => ({ id: chapter.id, uiKey: chapter.id, title: chapter.title,
    sequence: chapter.sequence, lessons: chapter.lessons.map(lesson => ({ id: lesson.id, uiKey: lesson.id, title: lesson.title,
      type: lesson.type, sequence: lesson.sequence, previewable: lesson.previewable,
      resources: lesson.resources.map(resource => ({ id: resource.id, mediaId: resource.mediaId,
        purpose: resource.purpose, sequence: resource.sequence })) })) }));
}

function videoMediaId(lesson: EditableLesson): string | null {
  return lesson.resources.find(resource => resource.purpose === "LESSON_VIDEO")?.mediaId ?? null;
}

/** READY 回调才写入 LESSON_VIDEO 输入；替换时保留其他用途，移除时只解除视频绑定。 */
function setVideoMedia(lesson: EditableLesson, mediaId: string | null) {
  const existing = lesson.resources.find(resource => resource.purpose === "LESSON_VIDEO");
  lesson.resources = lesson.resources.filter(resource => resource.purpose !== "LESSON_VIDEO");
  if (mediaId) lesson.resources.push({ mediaId, purpose: "LESSON_VIDEO", sequence: 1,
    id: existing?.mediaId === mediaId ? existing.id : undefined });
  selectedVideo.value = null;
  dirty.value = true;
}

function lessonTypeChanged(lesson: EditableLesson) {
  if (lesson.type !== "VIDEO") setVideoMedia(lesson, null);
}

function setVideoBusy(key: string, busy: boolean) {
  const next = new Set(videoBusyKeys.value);
  if (busy) next.add(key); else next.delete(key);
  videoBusyKeys.value = next;
}

function selectTeacherVideo(lesson: EditableLesson, resource: EditableResource) {
  if (!lesson.id || !resource.id) return;
  selectedVideo.value = { lessonId: lesson.id, resourceId: resource.id, title: lesson.title };
}

/** 教师预览只调用 owner 课程域接口；页面既不提交 mediaId，也不直调媒体服务授权。 */
function getTeacherGrant(signal: AbortSignal) {
  if (!course.value || !selectedVideo.value) throw new Error("VIDEO_NOT_SELECTED");
  return teacherCourseApi.playbackGrant(course.value.id, selectedVideo.value.lessonId,
    selectedVideo.value.resourceId, signal);
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
      <span>视频 READY 后保存目录，才可 owner 预览</span>
    </div>
    <p v-if="course.publishedVersion && course.hasUnpublishedChanges" class="version-notice">
      当前线上版本为 {{ course.publishedVersion }}；发布 {{ course.draftVersion }} 前，学生端目录不会改变。
    </p>
    <p v-if="dirty" class="feedback feedback--warning" role="status">有尚未保存的目录修改。保存后才能发布或下架。</p>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>

    <form @submit.prevent="save" @input="dirty = true">
      <ol v-if="chapters.length" class="curriculum-editor__chapters">
        <li v-for="(chapter, chapterIndex) in chapters" :key="chapter.uiKey" class="curriculum-chapter">
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
            <li v-for="(lesson, lessonIndex) in chapter.lessons" :key="lesson.uiKey">
              <span class="lesson-order">{{ lesson.sequence }}</span>
              <label><span>课时名称</span><input v-model="lesson.title" required maxlength="120"></label>
              <label><span>目录类型</span><select v-model="lesson.type" @change="lessonTypeChanged(lesson)">
                <option value="VIDEO">视频</option><option value="ARTICLE">图文</option>
                <option value="ASSESSMENT">测评目录项</option>
              </select></label>
              <label class="preview-toggle"><input v-model="lesson.previewable" type="checkbox"><span>允许试看</span></label>
              <div class="order-actions">
                <button type="button" :disabled="lessonIndex === 0" title="课时上移" @click="moveLesson(chapter, lessonIndex, -1)">↑</button>
                <button type="button" :disabled="lessonIndex === chapter.lessons.length - 1" title="课时下移" @click="moveLesson(chapter, lessonIndex, 1)">↓</button>
                <button class="danger-link" type="button" @click="removeLesson(chapter, lessonIndex)">删除</button>
              </div>
              <div v-if="lesson.type === 'VIDEO'" class="lesson-video-editor">
                <MediaUploadField v-if="lesson.id" :model-value="videoMediaId(lesson)" purpose="LESSON_VIDEO"
                  accept="video/mp4,video/webm,video/quicktime" label="课时视频"
                  :checkpoint-key="`teacher-lesson-video:${course.id}:${lesson.id}`"
                  @update:model-value="setVideoMedia(lesson, $event)"
                  @busy-change="setVideoBusy(lesson.id!, $event)" />
                <p v-else class="lesson-video-editor__hint">请先保存目录，为新课时生成稳定 ID 后再上传视频。</p>
                <button v-if="lesson.id && lesson.resources.find(item => item.purpose === 'LESSON_VIDEO')?.id"
                  class="button button--quiet lesson-preview-button" type="button"
                  @click="selectTeacherVideo(lesson, lesson.resources.find(item => item.purpose === 'LESSON_VIDEO')!)">
                  owner 预览
                </button>
                <small v-else-if="videoMediaId(lesson)">视频已 READY，请先保存目录后预览。</small>
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
      <p class="scope-note">目录只提交稳定 mediaId、purpose、sequence；不会提交 Bucket、Object Key 或 OSS URL，也不创建报名和学习进度。</p>
      <section v-if="selectedVideo" class="teacher-video-preview">
        <header><strong>{{ selectedVideo.title }}</strong><button type="button" @click="selectedVideo = null">关闭预览</button></header>
        <HlsVideoPlayer :grant-key="`${course.id}:${selectedVideo.lessonId}:${selectedVideo.resourceId}`"
          :title="`${selectedVideo.title}教师预览`" :grant-provider="getTeacherGrant" />
      </section>
      <footer class="editor-actions editor-actions--split">
        <div>
          <button v-if="course.publicationStatus === 'PUBLISHED'" class="button button--danger" type="button"
            :disabled="publishing || dirty" @click="updatePublication('UNPUBLISHED')">下架课程</button>
          <button v-else class="button button--publish" type="button"
            :disabled="publishing || !canPublish" @click="updatePublication('PUBLISHED')">
            {{ course.publicationStatus === "UNPUBLISHED" && !course.hasUnpublishedChanges ? "重新发布" : "发布当前版本" }}
          </button>
        </div>
        <button class="button button--primary" type="submit" :disabled="saving || videoBusyKeys.size > 0 || !dirty">{{ saving ? "保存中…" : "保存目录" }}</button>
      </footer>
    </form>
  </section>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
