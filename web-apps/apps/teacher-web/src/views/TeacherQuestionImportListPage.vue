<script setup lang="ts">
/**
 * 教师导入任务聚合页；只允许把 CSV/XLSX 导入本人 TEACHER 私有题库。
 * 历史 JSON 改由管理员受控导入 SYSTEM 题库，教师端仅保留既有任务的只读查看能力。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { QuestionImportPage, QuestionImportSourceFormat } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import MediaUploadField from "../components/MediaUploadField.vue";
import { teacherQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const pageSize = 20;
const result = ref<QuestionImportPage | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const sourceMediaId = ref<string | null>(null);
const sourceFormat = ref<QuestionImportSourceFormat>("CSV");
const sourceBusy = ref(false);
const creating = ref(false);
let controller = new AbortController();
const pageCount = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / pageSize)));

watch(() => route.fullPath, load, { immediate: true });
onBeforeUnmount(() => controller.abort());

async function load() {
  controller.abort();
  controller = new AbortController();
  loading.value = true;
  errorMessage.value = "";
  try { result.value = await teacherQuestionApi.imports({ page: currentPage(), size: pageSize }, controller.signal); }
  catch (error) { if (!controller.signal.aborted) errorMessage.value = messageFor(error, "导入任务加载失败。"); }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
async function createStructuredImport() {
  if (!sourceMediaId.value || sourceBusy.value) return;
  creating.value = true;
  errorMessage.value = "";
  try {
    const task = await teacherQuestionApi.createImport({ clientRequestId: crypto.randomUUID(),
      sourceMediaId: sourceMediaId.value, sourceFormat: sourceFormat.value }, controller.signal);
    await router.push({ name: "teacher-question-import-detail", params: { importTaskId: task.id } });
  } catch (error) { errorMessage.value = messageFor(error, "导入任务创建失败。"); }
  finally { creating.value = false; }
}
async function changePage(page: number) {
  if (page < 1 || page > pageCount.value || page === currentPage()) return;
  await router.push({ name: "teacher-question-imports", query: page === 1 ? {} : { page: String(page) } });
}
function currentPage(): number {
  const value = typeof route.query.page === "string" ? Number(route.query.page) : 1;
  return Number.isInteger(value) && value > 0 ? value : 1;
}
function formatLabel(task: QuestionImportPage["items"][number]): string { return String(task.sourceFormat); }
function messageFor(error: unknown, fallback: string): string {
  if (!(error instanceof GatewayRequestError)) return navigator.onLine ? fallback : "当前离线，恢复网络后重试。";
  if (error.status === 404) return "导入任务不存在或不可见。";
  if (error.status === 409) return "导入任务状态或幂等请求发生冲突，请刷新后重试。";
  if (error.status === 422) return "导入参数不符合契约，请检查文件格式和字段。";
  if (error.status === 429) return "请求过于频繁，请稍后继续，页面不会放大批次或自动无限重试。";
  if (error.status >= 500) return "导入服务暂时不可用，请稍后重试。";
  return error.message;
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-questions' }">← 返回题库</RouterLink>
  <section class="teacher-page-heading"><div><span class="section-kicker">QUESTION IMPORTS</span><h2>导入任务</h2>
    <p>统一查看 CSV、XLSX 与 LEGACY_JSON 状态、进度和计数。</p></div></section>
  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <div class="import-create-grid">
    <section class="import-create-card"><header><span>CSV / XLSX</span><h3>上传规范化导入源</h3></header>
      <label class="editor-field"><span>文件格式</span><select v-model="sourceFormat"><option value="CSV">CSV</option><option value="XLSX">XLSX</option></select></label>
      <MediaUploadField v-model="sourceMediaId" purpose="QUESTION_IMPORT_SOURCE" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        label="导入源文件" checkpoint-key="teacher-question-import-source" @busy-change="sourceBusy = $event" />
      <button class="button button--primary" type="button" :disabled="!sourceMediaId || sourceBusy || creating" @click="createStructuredImport">创建异步导入任务</button>
    </section>
    <section class="import-create-card"><header><span>SYSTEM LIBRARY</span><h3>历史 JSON 由管理员导入</h3></header>
      <p class="scope-note">管理员完成校验后将历史题与图片导入公共 SYSTEM 题库。教师可查看和选用公共题，但不能改写或清理系统题。</p>
    </section>
  </div>

  <div v-if="loading && !result" class="teacher-loading" aria-busy="true">正在加载导入任务…</div>
  <section v-else-if="result?.items.length" class="import-task-list"><h3>历史任务</h3>
    <RouterLink v-for="task in result.items" :key="task.id" class="import-task-card"
      :to="{ name: 'teacher-question-import-detail', params: { importTaskId: task.id } }">
      <header><strong>{{ formatLabel(task) }}</strong><span :class="`status-pill status-pill--${task.status.toLowerCase()}`">{{ task.status }}</span></header>
      <progress :value="task.progress" max="100" :aria-label="`任务进度 ${task.progress}%`" />
      <dl><div><dt>处理</dt><dd>{{ task.processedRows }} / {{ task.totalRows || task.maxItems || 0 }}</dd></div>
        <div><dt>成功</dt><dd>{{ task.successCount }}</dd></div><div><dt>待复核</dt><dd>{{ task.reviewCount }}</dd></div>
        <div><dt>已解决</dt><dd>{{ task.resolvedCount }}</dd></div><div><dt>失败</dt><dd>{{ task.failureCount }}</dd></div></dl>
    </RouterLink>
  </section>
  <div v-else-if="result" class="teacher-empty"><span>暂无导入任务</span><h3>导入本人私有题</h3><p>上传真实契约支持的 CSV/XLSX；历史 JSON 公共题由管理员统一导入。</p></div>
  <nav v-if="result && result.total > pageSize" class="pagination" aria-label="导入任务分页"><button type="button" :disabled="currentPage() <= 1" @click="changePage(currentPage() - 1)">上一页</button>
    <span>{{ currentPage() }} / {{ pageCount }}</span><button type="button" :disabled="currentPage() >= pageCount" @click="changePage(currentPage() + 1)">下一页</button></nav>
</template>
