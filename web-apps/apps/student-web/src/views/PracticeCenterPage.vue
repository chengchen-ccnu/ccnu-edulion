<script setup lang="ts">
/**
 * 学生练习中心：从真实列表恢复分页、课程筛选和参与状态；操作只依据服务端 learnerState/currentAttempt。
 * 刷新失败会保留上一页形成 partial-error，离页中止列表或开始请求，不假设 attempt 已创建。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { LearnerAssessmentPage, LearnerAssessmentState, LearnerAssessmentSummary } from "@edu/api-client";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";
import { learnerAssessmentAction } from "../assessment-view-model";

const route = useRoute();
const router = useRouter();
const data = ref<LearnerAssessmentPage | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const startingId = ref<string | null>(null);
let controller: AbortController | null = null;

const stateFilter = computed(() => typeof route.query.state === "string" ? route.query.state : "all");
/** 练习中心是 PRACTICE 的唯一入口；课程作业/考试即使被旧列表接口返回也不能渲染或导航。 */
const visibleItems = computed(() => (data.value?.items ?? [])
  .filter(item => item.type === "PRACTICE")
  .filter(item => matchesState(item, stateFilter.value)));

watch(() => route.query, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true; errorMessage.value = "";
  try {
    data.value = await studentAssessmentApi.list({
      page: positive(route.query.page, 1), size: 12,
      courseId: typeof route.query.courseId === "string" && route.query.courseId ? route.query.courseId : undefined
    }, controller.signal);
  } catch (error) {
    if (!controller.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!controller.signal.aborted) loading.value = false;
  }
}

async function open(item: LearnerAssessmentSummary) {
  if (item.type !== "PRACTICE") return;
  const action = learnerAssessmentAction(item);
  if (action === "result" && item.currentAttempt) {
    await router.push(`/attempt/${item.currentAttempt.id}/result`); return;
  }
  if (action === "resume" && item.currentAttempt) {
    await router.push(`/practice/${item.id}`); return;
  }
  if (action !== "start") return;
  startingId.value = item.id; errorMessage.value = "";
  const active = new AbortController(); controller?.abort(); controller = active;
  try {
    await studentAssessmentApi.start(item.id, { clientRequestId: crypto.randomUUID() }, active.signal);
    await router.push(`/practice/${item.id}`);
  } catch (error) {
    if (!active.signal.aborted) errorMessage.value = messageFor(error);
  } finally {
    if (!active.signal.aborted) startingId.value = null;
  }
}

function setQuery(name: string, value: string | number | undefined) {
  const query: Record<string, string | string[] | null | undefined> = {
    ...route.query, page: name === "page" ? String(value) : "1"
  };
  if (value === undefined || value === "" || value === "all") delete query[name]; else query[name] = String(value);
  void router.replace({ query });
}

function actionLabel(item: LearnerAssessmentSummary): string {
  const action = learnerAssessmentAction(item);
  if (action === "result") return "查看提交/结果";
  if (action === "resume") return "继续作答";
  if (action === "start") return "开始作答";
  if (item.learnerState === "NOT_OPEN") return "尚未开放";
  if (item.learnerState === "CLOSED") return "已关闭";
  return "状态同步中";
}

function matchesState(item: LearnerAssessmentSummary, filter: string): boolean {
  if (filter === "available") return item.learnerState === "AVAILABLE";
  if (filter === "progress") return item.learnerState === "IN_PROGRESS";
  if (filter === "complete") return ["SUBMITTED", "PARTIALLY_GRADED", "GRADED", "CLOSED"].includes(item.learnerState);
  return true;
}

const stateLabels: Record<LearnerAssessmentState, string> = {
  NOT_OPEN: "尚未开放", AVAILABLE: "可参与", IN_PROGRESS: "进行中", SUBMITTED: "已提交",
  PARTIALLY_GRADED: "部分批改", GRADED: "已完成", CLOSED: "已关闭"
};
const typeLabels = { PRACTICE: "练习", HOMEWORK: "作业", EXAM: "考试" } as const;
function positive(value: unknown, fallback: number) { const parsed = Number(value); return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback; }
function messageFor(error: unknown): string {
  if (error instanceof TypeError) return "当前处于离线状态；已有列表仍保留，恢复网络后可重试。";
  if (!(error instanceof GatewayRequestError)) return "练习列表暂时无法读取。";
  if (error.status === 401) return "登录状态已失效，请重新登录。";
  if (error.status === 403) return "当前账号没有学习空间访问能力。";
  if (error.status === 404) return "测评不存在、未发布或不可见。";
  if (error.status === 409) return "测评状态已经变化，请重新载入。";
  if (error.status === 422) return error.message;
  if (error.status === 429) return "请求过于频繁，请按提示稍后重试。";
  if (error.status === 503) return "课程或学习服务暂时不可用。";
  return error.message;
}
</script>

<template>
  <main class="practice-page">
    <header class="practice-heading"><div><span class="section-kicker">PRACTICE CENTER</span><h2>练习中心</h2>
      <p>开放、截止与作答状态均以服务端为准。</p></div>
      <RouterLink class="button button--quiet" to="/wrong-questions">错题本</RouterLink></header>
    <form class="practice-filters" @submit.prevent>
      <label>状态<select :value="stateFilter" @change="setQuery('state', ($event.target as HTMLSelectElement).value)">
        <option value="all">全部</option><option value="available">可参与</option>
        <option value="progress">进行中</option><option value="complete">已提交/完成</option>
      </select></label>
      <label>课程 ID<input :value="route.query.courseId ?? ''" placeholder="可选 UUID"
        @change="setQuery('courseId', ($event.target as HTMLInputElement).value.trim())"></label>
      <button class="button button--quiet" type="button" @click="load">重新载入</button>
    </form>
    <p v-if="data" class="server-time">服务端时间：{{ new Date(data.serverTime).toLocaleString('zh-CN') }}</p>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <div v-if="loading && !data" class="practice-loading" role="status">正在读取测评状态…</div>
    <section v-else-if="visibleItems.length" class="assessment-card-grid" aria-label="测评列表">
      <article v-for="item in visibleItems" :key="item.id" class="assessment-card">
        <header><span>{{ typeLabels[item.type] }}</span><strong>{{ stateLabels[item.learnerState] }}</strong></header>
        <h3>{{ item.title }}</h3>
        <dl><div><dt>开放</dt><dd>{{ item.opensAt ? new Date(item.opensAt).toLocaleString('zh-CN') : '发布后可参与' }}</dd></div>
          <div><dt>截止</dt><dd>{{ item.closesAt ? new Date(item.closesAt).toLocaleString('zh-CN') : '未设置' }}</dd></div>
          <div><dt>题目</dt><dd>{{ item.questionCount }} 题 · {{ item.totalPoints }} 分</dd></div>
          <div><dt>作答</dt><dd>{{ item.currentAttempt?.status ?? '尚未开始' }}</dd></div></dl>
        <button class="button button--primary" type="button"
          :disabled="startingId === item.id || (!item.resultAvailable && !item.resumeAllowed && item.learnerState !== 'AVAILABLE')"
          :aria-disabled="!item.resultAvailable && !item.resumeAllowed && item.learnerState !== 'AVAILABLE'"
          @click="open(item)">{{ startingId === item.id ? '正在创建…' : actionLabel(item) }}</button>
      </article>
    </section>
    <section v-else-if="!loading" class="practice-empty"><h3>当前筛选下没有测评</h3><p>只有服务端返回的已发布测评会出现在这里。</p></section>
    <nav v-if="data && data.total > data.size" class="pagination" aria-label="练习分页">
      <button type="button" :disabled="data.page <= 1" @click="setQuery('page', data.page - 1)">上一页</button>
      <span>第 {{ data.page }} 页</span><button type="button" :disabled="data.page * data.size >= data.total"
        @click="setQuery('page', data.page + 1)">下一页</button>
    </nav>
  </main>
</template>
