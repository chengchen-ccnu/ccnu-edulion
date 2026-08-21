<script setup lang="ts">
/**
 * 教师题目只读详情与不可变版本历史；当前版本和历史预览由 URL versionId/versionPage 恢复。
 * 历史读取不会提交任何写请求；生命周期操作携带当前 revision，冲突时只提示刷新，不静默重放。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { QuestionVersion, QuestionVersionPage, TeacherQuestion } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import QuestionVersionPreview from "../components/QuestionVersionPreview.vue";
import { teacherQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const pageSize = 10;
const question = ref<TeacherQuestion | null>(null);
const history = ref<QuestionVersionPage | null>(null);
const preview = ref<QuestionVersion | null>(null);
const loading = ref(true);
const historyError = ref("");
const errorMessage = ref("");
const successMessage = ref("");
const actionBusy = ref(false);
let controller: AbortController | null = null;
const pageCount = computed(() => Math.max(1, Math.ceil((history.value?.total ?? 0) / pageSize)));

watch(() => route.fullPath, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = !question.value;
  errorMessage.value = "";
  historyError.value = "";
  try {
    const detail = await teacherQuestionApi.detail(questionId(), controller.signal);
    question.value = detail;
    const selected = selectedVersionId();
    const [versionsResult, previewResult] = await Promise.allSettled([
      teacherQuestionApi.versions(questionId(), { page: historyPage(), size: pageSize }, controller.signal),
      selected && selected !== detail.currentVersion.id
        ? teacherQuestionApi.version(questionId(), selected, controller.signal) : Promise.resolve(detail.currentVersion)
    ]);
    if (versionsResult.status === "fulfilled") history.value = versionsResult.value;
    else historyError.value = messageFor(versionsResult.reason, "版本历史加载失败，可单独重试。");
    if (previewResult.status === "fulfilled") preview.value = previewResult.value;
    else errorMessage.value = messageFor(previewResult.reason, "指定版本加载失败。");
  } catch (error) {
    if (!controller.signal.aborted) errorMessage.value = messageFor(error, "题目详情加载失败。");
  } finally { if (!controller.signal.aborted) loading.value = false; }
}
async function chooseVersion(versionId?: string) {
  await router.push({ name: "teacher-question-detail", params: { questionId: questionId() },
    query: { ...route.query, versionId: versionId || undefined } });
}
async function changeHistoryPage(page: number) {
  if (page < 1 || page > pageCount.value || page === historyPage()) return;
  await router.push({ name: "teacher-question-detail", params: { questionId: questionId() },
    query: { ...route.query, versionPage: page === 1 ? undefined : String(page) } });
}
async function activate() { await updateStatus("ACTIVE", "确认启用该题？启用后仍可通过编辑创建新版本。"); }
async function archive() { await updateStatus("ARCHIVED", "确认归档该题？归档后题目将永久保持只读，T10 不支持重新启用。"); }
async function updateStatus(status: "ACTIVE" | "ARCHIVED", confirmation: string) {
  if (!question.value || !window.confirm(confirmation)) return;
  actionBusy.value = true;
  errorMessage.value = "";
  successMessage.value = "";
  try {
    question.value = await teacherQuestionApi.updateStatus(questionId(), {
      expectedRevision: question.value.revision, status
    }, controller?.signal);
    preview.value = question.value.currentVersion;
    successMessage.value = status === "ACTIVE" ? "题目已启用。" : "题目已归档并进入只读状态。";
  } catch (error) { errorMessage.value = messageFor(error, "生命周期操作失败。"); }
  finally { actionBusy.value = false; }
}
function questionId(): string { return String(route.params.questionId ?? ""); }
function selectedVersionId(): string { return typeof route.query.versionId === "string" ? route.query.versionId : ""; }
function historyPage(): number {
  const value = typeof route.query.versionPage === "string" ? Number(route.query.versionPage) : 1;
  return Number.isInteger(value) && value > 0 ? value : 1;
}
function messageFor(error: unknown, fallback: string): string {
  if (!(error instanceof GatewayRequestError)) return navigator.onLine ? fallback : "当前离线，恢复网络后可重试。";
  if (error.status === 404) return "题目不存在或不可见。";
  if (error.status === 409) return "题目版本或生命周期已变化，请刷新后重试。";
  if (error.status === 429) return "请求过于频繁，请稍后重试。";
  if (error.status >= 500) return "题库服务暂时不可用，请稍后重试。";
  return error.message;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-questions' }">← 返回题库</RouterLink>
  <div v-if="loading && !question" class="teacher-loading" aria-busy="true">正在加载题目详情…</div>
  <template v-else-if="question">
    <section class="teacher-page-heading question-detail-heading"><div><span class="section-kicker">QUESTION DETAIL</span>
      <h2>题目详情</h2><p>{{ question.scope === "SYSTEM" ? "平台系统题（只读）" : "我的私有题" }} · {{ question.status }} · 当前 v{{ question.currentVersion.versionNumber }} · r{{ question.revision }}</p></div>
      <div class="heading-actions"><RouterLink v-if="question.editable && question.status !== 'ARCHIVED'" class="button button--quiet"
        :to="{ name: 'teacher-question-edit', params: { questionId: question.id } }">编辑新版本</RouterLink>
        <button v-if="question.editable && question.status === 'DRAFT'" class="button button--publish" type="button" :disabled="actionBusy" @click="activate">启用</button>
        <button v-if="question.editable && question.status !== 'ARCHIVED'" class="button button--danger" type="button" :disabled="actionBusy" @click="archive">归档</button></div>
    </section>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }} <button type="button" @click="load">刷新</button></p>
    <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>
    <div class="question-detail-layout">
      <main><QuestionVersionPreview v-if="preview" :version="preview" :historical="preview.id !== question.currentVersion.id" /></main>
      <aside class="version-history"><header><div><strong>版本历史</strong><small>最新在前</small></div>
        <button type="button" :class="{ active: !selectedVersionId() }" @click="chooseVersion()">当前版本</button></header>
        <p v-if="historyError" class="feedback feedback--error" role="alert">{{ historyError }} <button type="button" @click="load">重试</button></p>
        <ol v-if="history?.items.length"><li v-for="item in history.items" :key="item.id">
          <button type="button" :class="{ active: preview?.id === item.id }" @click="chooseVersion(item.current ? undefined : item.id)">
            <strong>v{{ item.versionNumber }} <small v-if="item.current">当前</small></strong>
            <span>{{ item.type }} · {{ item.childCount }} 子题</span><time>{{ new Date(item.createdAt).toLocaleString("zh-CN") }}</time>
          </button></li></ol>
        <nav v-if="history && history.total > pageSize" class="pagination" aria-label="版本历史分页">
          <button type="button" :disabled="historyPage() <= 1" @click="changeHistoryPage(historyPage() - 1)">上一页</button>
          <span>{{ historyPage() }} / {{ pageCount }}</span><button type="button" :disabled="historyPage() >= pageCount" @click="changeHistoryPage(historyPage() + 1)">下一页</button>
        </nav>
      </aside>
    </div>
  </template>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
