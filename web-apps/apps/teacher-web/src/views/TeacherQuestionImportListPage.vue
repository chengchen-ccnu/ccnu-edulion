<script setup lang="ts">
/**
 * 教师导入任务聚合页；CSV/XLSX 先通过 QUESTION_IMPORT_SOURCE 直传，历史 JSON 只读取浏览器显式选择的文件。
 * 历史导入默认 dry-run，严格执行每批 20、每任务 1000，离开页面会中止读取、批次提交和上传轮询。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { QuestionImportPage, QuestionImportSourceFormat } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import MediaUploadField from "../components/MediaUploadField.vue";
import { LEGACY_TASK_LIMIT, legacyBatches, readLegacyFiles, rootFingerprint } from "../legacy-import";
import { teacherQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const pageSize = 20;
const result = ref<QuestionImportPage | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const successMessage = ref("");
const sourceMediaId = ref<string | null>(null);
const sourceFormat = ref<QuestionImportSourceFormat>("CSV");
const sourceBusy = ref(false);
const creating = ref(false);
const legacyFiles = ref<File[]>([]);
const legacyDryRun = ref(true);
const legacySourceSystem = ref("LEGACY_BROWSER_V1");
const legacyRootLabel = ref("browser-selection");
const legacyProgress = ref(0);
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
function chooseLegacyFiles(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? []).filter(file => file.name.toLowerCase().endsWith(".json"));
  legacyFiles.value = files.slice(0, LEGACY_TASK_LIMIT + 1);
  legacyProgress.value = 0;
  errorMessage.value = files.length > LEGACY_TASK_LIMIT ? `最多只能选择 ${LEGACY_TASK_LIMIT} 个 JSON 文件。` : "";
}
async function createLegacyImport() {
  if (!legacyFiles.value.length || legacyFiles.value.length > LEGACY_TASK_LIMIT) return;
  creating.value = true;
  errorMessage.value = "";
  legacyProgress.value = 0;
  try {
    const items = await readLegacyFiles(legacyFiles.value, controller.signal);
    const task = await teacherQuestionApi.createLegacyImport({
      clientRequestId: crypto.randomUUID(), sourceSystem: legacySourceSystem.value,
      sourceRootFingerprint: rootFingerprint(legacySourceSystem.value, legacyRootLabel.value),
      dryRun: legacyDryRun.value, maxItems: items.length
    }, controller.signal);
    const batches = legacyBatches(items);
    for (let index = 0; index < batches.length; index += 1) {
      await teacherQuestionApi.submitLegacyBatch(task.id, {
        batchId: crypto.randomUUID(), finalBatch: index === batches.length - 1, items: batches[index]
      }, controller.signal);
      legacyProgress.value = Math.round(((index + 1) / batches.length) * 100);
    }
    await router.push({ name: "teacher-question-import-detail", params: { importTaskId: task.id } });
  } catch (error) { if (!controller.signal.aborted) errorMessage.value = legacyMessage(error); }
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
function legacyMessage(error: unknown): string {
  if (error instanceof SyntaxError) return "所选文件包含无法解析的 JSON；任务未继续提交。";
  if (error instanceof Error && error.message === "LEGACY_TASK_LIMIT") return "每个历史导入任务最多 1000 项。";
  if (error instanceof Error && error.message.startsWith("LEGACY_FILE_TOO_LARGE")) return "单个 JSON 超过 256 KiB 有界读取上限。";
  if (error instanceof Error && error.message.startsWith("LEGACY_JSON_OBJECT_REQUIRED")) return "历史 JSON 必须是单题对象，不能是数组或空值。";
  return messageFor(error, "历史 JSON 导入失败；已停止后续批次，不会扫描其他文件。");
}
</script>

<template>
  <RouterLink class="back-link" :to="{ name: 'teacher-questions' }">← 返回题库</RouterLink>
  <section class="teacher-page-heading"><div><span class="section-kicker">QUESTION IMPORTS</span><h2>导入任务</h2>
    <p>统一查看 CSV、XLSX 与 LEGACY_JSON 状态、进度和计数。</p></div></section>
  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <p v-if="successMessage" class="feedback feedback--success" role="status">{{ successMessage }}</p>

  <div class="import-create-grid">
    <section class="import-create-card"><header><span>CSV / XLSX</span><h3>上传规范化导入源</h3></header>
      <label class="editor-field"><span>文件格式</span><select v-model="sourceFormat"><option value="CSV">CSV</option><option value="XLSX">XLSX</option></select></label>
      <MediaUploadField v-model="sourceMediaId" purpose="QUESTION_IMPORT_SOURCE" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        label="导入源文件" checkpoint-key="teacher-question-import-source" @busy-change="sourceBusy = $event" />
      <button class="button button--primary" type="button" :disabled="!sourceMediaId || sourceBusy || creating" @click="createStructuredImport">创建异步导入任务</button>
    </section>
    <section class="import-create-card"><header><span>LEGACY JSON</span><h3>浏览器显式选择并有界提交</h3></header>
      <p class="scope-note">默认 dry-run；每批最多 20、每任务最多 1000。不会提交绝对路径，也不会扫描 44,441 份历史题库。</p>
      <div class="legacy-source-fields"><label class="editor-field"><span>来源命名空间</span><input v-model="legacySourceSystem" pattern="[A-Z0-9][A-Z0-9_-]{2,79}"></label>
        <label class="editor-field"><span>本次选择标签（非路径）</span><input v-model="legacyRootLabel" maxlength="80"></label></div>
      <label class="legacy-dry-run"><input v-model="legacyDryRun" type="checkbox"><span>dry-run（推荐，默认开启）</span></label>
      <div class="legacy-file-actions"><label class="button button--quiet">选择 JSON 文件<input class="visually-hidden" type="file" accept=".json,application/json" multiple @change="chooseLegacyFiles"></label>
        <label class="button button--quiet">选择目录<input class="visually-hidden" type="file" accept=".json,application/json" multiple webkitdirectory @change="chooseLegacyFiles"></label></div>
      <p>{{ legacyFiles.length ? `已显式选择 ${legacyFiles.length} 个 JSON 文件` : "尚未选择文件或目录" }}</p>
      <progress v-if="creating && legacyProgress" :value="legacyProgress" max="100" aria-label="历史 JSON 批次提交进度" />
      <button class="button button--primary" type="button" :disabled="!legacyFiles.length || legacyFiles.length > 1000 || creating" @click="createLegacyImport">
        {{ creating ? `正在有界提交 ${legacyProgress}%` : legacyDryRun ? "创建 dry-run 任务" : "创建受控实际导入任务" }}
      </button>
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
  <div v-else-if="result" class="teacher-empty"><span>暂无导入任务</span><h3>从最小合成文件开始验证</h3><p>上传真实契约支持的 CSV/XLSX，或显式选择少量历史 JSON。</p></div>
  <nav v-if="result && result.total > pageSize" class="pagination" aria-label="导入任务分页"><button type="button" :disabled="currentPage() <= 1" @click="changePage(currentPage() - 1)">上一页</button>
    <span>{{ currentPage() }} / {{ pageCount }}</span><button type="button" :disabled="currentPage() >= pageCount" @click="changePage(currentPage() + 1)">下一页</button></nav>
</template>
