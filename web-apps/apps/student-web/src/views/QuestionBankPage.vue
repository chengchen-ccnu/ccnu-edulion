<script setup lang="ts">
/**
 * 顶部公共题库：只消费 ACTIVE SYSTEM 安全摘要，并用 URL 恢复分页和筛选。
 * 请求失败时保留上一页形成 partial-error；本页不枚举课程测评，也不伪造单题提交或评分能力。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { ListSystemQuestionsQuery, QuestionDifficulty, QuestionGrade, QuestionStage, QuestionType,
  SystemQuestionPage } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentQuestionApi } from "../question-api";

const route = useRoute();
const router = useRouter();
const data = ref<SystemQuestionPage | null>(null);
const loading = ref(false);
const errorMessage = ref("");
let controller: AbortController | null = null;

const page = computed(() => positive(route.query.page, 1));
const stage = computed(() => oneOf(route.query.stage, ["PRIMARY", "JUNIOR"] as const));
const grade = computed(() => oneOf(route.query.grade,
  ["ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE"] as const));
const type = computed(() => oneOf(route.query.type,
  ["SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER", "COMPOSITE"] as const));
const difficulty = computed(() => oneOf(route.query.difficulty, ["EASY", "MEDIUM", "HARD"] as const));
const primaryGrades = ["ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX"] as const;
const juniorGrades = ["SEVEN", "EIGHT", "NINE"] as const;

watch(() => route.query, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load() {
  controller?.abort();
  const active = new AbortController();
  controller = active;
  loading.value = true;
  errorMessage.value = "";
  const query: ListSystemQuestionsQuery = { page: page.value, size: 12 };
  if (stage.value) query.stage = stage.value;
  if (grade.value) query.grade = grade.value;
  if (type.value) query.type = type.value;
  if (difficulty.value) query.difficulty = difficulty.value;
  try {
    const result = await studentQuestionApi.list(query, active.signal);
    if (!active.signal.aborted) data.value = result;
  } catch (error) {
    if (!active.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!active.signal.aborted) loading.value = false;
  }
}

/** 筛选只写 URL；切换学段会移除不属于该学段的年级，刷新后由同一查询恢复。 */
function setFilter(name: string, value: string) {
  const query: Record<string, string | string[] | null | undefined> = { ...route.query, page: "1" };
  if (value) query[name] = value; else delete query[name];
  if (name === "stage" && value && !gradeBelongsToStage(String(query.grade ?? ""), value)) delete query.grade;
  void router.replace({ query });
}

function setPage(next: number) { void router.replace({ query: { ...route.query, page: String(next) } }); }
function clearFilters() { void router.replace({ query: {} }); }

function oneOf<const T extends readonly string[]>(value: unknown, values: T): T[number] | undefined {
  return typeof value === "string" && values.includes(value) ? value as T[number] : undefined;
}
function positive(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
function gradeBelongsToStage(value: string, selectedStage: string) {
  if (!value) return true;
  return selectedStage === "PRIMARY" ? primaryGrades.includes(value as typeof primaryGrades[number])
    : juniorGrades.includes(value as typeof juniorGrades[number]);
}
function gradeLabel(value: QuestionGrade) {
  return ({ ONE: "一年级", TWO: "二年级", THREE: "三年级", FOUR: "四年级", FIVE: "五年级", SIX: "六年级",
    SEVEN: "七年级", EIGHT: "八年级", NINE: "九年级" } as const)[value];
}
const typeLabels: Record<QuestionType, string> = {
  SINGLE_CHOICE: "单选题", MULTIPLE_CHOICE: "多选题", TRUE_FALSE: "判断题", FILL_BLANK: "填空题",
  SHORT_ANSWER: "简答题", COMPOSITE: "组合题"
};
const difficultyLabels: Record<QuestionDifficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };
const stageLabels: Record<QuestionStage, string> = { PRIMARY: "小学", JUNIOR: "初中" };

function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线；已有题目仍保留，恢复网络后可重试。";
  if (!(error instanceof GatewayRequestError)) return "公共题库暂时无法读取。";
  const labels: Record<number, string> = {
    401: "登录状态已失效，请重新登录。", 403: "当前账号没有学习空间访问能力。",
    422: error.message, 429: "请求过于频繁，请稍后重试。", 503: "学习服务暂时不可用。"
  };
  return labels[error.status] ?? error.message;
}
</script>

<template>
  <main class="practice-page question-bank-page">
    <header class="practice-heading"><div><span class="section-kicker">PUBLIC QUESTION BANK</span><h2>题库</h2>
      <p>浏览平台公开数学题；题目与课程无关，答案和解析不会随浏览响应返回。</p></div>
      <RouterLink class="button button--quiet" to="/wrong-questions">错题本</RouterLink></header>
    <p class="question-bank-scope">当前可以浏览题目。在线作答与自主组题尚未开放。</p>
    <form class="practice-filters" aria-label="公共题筛选" @submit.prevent>
      <label>学段<select :value="stage ?? ''" @change="setFilter('stage', ($event.target as HTMLSelectElement).value)">
        <option value="">全部</option><option value="PRIMARY">小学</option><option value="JUNIOR">初中</option>
      </select></label>
      <label>年级<select :value="grade ?? ''" @change="setFilter('grade', ($event.target as HTMLSelectElement).value)">
        <option value="">全部</option>
        <optgroup v-if="stage !== 'JUNIOR'" label="小学"><option v-for="value in primaryGrades" :key="value" :value="value">{{ gradeLabel(value) }}</option></optgroup>
        <optgroup v-if="stage !== 'PRIMARY'" label="初中"><option v-for="value in juniorGrades" :key="value" :value="value">{{ gradeLabel(value) }}</option></optgroup>
      </select></label>
      <label>题型<select :value="type ?? ''" @change="setFilter('type', ($event.target as HTMLSelectElement).value)">
        <option value="">全部</option><option v-for="(label, value) in typeLabels" :key="value" :value="value">{{ label }}</option>
      </select></label>
      <label>难度<select :value="difficulty ?? ''" @change="setFilter('difficulty', ($event.target as HTMLSelectElement).value)">
        <option value="">全部</option><option v-for="(label, value) in difficultyLabels" :key="value" :value="value">{{ label }}</option>
      </select></label>
      <button class="button button--quiet" type="button" @click="clearFilters">清除筛选</button>
      <button class="button button--quiet" type="button" @click="load">重新载入</button>
    </form>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <p v-if="loading && data" class="server-time" role="status">正在更新当前筛选结果…</p>
    <div v-if="loading && !data" class="practice-loading" role="status">正在读取公共题目…</div>
    <section v-else-if="data?.items.length" class="question-bank-grid" aria-label="公共题目列表">
      <article v-for="item in data.items" :key="item.id" class="question-bank-card">
        <header><span>{{ stageLabels[item.stage] }} · {{ gradeLabel(item.grade) }}</span><strong>{{ difficultyLabels[item.difficulty] }}</strong></header>
        <div class="question-bank-card__tags"><span>{{ typeLabels[item.type] }}</span><span v-if="item.type === 'COMPOSITE'">含有序小问</span></div>
        <p>{{ item.stemPreview }}</p>
        <RouterLink class="button button--primary" :to="`/practice/questions/${item.id}`">查看题目</RouterLink>
      </article>
    </section>
    <section v-else-if="!loading && !errorMessage" class="practice-empty"><h3>当前筛选下没有公共题目</h3><p>可以调整学段、年级、题型或难度后重试。</p></section>
    <nav v-if="data && data.total > data.size" class="pagination" aria-label="公共题目分页">
      <button type="button" :disabled="data.page <= 1" @click="setPage(data.page - 1)">上一页</button>
      <span>第 {{ data.page }} 页，共 {{ Math.ceil(data.total / data.size) }} 页</span>
      <button type="button" :disabled="data.page * data.size >= data.total" @click="setPage(data.page + 1)">下一页</button>
    </nav>
  </main>
</template>
