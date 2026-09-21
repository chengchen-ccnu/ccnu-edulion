<script setup lang="ts">
/**
 * 自主练习建议与创建结果卡片。输入只能是 OpenAPI 的服务端动作投影；确认、取消、执行恢复都携带
 * actionId/revision，未知结果先查询再允许重试。条件编辑会立即禁用旧确认，重新生成前先取消旧动作。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { PendingPracticeAction, PracticeDifficulty } from "@edu/api-client";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";

import { studentAgentApi } from "../agent-api";
import { isUnknownPracticeCommandError, practiceActionError, practiceRegenerationPrompt,
  runPracticeCommand } from "../practice-action";

const props = defineProps<{ conversationId: string; initialAction?: PendingPracticeAction | null }>();
const emit = defineEmits<{ regenerate: [prompt: string] }>();

const action = ref<PendingPracticeAction | null>(null);
const loading = ref(false); const busy = ref(false); const unknownResult = ref(false);
const conditionsChanged = ref(false);
const message = ref(""); const topic = ref(""); const difficulty = ref<PracticeDifficulty>("MEDIUM");
const requestedCount = ref(5); const remainingSeconds = ref<number | null>(null);
const statusTarget = ref<HTMLElement | null>(null);
const cardTarget = ref<HTMLElement | null>(null);
const lifetime = new AbortController();
let loadController: AbortController | null = null;
let expiryTimer: ReturnType<typeof setInterval> | null = null;
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let expiryQueryAttempted = false;

const dirty = computed(() => Boolean(action.value) && (conditionsChanged.value || topic.value.trim() !== action.value?.criteria.topic
  || difficulty.value !== action.value?.criteria.difficulty
  || requestedCount.value !== action.value?.criteria.requestedCount));
const canConfirm = computed(() => action.value?.status === "PENDING" && action.value.actualCount > 0
  && !dirty.value && !loading.value && !busy.value && !unknownResult.value);
const shortage = computed(() => action.value && action.value.actualCount < action.value.requestedCount
  ? `目前找到 ${action.value.actualCount} 道符合条件的题，少于期望的 ${action.value.requestedCount} 道。`
  : "");
const statusText = computed(() => action.value ? ({
  PENDING: "待确认", CONFIRMED: "已确认", EXECUTING: "正在创建练习",
  SUCCEEDED: "练习已创建", FAILED: "创建失败，可保留条件重新生成",
  EXPIRED: "建议已过期", CANCELLED: "建议已取消"
} as const)[action.value.status] : "当前没有待确认的自主练习建议");
const difficultyDistribution = computed(() => (["EASY", "MEDIUM", "HARD"] as const)
  .map(value => ({ value, count: action.value?.candidateQuestions.filter(item => item.difficulty === value).length ?? 0 }))
  .filter(item => item.count).map(item => `${difficultyLabel(item.value)} ${item.count} 道`).join("、"));

watch(() => props.conversationId, () => void loadCurrent(), { immediate: true });
watch(() => props.initialAction, value => {
  // 恢复事件可能晚于首次 current 查询；身份到达后重查，不能展示历史 PENDING 快照。
  if (value && value.conversationId === props.conversationId) void loadCurrent();
}, { deep: true });
onBeforeUnmount(() => {
  lifetime.abort(); loadController?.abort();
  if (expiryTimer) clearInterval(expiryTimer);
  if (pollTimer) clearTimeout(pollTimer);
});

/** 读取会话当前动作；404 是真实空状态，其他错误保留已有条件供恢复。 */
async function loadCurrent() {
  if (!props.conversationId) return;
  loadController?.abort();
  const active = new AbortController(); loadController = active;
  action.value = null; conditionsChanged.value = false; unknownResult.value = false;
  loading.value = true; message.value = "";
  try {
    const value = await studentAgentApi.pendingPracticeActionForConversation(props.conversationId, active.signal);
    if (!active.signal.aborted && value.conversationId === props.conversationId) applyAction(value);
  } catch (error) {
    if (active.signal.aborted) return;
    if (error instanceof GatewayRequestError && error.status === 404) {
      // 消息里的 SSE 是历史快照；当前 pending 404 后按动作 ID 读取，才能恢复成功/过期等最新状态。
      if (props.initialAction?.conversationId === props.conversationId) {
        try {
          const restored = await studentAgentApi.pendingPracticeAction(props.initialAction.actionId, active.signal);
          if (!active.signal.aborted && restored) applyAction(restored);
        } catch (restoreError) {
          if (!active.signal.aborted && !(restoreError instanceof GatewayRequestError && restoreError.status === 404)) {
            message.value = practiceActionError(restoreError, "练习结果暂时无法读取。");
          }
        }
      } else action.value = null;
    } else message.value = practiceActionError(error, "练习建议暂时无法读取。");
  } finally { if (!active.signal.aborted) loading.value = false; }
}

/** 查询固定 actionId，专门恢复确认超时、响应丢失、409 并发和执行中的真实结果。 */
async function queryResult(announce = true) {
  if (!action.value || busy.value) return;
  busy.value = true;
  try {
    applyAction(await studentAgentApi.pendingPracticeAction(action.value.actionId, lifetime.signal));
    unknownResult.value = false;
    if (announce) message.value = "状态已更新。";
    else message.value = action.value?.status === "PENDING"
      ? "练习尚未创建，你可以再次确认。" : "练习状态已恢复。";
    if (action.value?.status === "EXECUTING" || action.value?.status === "CONFIRMED") schedulePoll(0);
  } catch (error) {
    if (!lifetime.signal.aborted) message.value = practiceActionError(error, "真实结果暂时无法查询。");
  } finally { busy.value = false; }
}

async function confirm() {
  if (!action.value || !canConfirm.value) return;
  await command("confirm");
}

async function cancel(andRegenerate = false) {
  if (!action.value || busy.value) return;
  if (action.value.status === "PENDING") {
    const completed = await command("cancel");
    if (!completed) return;
  }
  if (andRegenerate) emit("regenerate", practiceRegenerationPrompt(topic.value, difficulty.value, requestedCount.value));
}

/** 命令最多等待 12 秒；超时不判失败，转入未知结果并立即查询服务端动作。 */
async function command(kind: "confirm" | "cancel") {
  if (!action.value || busy.value) return false;
  busy.value = true; message.value = ""; unknownResult.value = false;
  const snapshot = action.value;
  const timeoutController = new AbortController();
  const abortFromLifetime = () => timeoutController.abort();
  lifetime.signal.addEventListener("abort", abortFromLifetime, { once: true });
  const timeout = setTimeout(() => timeoutController.abort(), 12_000);
  try {
    const result = await runPracticeCommand(snapshot, kind, (requestId, expectedRevision) => kind === "confirm"
      ? studentAgentApi.confirmPendingPracticeAction(snapshot.actionId, { requestId, expectedRevision }, timeoutController.signal)
      : studentAgentApi.cancelPendingPracticeAction(snapshot.actionId, { requestId, expectedRevision }, timeoutController.signal));
    applyAction(result);
    message.value = kind === "confirm" ? "" : "已取消这次练习。";
    if (result.status === "CONFIRMED" || result.status === "EXECUTING") schedulePoll();
    await restoreFocus();
    return true;
  } catch (error) {
    if (lifetime.signal.aborted) return false;
    unknownResult.value = isUnknownPracticeCommandError(error);
    message.value = practiceActionError(error);
    if (unknownResult.value || error instanceof GatewayRequestError && error.status === 409) {
      busy.value = false;
      await queryResult(false);
    }
    await restoreFocus();
    return false;
  } finally {
    clearTimeout(timeout);
    lifetime.signal.removeEventListener("abort", abortFromLifetime);
    busy.value = false;
  }
}

/** 浏览器最后一道防线：不把旧版本遗留的空候选或计数不一致动作挂入 DOM。 */
function applyAction(value: PendingPracticeAction) {
  if (value.actualCount < 1 || value.candidateQuestions.length < 1
      || value.candidateQuestions.length !== value.actualCount) {
    action.value = null;
    conditionsChanged.value = false;
    unknownResult.value = false;
    if (expiryTimer) clearInterval(expiryTimer);
    remainingSeconds.value = null;
    return;
  }
  const changed = action.value?.actionId !== value.actionId;
  action.value = value;
  if (changed || !conditionsChanged.value) {
    topic.value = value.criteria.topic;
    difficulty.value = value.criteria.difficulty;
    requestedCount.value = value.criteria.requestedCount;
  }
  if (changed) { conditionsChanged.value = false; expiryQueryAttempted = false; }
  updateExpiry();
  if (value.status === "CONFIRMED" || value.status === "EXECUTING") schedulePoll();
}

function invalidateConditions() {
  conditionsChanged.value = true;
  unknownResult.value = false;
  message.value = "条件已修改，旧建议不能再确认；请按新条件重新生成。";
}

/** 执行状态只做三次有限轮询；离页会同时清理轮询和请求。 */
function schedulePoll(attempt = 0) {
  if (pollTimer) clearTimeout(pollTimer);
  if (attempt >= 3 || lifetime.signal.aborted) {
    message.value = "练习还在创建中，可稍后刷新状态。";
    return;
  }
  pollTimer = setTimeout(async () => {
    if (!action.value || !["CONFIRMED", "EXECUTING"].includes(action.value.status)) return;
    busy.value = true;
    try {
      applyAction(await studentAgentApi.pendingPracticeAction(action.value.actionId, lifetime.signal));
      if (action.value && ["CONFIRMED", "EXECUTING"].includes(action.value.status)) schedulePoll(attempt + 1);
    } catch (error) {
      if (!lifetime.signal.aborted) message.value = practiceActionError(error, "执行状态暂时无法刷新。");
    } finally { busy.value = false; }
  }, 1_500);
}

function updateExpiry() {
  if (expiryTimer) clearInterval(expiryTimer);
  const tick = () => {
    if (!action.value || action.value.status !== "PENDING") { remainingSeconds.value = null; return; }
    remainingSeconds.value = Math.max(0, Math.ceil((Date.parse(action.value.expiresAt) - Date.now()) / 1000));
    if (remainingSeconds.value === 0 && !expiryQueryAttempted) {
      expiryQueryAttempted = true;
      void queryResult(false);
    }
  };
  tick(); expiryTimer = setInterval(tick, 1_000);
}

/** 操作后聚焦反馈；没有额外提示时回到卡片，避免原按钮移除后焦点丢失。 */
async function restoreFocus() { await nextTick(); (statusTarget.value ?? cardTarget.value)?.focus(); }
function difficultyLabel(value: PracticeDifficulty) { return ({ EASY: "简单", MEDIUM: "中等", HARD: "困难" } as const)[value]; }
function formatExpiry() {
  if (!action.value) return "";
  if (action.value.status !== "PENDING" || remainingSeconds.value === null) return new Date(action.value.expiresAt).toLocaleString();
  const minutes = Math.floor(remainingSeconds.value / 60); const seconds = remainingSeconds.value % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")} 后过期`;
}
</script>

<template>
  <section v-if="action" ref="cardTarget" tabindex="-1" class="practice-suggestion" aria-labelledby="practice-suggestion-title" :aria-busy="busy">
    <header><div><span>自主练习建议</span><h3 id="practice-suggestion-title">{{ action?.criteria.topic ?? "等待建议" }}</h3></div>
      <strong v-if="action" :class="`practice-suggestion__status is-${action.status.toLowerCase()}`">{{ statusText }}</strong></header>
      <dl class="practice-suggestion__facts">
        <div><dt>可练习题数</dt><dd>{{ action.actualCount }} 道</dd></div>
        <div><dt>实际难度</dt><dd>{{ difficultyDistribution }}</dd></div>
        <div v-if="action.status === 'PENDING'"><dt>有效期</dt><dd>{{ formatExpiry() }}</dd></div></dl>
      <p v-if="shortage" class="practice-suggestion__notice" role="status">{{ shortage }}</p>
      <details class="practice-suggestion__settings"><summary>调整练习条件</summary>
      <p v-if="action.criteria.allowDifficultyFallback" class="practice-suggestion__message">中等优先，不足时其他难度补齐。选择难度后，仅查找该难度的题目。</p>
      <fieldset class="practice-suggestion__editor" :disabled="busy || action.status === 'CONFIRMED' || action.status === 'EXECUTING'">
        <legend>修改条件并重新生成</legend>
        <label>主题<input v-model="topic" maxlength="200" autocomplete="off" @input="invalidateConditions"></label>
        <label>难度<select v-model="difficulty" @change="invalidateConditions"><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
        <label>数量<input v-model.number="requestedCount" type="number" min="1" max="20" inputmode="numeric" @input="invalidateConditions"></label>
      </fieldset>
      </details>
      <p v-if="message || unknownResult" ref="statusTarget" class="practice-suggestion__message" :class="{ 'is-unknown': unknownResult }" role="status" tabindex="-1">{{ message }}</p>
      <div class="practice-suggestion__actions">
        <button v-if="action.status === 'PENDING'" class="button button--primary" type="button" :disabled="!canConfirm" @click="confirm">{{ busy ? "处理中…" : "确认创建" }}</button>
        <button v-if="action.status === 'PENDING'" class="button button--quiet" type="button" :disabled="busy" @click="cancel(false)">取消</button>
        <button v-if="dirty || ['FAILED','EXPIRED','CANCELLED'].includes(action.status)" class="button button--quiet" type="button"
          :disabled="busy || !topic.trim() || requestedCount < 1 || requestedCount > 20" @click="cancel(true)">按新条件重新生成</button>
        <button v-if="unknownResult || ['CONFIRMED','EXECUTING'].includes(action.status)" class="button button--quiet" type="button" :disabled="busy" @click="queryResult()">刷新状态</button>
        <RouterLink v-if="action.status === 'SUCCEEDED' && action.executionResult" class="button button--primary" :to="action.executionResult.startRoute">开始练习</RouterLink>
      </div>
  </section>
</template>

<style scoped>
/* 卡片只强调题量和下一步，条件编辑收进原生 details，保留键盘操作。 */
.practice-suggestion { box-sizing: border-box; padding: 22px; gap: 16px; background: linear-gradient(120deg,#f4f8ff,#fff 70%); border-color: #dce5f2; border-radius: 18px; box-shadow: none; }
.practice-suggestion h3 { margin-top: 7px; font-size: 1.15rem; line-height: 1.5; }
.practice-suggestion__facts > div { padding: 0 12px 0 0; background: transparent; border-radius: 0; }
.practice-suggestion__facts dd { margin-top: 6px; font-weight: 600; }
.practice-suggestion__facts > div:first-child dd { color: #2858ae; font-size: 1.2rem; }
.practice-suggestion__notice { background: #fff8eb; color: #795827; }
.practice-suggestion__settings { border-top: 1px solid #e2e9f3; padding-top: 12px; }
.practice-suggestion__settings summary { width: fit-content; padding: 5px 0; color: #53647c; font-size: .76rem; cursor: pointer; }
.practice-suggestion__settings[open] > p { margin: 8px 0 12px; }
.practice-suggestion__editor { margin-top: 10px; }
.practice-suggestion__actions .button { min-height: 40px; padding: 0 16px; font-size: .78rem; border-radius: 10px; }
.practice-suggestion:focus { outline: 2px solid #7398d3; outline-offset: 3px; }
@media (max-width: 620px) { .practice-suggestion { padding: 16px; }.practice-suggestion h3 { font-size: 1rem; } }
</style>
