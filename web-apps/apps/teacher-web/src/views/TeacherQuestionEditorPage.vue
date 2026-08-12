<script setup lang="ts">
/**
 * 教师题目新建/编辑页；父题和子题都提交 T09B QuestionVersionInput，编辑只通过 expectedRevision 创建新版本。
 * 409 时保留当前内存表单并显式要求重新加载；422 按服务端 fieldErrors 映射到结构化字段。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { KnowledgePoint, QuestionVersionInput, TeacherQuestion } from "@edu/api-client";
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import QuestionFields from "../components/QuestionFields.vue";
import { editableQuestionVersion, emptyQuestionVersion, flattenKnowledgePoints, normalizeQuestionVersion } from "../question-form";
import { teacherQuestionApi } from "../question-api";

const props = defineProps<{ mode: "create" | "edit" }>();
const route = useRoute();
const router = useRouter();
const version = ref<QuestionVersionInput>(emptyQuestionVersion());
const current = ref<TeacherQuestion | null>(null);
const knowledgePoints = ref<KnowledgePoint[]>([]);
const loading = ref(props.mode === "edit");
const saving = ref(false);
const mediaBusy = ref(false);
const errorMessage = ref("");
const fieldErrors = ref<Record<string, string>>({});
const conflict = ref(false);
const alert = ref<HTMLElement | null>(null);
let controller = new AbortController();

onMounted(load);
onBeforeUnmount(() => controller.abort());

async function load() {
  controller.abort();
  controller = new AbortController();
  loading.value = true;
  errorMessage.value = "";
  try {
    const pointsPromise = teacherQuestionApi.knowledgePoints(controller.signal).then(flattenKnowledgePoints);
    if (props.mode === "edit") {
      const [question, points] = await Promise.all([teacherQuestionApi.detail(questionId(), controller.signal), pointsPromise]);
      current.value = question;
      knowledgePoints.value = points;
      if (question.status === "ARCHIVED") {
        errorMessage.value = "归档题保持只读，不能创建新版本。";
      } else version.value = editableQuestionVersion(question.currentVersion);
    } else knowledgePoints.value = await pointsPromise;
    conflict.value = false;
  } catch (error) {
    if (!controller.signal.aborted) errorMessage.value = messageFor(error, "题目编辑数据加载失败。");
  } finally { if (!controller.signal.aborted) loading.value = false; }
}

async function submit() {
  if (mediaBusy.value || current.value?.status === "ARCHIVED") return;
  saving.value = true;
  errorMessage.value = "";
  fieldErrors.value = {};
  conflict.value = false;
  try {
    const normalized = normalizeQuestionVersion(version.value);
    const saved = props.mode === "create"
      ? await teacherQuestionApi.create({ clientRequestId: createRequestId(), version: normalized }, controller.signal)
      : await teacherQuestionApi.update(questionId(), { expectedRevision: current.value!.revision, version: normalized }, controller.signal);
    if (props.mode === "create") sessionStorage.removeItem("teacher-question-create-request-id");
    await router.push({ name: "teacher-question-detail", params: { questionId: saved.id }, query: { saved: "1" } });
  } catch (error) {
    if (error instanceof GatewayRequestError) {
      fieldErrors.value = error.fieldErrors;
      if (error.status === 409) conflict.value = true;
    }
    errorMessage.value = messageFor(error, "题目保存失败，当前输入已保留。");
    await nextTick();
    alert.value?.focus();
  } finally { saving.value = false; }
}

async function reloadAfterConflict() {
  if (!window.confirm("重新加载会丢弃当前尚未提交的本地输入，是否继续？")) return;
  await load();
}
function questionId(): string { return String(route.params.questionId ?? ""); }
function createRequestId(): string {
  const key = "teacher-question-create-request-id";
  const existing = sessionStorage.getItem(key);
  if (existing) return existing;
  const created = crypto.randomUUID();
  sessionStorage.setItem(key, created);
  return created;
}
function messageFor(error: unknown, fallback: string): string {
  if (!(error instanceof GatewayRequestError)) return navigator.onLine ? fallback : "当前离线，输入已保留，请恢复网络后重试。";
  if (error.status === 404) return "题目不存在或不可见。";
  if (error.status === 409) return "题目已被更新或状态已变化。当前输入已保留，请重新加载最新版本后再决定如何应用修改。";
  if (error.status === 422) return "部分字段不符合契约，请按字段提示修正；当前输入已保留。";
  if (error.status === 429) return "请求过于频繁，请稍后再提交；当前输入已保留。";
  if (error.status >= 500) return "题库或媒体服务暂时不可用，当前输入已保留。";
  return error.message;
}
</script>

<template>
  <RouterLink class="back-link" :to="mode === 'edit' && current
    ? { name: 'teacher-question-detail', params: { questionId: current.id } } : { name: 'teacher-questions' }">← 返回题库</RouterLink>
  <section class="editor-shell question-editor-shell">
    <header class="editor-heading"><div><span class="section-kicker">{{ mode === "create" ? "NEW QUESTION" : "NEW VERSION" }}</span>
      <h2>{{ mode === "create" ? "新建题目" : "编辑题目新版本" }}</h2></div>
      <span v-if="current">当前 v{{ current.currentVersion.versionNumber }} · r{{ current.revision }}</span></header>
    <p class="editor-intro">普通题和组合题都以完整结构提交；历史版本不会被覆盖。</p>
    <p ref="alert" v-if="errorMessage" class="feedback feedback--error" role="alert" tabindex="-1">{{ errorMessage }}</p>
    <div v-if="conflict" class="conflict-panel"><strong>检测到并发冲突</strong><p>本地输入仍在本页。重新加载前可先复制需要保留的内容。</p>
      <button class="button button--danger" type="button" @click="reloadAfterConflict">确认丢弃本地输入并加载最新版本</button></div>
    <div v-if="loading" class="teacher-loading" aria-busy="true">正在加载题目与知识点…</div>
    <form v-else @submit.prevent="submit">
      <QuestionFields v-model="version" :knowledge-points="knowledgePoints" :field-errors="fieldErrors" field-prefix="version"
        :checkpoint-prefix="mode === 'create' ? `teacher-question:new:${createRequestId()}` : `teacher-question:${questionId()}:r${current?.revision ?? 0}`"
        allow-composite @busy-change="mediaBusy = $event" />
      <footer class="editor-actions"><RouterLink class="button button--quiet" :to="{ name: 'teacher-questions' }">取消</RouterLink>
        <button class="button button--primary" type="submit" :disabled="saving || mediaBusy || current?.status === 'ARCHIVED'">
          {{ saving ? "正在保存…" : mode === "create" ? "创建题目" : "创建不可变新版本" }}
        </button></footer>
    </form>
  </section>
</template>
