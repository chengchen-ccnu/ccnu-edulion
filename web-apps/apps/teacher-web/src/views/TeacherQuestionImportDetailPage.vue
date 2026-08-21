<script setup lang="ts">
/**
 * 教师导入任务详情页；CSV/XLSX 任务可查看处理结果，既有历史 JSON 任务仅保留只读审计投影。
 * 历史 JSON 的复核、清理和正式 SYSTEM 导入统一由管理员受控工具执行。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { LegacyQuestionImportItemPage, LegacyQuestionImportReviewItem, QuestionImportItemOutcome, QuestionImportTask } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { teacherQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const pageSize = 20;
const task = ref<QuestionImportTask | null>(null);
const items = ref<LegacyQuestionImportItemPage | null>(null);
const selectedItem = ref<LegacyQuestionImportReviewItem | null>(null);
const loading = ref(true);
const itemsLoading = ref(false);
const errorMessage = ref("");
const itemsError = ref("");
let controller = new AbortController();
let pollTimer: ReturnType<typeof setTimeout> | null = null;
const itemPageCount = computed(() => Math.max(1, Math.ceil((items.value?.total ?? 0) / pageSize)));

watch(() => route.fullPath, load, { immediate: true });
onBeforeUnmount(dispose);

async function load() {
  clearPoll();
  controller.abort();
  controller = new AbortController();
  loading.value = !task.value;
  errorMessage.value = "";
  itemsError.value = "";
  try {
    task.value = await teacherQuestionApi.importDetail(taskId(), controller.signal);
    if (task.value.sourceFormat === "LEGACY_JSON") {
      await Promise.all([loadItems(), loadSelectedItem()]);
    } else { items.value = null; selectedItem.value = null; }
    schedulePoll();
  } catch (error) { if (!controller.signal.aborted) errorMessage.value = messageFor(error, "导入任务详情加载失败。"); }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
async function loadItems() {
  itemsLoading.value = true;
  try {
    items.value = await teacherQuestionApi.legacyItems(taskId(), { page: itemPage(), size: pageSize,
      outcome: selectedOutcome() || undefined }, controller.signal);
  } catch (error) { if (!controller.signal.aborted) itemsError.value = messageFor(error, "逐项结果加载失败，可单独重试。"); }
  finally { if (!controller.signal.aborted) itemsLoading.value = false; }
}
async function loadSelectedItem() {
  if (!selectedItemId()) { selectedItem.value = null; return; }
  try {
    selectedItem.value = await teacherQuestionApi.legacyItem(taskId(), selectedItemId(), controller.signal);
  } catch (error) { errorMessage.value = messageFor(error, "复核项加载失败。"); }
}
function schedulePoll() {
  if (task.value && ["PENDING", "PROCESSING"].includes(task.value.status)) {
    pollTimer = setTimeout(() => { void load(); }, 3_000);
  }
}
function clearPoll() { if (pollTimer) clearTimeout(pollTimer); pollTimer = null; }
function dispose() { clearPoll(); controller.abort(); }

async function setOutcome(outcome: string) {
  await router.push({ name: "teacher-question-import-detail", params: { importTaskId: taskId() },
    query: { ...route.query, outcome: outcome || undefined, itemPage: undefined, itemId: undefined } });
}
async function changeItemPage(page: number) {
  if (page < 1 || page > itemPageCount.value || page === itemPage()) return;
  await router.push({ name: "teacher-question-import-detail", params: { importTaskId: taskId() },
    query: { ...route.query, itemPage: page === 1 ? undefined : String(page), itemId: undefined } });
}
async function chooseItem(itemId?: string) {
  await router.push({ name: "teacher-question-import-detail", params: { importTaskId: taskId() },
    query: { ...route.query, itemId: itemId || undefined } });
}

function taskId(): string { return String(route.params.importTaskId ?? ""); }
function selectedOutcome(): QuestionImportItemOutcome | "" {
  return typeof route.query.outcome === "string" ? route.query.outcome as QuestionImportItemOutcome : "";
}
function selectedItemId(): string { return typeof route.query.itemId === "string" ? route.query.itemId : ""; }
function itemPage(): number {
  const value = typeof route.query.itemPage === "string" ? Number(route.query.itemPage) : 1;
  return Number.isInteger(value) && value > 0 ? value : 1;
}
function messageFor(error: unknown, fallback: string): string {
  if (!(error instanceof GatewayRequestError)) return navigator.onLine ? fallback : "当前离线，恢复网络后重试。";
  if (error.status === 404) return "导入任务或复核项不存在或不可见。";
  if (error.status === 409 && error.code === "QUESTION_IMPORT.TASK_NOT_REVIEWABLE") return "任务尚未处理最终批次或未进入可复核终态，禁止提前解决 NEEDS_REVIEW。";
  if (error.status === 409) return "复核项 revision、终态决定或幂等请求发生冲突；当前修正已保留，请刷新后重试。";
  if (error.status === 422) return "结构化修正存在字段错误，请按字段提示修正。";
  if (error.status === 429) return "请求过于频繁，请稍后重试，不会无限自动重放。";
  if (error.status >= 500) return "导入服务暂时不可用，当前页面状态已保留。";
  return error.message;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-question-imports' }">← 返回导入任务</RouterLink>
  <div v-if="loading && !task" class="teacher-loading" aria-busy="true">正在恢复导入任务…</div>
  <template v-else-if="task">
    <section class="teacher-page-heading"><div><span class="section-kicker">IMPORT DETAIL</span><h2>{{ task.sourceFormat }} 导入详情</h2>
      <p>{{ task.status }} · {{ task.dryRun ? "dry-run" : "实际导入" }} · 更新于 {{ new Date(task.updatedAt).toLocaleString("zh-CN") }}</p></div>
      <button class="button button--quiet" type="button" @click="load">刷新任务</button></section>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <section class="import-summary"><progress :value="task.progress" max="100" :aria-label="`任务进度 ${task.progress}%`" />
      <dl><div><dt>处理</dt><dd>{{ task.processedRows }} / {{ task.totalRows || task.maxItems || 0 }}</dd></div><div><dt>成功</dt><dd>{{ task.successCount }}</dd></div>
        <div><dt>待复核</dt><dd>{{ task.reviewCount }}</dd></div><div><dt>已解决</dt><dd>{{ task.resolvedCount }}</dd></div>
        <div><dt>跳过</dt><dd>{{ task.skippedCount }}</dd></div><div><dt>失败</dt><dd>{{ task.failureCount }}</dd></div></dl>
      <p v-if="task.failureMessage" class="feedback feedback--error">{{ task.failureCode }}：{{ task.failureMessage }}</p></section>
    <section v-if="task.errors.length" class="row-error-list"><h3>行错误</h3><ol><li v-for="error in task.errors" :key="`${error.rowNumber}-${error.code}`">
      <strong>第 {{ error.rowNumber }} 行 · {{ error.code }}</strong><span>{{ error.message }}</span></li></ol></section>

    <template v-if="task.sourceFormat === 'LEGACY_JSON'">
      <div class="legacy-review-layout">
        <section class="legacy-item-list"><header><div><h3>逐项结果</h3><small>刷新后从服务端安全投影恢复</small></div>
          <select :value="selectedOutcome()" aria-label="结果筛选" @change="setOutcome(($event.target as HTMLSelectElement).value)">
            <option value="">全部结果</option><option value="IMPORTED">IMPORTED</option><option value="VALID">VALID</option>
            <option value="NEEDS_REVIEW">NEEDS_REVIEW</option><option value="SKIPPED">SKIPPED</option><option value="FAILED">FAILED</option></select></header>
          <p v-if="itemsError" class="feedback feedback--error" role="alert">{{ itemsError }} <button type="button" @click="loadItems">重试</button></p>
          <div v-if="itemsLoading && !items" class="teacher-loading">正在加载逐项结果…</div>
          <ol v-else-if="items?.items.length"><li v-for="item in items.items" :key="item.id"><button type="button" :class="{ active: item.id === selectedItemId() }" @click="chooseItem(item.id)">
            <strong>{{ item.externalId }}</strong><span>{{ item.outcome }} · r{{ item.revision }}</span><small>{{ item.issues.map(issue => issue.code).join("、") || "无问题码" }}</small></button></li></ol>
          <div v-else class="teacher-empty"><p>当前筛选没有逐项结果。</p></div>
          <nav v-if="items && items.total > pageSize" class="pagination" aria-label="逐项结果分页"><button type="button" :disabled="itemPage() <= 1" @click="changeItemPage(itemPage() - 1)">上一页</button>
            <span>{{ itemPage() }} / {{ itemPageCount }}</span><button type="button" :disabled="itemPage() >= itemPageCount" @click="changeItemPage(itemPage() + 1)">下一页</button></nav>
        </section>
        <aside class="legacy-review-panel">
          <template v-if="selectedItem"><header><div><h3>历史审计详情</h3><small>{{ selectedItem.externalId }} · r{{ selectedItem.revision }}</small></div><button type="button" @click="chooseItem()">关闭</button></header>
            <dl class="review-metadata"><div><dt>结果</dt><dd>{{ selectedItem.outcome }}</dd></div><div><dt>来源</dt><dd>{{ selectedItem.sourceSystem }}</dd></div>
              <div><dt>版次</dt><dd>{{ selectedItem.pathEdition || "-" }} / {{ selectedItem.jsonEdition || "-" }}</dd></div>
              <div><dt>年级</dt><dd>{{ selectedItem.pathGrade || "-" }} / {{ selectedItem.jsonGrade || "-" }}</dd></div></dl>
            <ul class="review-issues"><li v-for="issue in selectedItem.issues" :key="issue.code"><strong>{{ issue.code }}</strong><span>{{ issue.message }}</span></li></ul>
            <p v-if="selectedItem.resolution" class="feedback feedback--success">历史终态决定：{{ selectedItem.resolution.action }}</p>
            <p v-else-if="selectedItem.outcome === 'NEEDS_REVIEW'" class="feedback feedback--warning">此旧任务仅供审计；历史 JSON 复核与 SYSTEM 导入已转由管理员处理。</p>
          </template>
          <div v-else class="teacher-empty"><p>从左侧选择一项查看稳定问题码和复核状态。</p></div>
        </aside>
      </div>
      <section class="cleanup-panel"><h3>历史任务只读</h3><p>教师端不能复核、继续提交或清理历史 JSON 任务；公共系统题由管理员统一管理。</p></section>
    </template>
  </template>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
