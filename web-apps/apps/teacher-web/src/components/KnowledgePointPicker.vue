<script setup lang="ts">
/**
 * T10B 分页知识点选择器；数据只来自 Gateway 的 OpenAPI 客户端。
 * 每次最多渲染 20 个候选，已选项独立保留；关系摘要只请求一层邻域并可安全失败。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { KnowledgePoint, KnowledgePointGraph, KnowledgePointSearchItem, QuestionStage } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";

import { teacherQuestionApi } from "../question-api";

const props = defineProps<{ modelValue: string[]; seed: KnowledgePoint[]; stage: QuestionStage }>();
const emit = defineEmits<{ "update:modelValue": [value: string[]] }>();
const items = ref<KnowledgePointSearchItem[]>([]);
const cache = ref<Record<string, { id: string; name: string; typeName: string | null }>>({});
const keyword = ref(""); const typeId = ref(""); const page = ref(1); const total = ref(0);
const loading = ref(false); const error = ref(""); const loaded = ref(false);
const graph = ref<KnowledgePointGraph | null>(null); const graphLoading = ref(false); const graphError = ref("");
let controller = new AbortController();

for (const point of props.seed) cache.value[point.id] = { id: point.id, name: point.name, typeName: null };
const selected = computed(() => props.modelValue.map(id => cache.value[id] ?? { id, name: "已选知识点", typeName: null }));
const pages = computed(() => Math.max(1, Math.ceil(total.value / 20)));
const typeOptions = computed(() => [...new Map(items.value.filter(item => item.knowledgeTypeName)
  .map(item => [item.knowledgeTypeId, item.knowledgeTypeName!])).entries()]);

async function load(targetPage = page.value) {
  controller.abort(); controller = new AbortController(); loading.value = true; error.value = ""; graph.value = null;
  try {
    const result = await teacherQuestionApi.searchKnowledgePoints({ normalizedStage: props.stage, page: targetPage,
      size: 20, keyword: keyword.value.trim() || undefined, knowledgeTypeId: typeId.value || undefined }, controller.signal);
    items.value = result.items; page.value = result.page; total.value = result.total; loaded.value = true;
    for (const item of result.items) cache.value[item.id] = { id: item.id, name: item.name, typeName: item.knowledgeTypeName };
  } catch (cause) { if (!controller.signal.aborted) error.value = message(cause); }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
watch(() => props.stage, () => {
  controller.abort(); controller = new AbortController();
  items.value = []; typeId.value = ""; page.value = 1; total.value = 0; loaded.value = false;
  graph.value = null; graphError.value = ""; error.value = ""; loading.value = false;
});
function toggle(id: string, checked: boolean) {
  emit("update:modelValue", checked ? [...new Set([...props.modelValue, id])] : props.modelValue.filter(value => value !== id));
}
async function showGraph(id: string) {
  graphLoading.value = true; graphError.value = "";
  try { graph.value = await teacherQuestionApi.knowledgePointGraph(id, 1, controller.signal); }
  catch (cause) { if (!controller.signal.aborted) graphError.value = message(cause); }
  finally { if (!controller.signal.aborted) graphLoading.value = false; }
}
function message(cause: unknown): string {
  if (!(cause instanceof GatewayRequestError)) return navigator.onLine ? "知识点服务暂时不可用，请稍后重试。" : "当前离线，已选知识点仍保留。";
  if (cause.status === 401) return "登录已失效，请重新登录后再搜索。";
  if (cause.status === 403) return "当前账号没有读取知识点的权限。";
  if (cause.status === 422) return "知识点筛选条件无效，请清空后重试。";
  return cause.status >= 500 ? "知识点服务暂时不可用，已选知识点仍保留。" : cause.message;
}
onBeforeUnmount(() => controller.abort());
</script>

<template>
  <details class="knowledge-picker" @toggle="!loaded && ($event.target as HTMLDetailsElement).open && load(1)">
    <summary>知识点（已选 {{ modelValue.length }}）</summary>
    <ul v-if="selected.length" class="knowledge-chips" aria-label="已选知识点">
      <li v-for="point in selected" :key="point.id"><span>{{ point.name }}</span>
        <small v-if="point.typeName">{{ point.typeName }}</small>
        <button type="button" :aria-label="`移除${point.name}`" @click="toggle(point.id, false)">×</button></li>
    </ul>
    <form class="knowledge-search" role="search" @submit.prevent="load(1)">
      <label><span>关键词</span><input v-model="keyword" maxlength="100" placeholder="输入知识点名称"></label>
      <label><span>知识点类型</span><select v-model="typeId" :disabled="!typeOptions.length">
        <option value="">全部类型</option><option v-for="option in typeOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option>
      </select><small v-if="loaded && !typeOptions.length">正式类型名称尚未维护</small></label>
      <button type="submit" :disabled="loading">搜索</button>
    </form>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }} <button type="button" @click="load(page)">重试</button></p>
    <p v-else-if="loading" aria-live="polite">正在加载知识点…</p>
    <p v-else-if="loaded && !items.length" class="empty-state">没有符合条件的已启用{{ stage === "PRIMARY" ? "小学" : "初中" }}知识点。</p>
    <ul v-else class="knowledge-results">
      <li v-for="point in items" :key="point.id">
        <label><input type="checkbox" :checked="modelValue.includes(point.id)" @change="toggle(point.id, ($event.target as HTMLInputElement).checked)">
          <span>{{ point.name }}<small>{{ point.knowledgeTypeName ?? "类型名称待维护" }}</small></span></label>
        <button type="button" @click="showGraph(point.id)">关系摘要</button>
      </li>
    </ul>
    <nav v-if="loaded && total" class="knowledge-pagination" aria-label="知识点分页">
      <button type="button" :disabled="page <= 1 || loading" @click="load(page - 1)">上一页</button>
      <span>第 {{ page }} / {{ pages }} 页，共 {{ total }} 个</span>
      <button type="button" :disabled="page >= pages || loading" @click="load(page + 1)">下一页</button>
    </nav>
    <p v-if="graphLoading" aria-live="polite">正在加载关系摘要…</p>
    <p v-else-if="graphError" class="feedback feedback--error" role="alert">{{ graphError }}</p>
    <aside v-else-if="graph" class="knowledge-graph-summary">
      <strong>{{ graph.center.name }}</strong>
      <span>直接先修 {{ graph.directPrerequisites.length }} 个 · 直接后续 {{ graph.directDependents.length }} 个</span>
    </aside>
  </details>
</template>
