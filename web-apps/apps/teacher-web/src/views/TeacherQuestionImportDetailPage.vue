<script setup lang="ts">
/**
 * 导入任务详情与历史 JSON 人工复核页；任务、分页、outcome 和 itemId 均从 URL/服务端恢复。
 * 仅可复核终态允许解决 NEEDS_REVIEW；409 保留结构化修正，清理只调用当前任务的精确 cleanup 接口。
 */
import { GatewayRequestError } from "@edu/api-client";
import type {
  KnowledgePoint,
  LegacyQuestionImportItemPage,
  LegacyQuestionImportReviewItem,
  QuestionImportItemOutcome,
  QuestionImportTask,
  QuestionVersionInput
} from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import QuestionFields from "../components/QuestionFields.vue";
import { emptyQuestionVersion, flattenKnowledgePoints, normalizeQuestionVersion } from "../question-form";
import { teacherQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const pageSize = 20;
const task = ref<QuestionImportTask | null>(null);
const items = ref<LegacyQuestionImportItemPage | null>(null);
const selectedItem = ref<LegacyQuestionImportReviewItem | null>(null);
const knowledgePoints = ref<KnowledgePoint[]>([]);
const correction = ref<QuestionVersionInput>(emptyQuestionVersion());
const loading = ref(true);
const itemsLoading = ref(false);
const resolving = ref(false);
const cleanupBusy = ref(false);
const cleanupAcknowledged = ref(false);
const errorMessage = ref("");
const itemsError = ref("");
const successMessage = ref("");
const fieldErrors = ref<Record<string, string>>({});
const mediaBusy = ref(false);
let controller = new AbortController();
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let resolutionRequestId = crypto.randomUUID();
const reviewable = computed(() => task.value?.status === "COMPLETED" || task.value?.status === "PARTIAL_SUCCESS");
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
      await Promise.all([loadItems(), loadSelectedItem(), loadKnowledgePoints()]);
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
    correction.value = emptyQuestionVersion();
    fieldErrors.value = {};
    resolutionRequestId = crypto.randomUUID();
  } catch (error) { errorMessage.value = messageFor(error, "复核项加载失败。"); }
}
async function loadKnowledgePoints() {
  if (!knowledgePoints.value.length) knowledgePoints.value = flattenKnowledgePoints(
    await teacherQuestionApi.knowledgePoints(controller.signal));
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
async function importCorrection() {
  if (!selectedItem.value || !reviewable.value || mediaBusy.value) return;
  await resolve("IMPORT_CORRECTED", normalizeQuestionVersion(correction.value));
}
async function confirmSkip() {
  if (!selectedItem.value || !reviewable.value || !window.confirm("确认跳过此项？该决定是终态，之后不能再导入修正。")) return;
  await resolve("CONFIRM_SKIP", null);
}
async function resolve(action: "IMPORT_CORRECTED" | "CONFIRM_SKIP", correctedVersion: QuestionVersionInput | null) {
  resolving.value = true;
  errorMessage.value = "";
  fieldErrors.value = {};
  try {
    selectedItem.value = await teacherQuestionApi.resolveLegacyItem(taskId(), selectedItem.value!.id, {
      clientRequestId: resolutionRequestId, expectedRevision: selectedItem.value!.revision, action, correctedVersion
    }, controller.signal);
    successMessage.value = action === "IMPORT_CORRECTED" ? "结构化修正已导入。" : "已确认跳过该项。";
    await load();
  } catch (error) {
    if (error instanceof GatewayRequestError) fieldErrors.value = error.fieldErrors;
    errorMessage.value = messageFor(error, "复核解决失败，当前结构化修正已保留。");
  } finally { resolving.value = false; }
}
async function cleanup() {
  if (!task.value || !cleanupAcknowledged.value) return;
  const impact = `任务 ${task.value.id}：最多涉及本任务创建的 ${task.value.successCount} 道 DRAFT 题、来源引用和媒体引用。不会清理其他任务或目录。`;
  if (!window.confirm(`${impact}\n\n确认调用任务级精确清理接口？`)) return;
  cleanupBusy.value = true;
  errorMessage.value = "";
  try {
    const cleaned = await teacherQuestionApi.cleanupLegacyImport(taskId(), controller.signal);
    successMessage.value = `精确清理完成：删除题目 ${cleaned.questionsDeleted}，删除来源引用 ${cleaned.sourceReferencesDeleted}，释放媒体引用 ${cleaned.mediaReferencesReleased}。`;
    cleanupAcknowledged.value = false;
    await load();
  } catch (error) { errorMessage.value = messageFor(error, "任务精确清理失败。"); }
  finally { cleanupBusy.value = false; }
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
    <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>
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
          <template v-if="selectedItem"><header><div><h3>人工复核</h3><small>{{ selectedItem.externalId }} · r{{ selectedItem.revision }}</small></div><button type="button" @click="chooseItem()">关闭</button></header>
            <dl class="review-metadata"><div><dt>结果</dt><dd>{{ selectedItem.outcome }}</dd></div><div><dt>来源</dt><dd>{{ selectedItem.sourceSystem }}</dd></div>
              <div><dt>版次</dt><dd>{{ selectedItem.pathEdition || "-" }} / {{ selectedItem.jsonEdition || "-" }}</dd></div>
              <div><dt>年级</dt><dd>{{ selectedItem.pathGrade || "-" }} / {{ selectedItem.jsonGrade || "-" }}</dd></div></dl>
            <ul class="review-issues"><li v-for="issue in selectedItem.issues" :key="issue.code"><strong>{{ issue.code }}</strong><span>{{ issue.message }}</span></li></ul>
            <p v-if="!reviewable" class="feedback feedback--warning">任务尚未处理最终批次并进入 COMPLETED/PARTIAL_SUCCESS，禁止解决 NEEDS_REVIEW。</p>
            <p v-else-if="selectedItem.resolution" class="feedback feedback--success">已终态解决：{{ selectedItem.resolution.action }}</p>
            <form v-else-if="selectedItem.outcome === 'NEEDS_REVIEW'" @submit.prevent="importCorrection">
              <p class="scope-note">服务端不保存原题干或原始 JSON。请参考本地重新选择的来源，显式填写完整结构化修正；页面不会启发式自动放行。</p>
              <QuestionFields v-model="correction" :knowledge-points="knowledgePoints" :field-errors="fieldErrors" field-prefix="correctedVersion"
                :checkpoint-prefix="`legacy-review:${task.id}:${selectedItem.id}`" allow-composite @busy-change="mediaBusy = $event" />
              <div class="editor-actions"><button class="button button--danger" type="button" :disabled="!reviewable || resolving" @click="confirmSkip">确认跳过</button>
                <button class="button button--primary" type="submit" :disabled="!reviewable || resolving || mediaBusy">提交结构化修正并导入</button></div>
            </form>
          </template>
          <div v-else class="teacher-empty"><p>从左侧选择一项查看稳定问题码和复核状态。</p></div>
        </aside>
      </div>
      <section class="cleanup-panel"><h3>任务级精确清理</h3><p>影响范围仅限本任务创建且仍为 DRAFT 的题目、来源引用和对应媒体引用。当前成功计数：{{ task.successCount }}。</p>
        <label><input v-model="cleanupAcknowledged" type="checkbox">我已核对任务 ID 和上述影响范围</label>
        <button class="button button--danger" type="button" :disabled="!cleanupAcknowledged || cleanupBusy || task.status === 'CLEANED'" @click="cleanup">二次确认并精确清理</button></section>
    </template>
  </template>
  <p v-else-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
</template>
