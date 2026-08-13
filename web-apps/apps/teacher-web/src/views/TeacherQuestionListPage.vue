<script setup lang="ts">
/**
 * 教师本人题库列表；筛选和分页以 URL query 为唯一页面状态，刷新与深链接会重新调用 owner-only 列表契约。
 * 列表只消费 QuestionSummary，不请求或展示完整答案/解析；失败时保留上次成功内容并允许定向重试。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { KnowledgePoint, QuestionDifficulty, QuestionPage, QuestionStatus, QuestionType } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { flattenKnowledgePoints } from "../question-form";
import { teacherQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const pageSize = 20;
const result = ref<QuestionPage | null>(null);
const knowledgePoints = ref<KnowledgePoint[]>([]);
const loading = ref(false);
const errorMessage = ref("");
let controller: AbortController | null = null;
const form = ref(readFilters());
const pageCount = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / pageSize)));

watch(() => route.fullPath, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load() {
  controller?.abort();
  controller = new AbortController();
  form.value = readFilters();
  loading.value = true;
  errorMessage.value = "";
  try {
    const [questions, points] = await Promise.all([
      teacherQuestionApi.list({ page: currentPage(), size: pageSize,
        keyword: form.value.keyword || undefined, type: form.value.type || undefined,
        difficulty: form.value.difficulty || undefined, status: form.value.status || undefined,
        knowledgePointId: form.value.knowledgePointId || undefined }, controller.signal),
      knowledgePoints.value.length ? Promise.resolve(knowledgePoints.value)
        : teacherQuestionApi.knowledgePoints(controller.signal).then(flattenKnowledgePoints)
    ]);
    result.value = questions;
    knowledgePoints.value = points;
  } catch (error) {
    if (!controller.signal.aborted) errorMessage.value = messageFor(error, "题库加载失败，请检查网络后重试。");
  } finally { if (!controller.signal.aborted) loading.value = false; }
}
async function applyFilters() {
  await router.push({ name: "teacher-questions", query: compactQuery({ ...form.value, page: undefined }) });
}
async function resetFilters() {
  form.value = { keyword: "", type: "", difficulty: "", status: "", knowledgePointId: "" };
  await router.push({ name: "teacher-questions" });
}
async function changePage(page: number) {
  if (page < 1 || page > pageCount.value || page === currentPage()) return;
  await router.push({ name: "teacher-questions", query: compactQuery({ ...route.query, page: page === 1 ? undefined : String(page) }) });
}
function currentPage(): number {
  const page = typeof route.query.page === "string" ? Number(route.query.page) : 1;
  return Number.isInteger(page) && page > 0 ? page : 1;
}
function readFilters() {
  return {
    keyword: stringQuery("keyword"), type: stringQuery("type") as QuestionType | "",
    difficulty: stringQuery("difficulty") as QuestionDifficulty | "",
    status: stringQuery("status") as QuestionStatus | "", knowledgePointId: stringQuery("knowledgePointId")
  };
}
function stringQuery(key: string): string { return typeof route.query[key] === "string" ? route.query[key] as string : ""; }
function compactQuery(query: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(Object.entries(query).filter(([, value]) => typeof value === "string" && value.length)) as Record<string, string>;
}
function messageFor(error: unknown, fallback: string): string {
  if (!(error instanceof GatewayRequestError)) return navigator.onLine ? fallback : "当前处于离线状态，恢复网络后重试。";
  if (error.status === 403) return "当前账号缺少 teaching:access，无法进入教师题库。";
  if (error.status === 429) return "请求过于频繁，请按服务端限流提示稍后重试。";
  if (error.status >= 500) return "题库服务暂时不可用，已保留当前筛选条件。";
  return error.message;
}
const typeLabel = (type: QuestionType) => ({ SINGLE_CHOICE: "单选", MULTIPLE_CHOICE: "多选", TRUE_FALSE: "判断",
  FILL_BLANK: "填空", SHORT_ANSWER: "简答", COMPOSITE: "组合" })[type];
const difficultyLabel = (value: QuestionDifficulty) => ({ EASY: "简单", MEDIUM: "中等", HARD: "困难" })[value];
const statusLabel = (value: QuestionStatus) => ({ DRAFT: "草稿", ACTIVE: "已启用", ARCHIVED: "已归档" })[value];
</script>

<template>
  <section class="teacher-page-heading">
    <div><span class="section-kicker">QUESTION BANK</span><h2>题库</h2><p>管理本人题目、不可变版本和受控导入任务。</p></div>
    <div class="heading-actions"><RouterLink class="button button--quiet" :to="{ name: 'teacher-question-imports' }">导入任务</RouterLink>
      <RouterLink class="button button--primary" :to="{ name: 'teacher-question-new' }">＋ 新建题目</RouterLink></div>
  </section>
  <form class="question-filter-panel" @submit.prevent="applyFilters">
    <label><span>关键词</span><input v-model="form.keyword" placeholder="搜索题干摘要"></label>
    <label><span>知识点</span><select v-model="form.knowledgePointId"><option value="">全部</option>
      <option v-for="point in knowledgePoints" :key="point.id" :value="point.id">{{ point.name }}</option></select></label>
    <label><span>题型</span><select v-model="form.type"><option value="">全部</option><option value="SINGLE_CHOICE">单选</option>
      <option value="MULTIPLE_CHOICE">多选</option><option value="TRUE_FALSE">判断</option><option value="FILL_BLANK">填空</option>
      <option value="SHORT_ANSWER">简答</option><option value="COMPOSITE">组合</option></select></label>
    <label><span>难度</span><select v-model="form.difficulty"><option value="">全部</option><option value="EASY">简单</option>
      <option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
    <label><span>状态</span><select v-model="form.status"><option value="">全部</option><option value="DRAFT">草稿</option>
      <option value="ACTIVE">已启用</option><option value="ARCHIVED">已归档</option></select></label>
    <div><button class="button button--primary" type="submit">应用筛选</button><button class="button button--quiet" type="button" @click="resetFilters">重置</button></div>
  </form>

  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }} <button type="button" @click="load">重试</button></p>
  <div v-if="loading && !result" class="teacher-loading" aria-busy="true">正在加载本人题目…</div>
  <div v-else-if="result?.items.length" class="question-list" :aria-busy="loading">
    <article v-for="question in result.items" :key="question.id" class="question-list-card">
      <header><div><span :class="`status-pill status-pill--${question.status.toLowerCase()}`">{{ statusLabel(question.status) }}</span>
        <span>{{ typeLabel(question.type) }} · {{ difficultyLabel(question.difficulty) }} · {{ question.grade }}</span></div>
        <span>v{{ question.versionNumber }} · r{{ question.revision }}</span></header>
      <h3>{{ question.stemPreview }}</h3>
      <p>{{ question.knowledgePointIds.length }} 个知识点 · 更新于 {{ new Date(question.updatedAt).toLocaleString("zh-CN") }}</p>
      <footer><RouterLink class="button button--quiet" :to="{ name: 'teacher-question-detail', params: { questionId: question.id } }">查看详情</RouterLink>
        <RouterLink v-if="question.status !== 'ARCHIVED'" class="button button--primary" :to="{ name: 'teacher-question-edit', params: { questionId: question.id } }">编辑新版本</RouterLink></footer>
    </article>
  </div>
  <div v-else-if="result" class="teacher-empty"><span>没有匹配题目</span><h3>调整筛选或创建第一道题</h3>
    <p>题目列表不会展示完整答案与解析。</p><RouterLink class="button button--primary" :to="{ name: 'teacher-question-new' }">新建题目</RouterLink></div>
  <nav v-if="result && result.total > pageSize" class="pagination" aria-label="题库分页">
    <button type="button" :disabled="currentPage() <= 1" @click="changePage(currentPage() - 1)">上一页</button>
    <span>第 {{ currentPage() }} / {{ pageCount }} 页</span>
    <button type="button" :disabled="currentPage() >= pageCount" @click="changePage(currentPage() + 1)">下一页</button>
  </nav>
</template>
