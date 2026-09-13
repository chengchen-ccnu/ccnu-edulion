<script setup lang="ts">
/**
 * 普通公共题页的临时 AI 答疑承载。输入是当前安全题目；首次真实发送才创建 PRACTICE_FLOATING 会话，
 * 切题保留会话、消息、生成和草稿原关联。关闭或离开页面会取消活动 run、恢复服务端已保存部分并清理请求/监听器。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AgentConversation, AgentMessage, SystemQuestion } from "@edu/api-client";
import { SafeArticleContent } from "@edu/ui";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";

import { studentAgentApi } from "../agent-api";
import { agentError } from "../agent-errors";
import { consumeAgentSse, emptyAgentStreamState, reduceAgentEvent } from "../agent-stream";
import type { AgentStreamState } from "../agent-stream";
import { capturePracticeDraftContext, isReusablePracticeConversation,
  fallbackPracticeActivityAt } from "../practice-floating-chat";
import type { PracticeDraftContext } from "../practice-floating-chat";
import AgentQuestionContextCard from "./AgentQuestionContextCard.vue";

const props = defineProps<{ question: SystemQuestion | null }>();
const router = useRouter();
const openButton = ref<HTMLButtonElement | null>(null); const panel = ref<HTMLElement | null>(null);
const panelOpen = ref(false); const loading = ref(false); const sending = ref(false); const creating = ref(false);
const conversation = ref<AgentConversation | null>(null); const messages = ref<AgentMessage[]>([]);
const stream = ref<AgentStreamState | null>(null); const errorMessage = ref(""); const draft = ref("");
const draftContext = ref<PracticeDraftContext | null>(null); const initialized = ref(false);
const resolvedActivityAt = ref<number | undefined>();
let controller: AbortController | null = null;
const lifetime = new AbortController(); let disposed = false;
const currentQuestionChanged = computed(() => Boolean(draft.value && draftContext.value && props.question
  && draftContext.value.questionId !== props.question.id));
const lastUserMessage = computed(() => [...messages.value].reverse().find(item => item.role === "USER"
  && item.contentState !== "PURGED") ?? null);
const lastAssistant = computed(() => {
  const user = lastUserMessage.value; if (!user) return null;
  const index = messages.value.findIndex(item => item.id === user.id);
  return messages.value.slice(index + 1).filter(item => item.role === "ASSISTANT"
    && item.contentState !== "PURGED").at(-1) ?? null;
});
const canRegenerate = computed(() => !sending.value && !conversation.value?.activeRunId
  && Boolean(lastUserMessage.value && lastAssistant.value));
const displayMessages = computed(() => messages.value.filter(message => !(stream.value && !stream.value.terminal
  && message.role === "ASSISTANT" && message.runId === stream.value.runId)));

watch(draft, value => {
  if (value && !draftContext.value && props.question) draftContext.value = capturePracticeDraftContext(props.question);
  if (!value) draftContext.value = null;
});
watch(() => [messages.value.length, stream.value?.answer], () => void scrollToBottom());
onMounted(() => document.addEventListener("keydown", onDocumentKeydown));
onBeforeUnmount(() => {
  disposed = true; document.removeEventListener("keydown", onDocumentKeydown); lifetime.abort(); controller?.abort();
  const runId = activeRunId(); if (runId) void studentAgentApi.cancel(runId, "PAGE_CLOSED").catch(() => {});
});

/** 打开只读取可复用会话，不创建空会话；焦点进入对话框便于键盘继续操作。 */
async function openPanel() {
  panelOpen.value = true; await nextTick(); panel.value?.focus();
  if (!initialized.value) await initialize();
}
async function initialize() {
  initialized.value = true; loading.value = true; errorMessage.value = "";
  try {
    const page = await studentAgentApi.conversations(1, 100, lifetime.signal);
    const recent = page.items.find(item => item.entryPoint === "PRACTICE_FLOATING" && item.status === "ACTIVE");
    if (recent) await loadConversation(recent, true);
  } catch (error) { if (!lifetime.signal.aborted) errorMessage.value = agentError(error, "最近一次悬浮答疑暂时无法读取。"); }
  finally { if (!disposed) loading.value = false; }
}
async function loadConversation(item: AgentConversation, enforceIdleWindow = false) {
  const history = await loadAllMessages(item.id);
  let recovered: AgentStreamState | null = null;
  if (item.activeRunId) {
    recovered = await recover(item.activeRunId);
  }
  const activityAt = recovered?.lastOccurredAt ? Date.parse(recovered.lastOccurredAt)
    : await resolveLastActivityAt(item, history);
  if (enforceIdleWindow && !isReusablePracticeConversation(item, Date.now(), activityAt)) return;
  if (disposed) return;
  conversation.value = item; messages.value = history; stream.value = recovered;
  resolvedActivityAt.value = activityAt; sending.value = Boolean(recovered && !recovered.terminal);
}
/** 最近 run 的唯一终态时间优先于消息创建时间，保证长回答结束后仍获得完整 30 分钟窗口。 */
async function resolveLastActivityAt(item: AgentConversation, history: AgentMessage[]) {
  const runId = [...history].reverse().find(message => message.runId)?.runId;
  if (!runId) return fallbackPracticeActivityAt(item);
  try {
    const events = await studentAgentApi.events(runId, undefined, lifetime.signal);
    const terminal = [...events.items].reverse().find(event => event.eventType === "FINAL_RESULT");
    const terminalAt = terminal ? Date.parse(terminal.occurredAt) : Number.NaN;
    return Number.isFinite(terminalAt) ? terminalAt : fallbackPracticeActivityAt(item);
  } catch (error) {
    if (lifetime.signal.aborted) throw error;
    return fallbackPracticeActivityAt(item);
  }
}
/** 发送瞬间重新检查 30 分钟窗口；过期只换新会话，不删除旧历史，也不会中断仍在运行的回答。 */
async function conversationForSend() {
  if (conversation.value && isReusablePracticeConversation(conversation.value, Date.now(), resolvedActivityAt.value)) return conversation.value;
  creating.value = true;
  try {
    const created = await studentAgentApi.createConversation(crypto.randomUUID(), "PRACTICE_FLOATING", lifetime.signal);
    conversation.value = created; messages.value = []; stream.value = null;
    resolvedActivityAt.value = fallbackPracticeActivityAt(created); return created;
  } finally { creating.value = false; }
}
async function send() {
  const content = draft.value.trim();
  if (!content || sending.value || creating.value) return;
  if (!draftContext.value) {
    if (!props.question) { errorMessage.value = "题目仍在加载，请稍后再发送。"; return; }
    draftContext.value = capturePracticeDraftContext(props.question);
  }
  const context = draftContext.value;
  errorMessage.value = "";
  let item: AgentConversation;
  try { item = await conversationForSend(); }
  catch (error) { errorMessage.value = agentError(error, "悬浮会话创建失败，提问草稿已保留。"); return; }
  const optimisticId = `pending-${crypto.randomUUID()}`;
  messages.value.push({ id: optimisticId, conversationId: item.id, role: "USER", content,
    completeness: "COMPLETE", questionContextProjection: { status: "AVAILABLE", question: context.projection },
    sequenceNo: messages.value.length + 1, createdAt: new Date().toISOString() });
  sending.value = true; stream.value = emptyAgentStreamState();
  const active = new AbortController(); controller = active; let accepted = false;
  try {
    const response = await studentAgentApi.send(item.id, { clientRequestId: crypto.randomUUID(), content,
      context: { questionId: context.questionId, questionMediaIds: context.mediaIds } }, active.signal);
    accepted = true; draft.value = ""; draftContext.value = null;
    stream.value.runId = response.headers.get("X-Agent-Run-Id") ?? "";
    await consumeAgentSse(response, event => { stream.value = reduceAgentEvent(stream.value!, event); }, active.signal);
    if (!stream.value.terminal) throw new Error("AGENT_STREAM_TRUNCATED");
    resolvedActivityAt.value = Date.parse(stream.value.lastOccurredAt);
    await refresh();
  } catch (error) {
    if (!accepted) messages.value = messages.value.filter(message => message.id !== optimisticId);
    if (!active.signal.aborted) {
      errorMessage.value = error instanceof GatewayRequestError && error.status === 409
        ? "此会话正在回答，请等待当前回答结束后再发送。" : agentError(error, "连接中断，正在读取已保存内容…");
      const runId = activeRunId();
      if (accepted && runId) {
        stream.value = await recover(runId);
        if (stream.value.terminal) resolvedActivityAt.value = Date.parse(stream.value.lastOccurredAt);
      }
    }
  } finally { controller = null; sending.value = Boolean(activeRunId()); }
}
async function regenerate() {
  if (!canRegenerate.value || !conversation.value || !lastUserMessage.value) return;
  await runExisting(signal => studentAgentApi.regenerate(conversation.value!.id, lastUserMessage.value!.id,
    crypto.randomUUID(), signal));
}
async function runExisting(open: (signal: AbortSignal) => Promise<Response>) {
  errorMessage.value = ""; sending.value = true; stream.value = emptyAgentStreamState();
  const active = new AbortController(); controller = active;
  try {
    const response = await open(active.signal); stream.value.runId = response.headers.get("X-Agent-Run-Id") ?? "";
    await consumeAgentSse(response, event => { stream.value = reduceAgentEvent(stream.value!, event); }, active.signal);
    if (!stream.value.terminal) throw new Error("AGENT_STREAM_TRUNCATED");
    resolvedActivityAt.value = Date.parse(stream.value.lastOccurredAt); await refresh();
  } catch (error) {
    if (!active.signal.aborted) {
      errorMessage.value = error instanceof GatewayRequestError && error.status === 409
        ? "此会话正在回答，请等待当前回答结束后再操作。" : agentError(error, "连接中断，正在读取已保存内容…");
      const runId = activeRunId();
      if (runId) {
        stream.value = await recover(runId);
        if (stream.value.terminal) resolvedActivityAt.value = Date.parse(stream.value.lastOccurredAt);
      }
    }
  } finally { controller = null; sending.value = Boolean(activeRunId()); }
}
async function recover(runId: string) {
  try {
    const page = await studentAgentApi.events(runId, undefined, lifetime.signal); let state = emptyAgentStreamState();
    state.runId = runId;
    for (const event of page.items) state = reduceAgentEvent(state, event);
    if (!state.terminal) errorMessage.value = "此会话正在回答；这里只显示服务端已保存的内容。";
    return state;
  } catch (error) {
    errorMessage.value = agentError(error, "已保存内容暂时无法恢复。");
    return { ...emptyAgentStreamState(), runId };
  }
}
async function stop(reason: "USER_STOP" | "PAGE_CLOSED" = "USER_STOP") {
  const runId = activeRunId(); controller?.abort();
  if (!runId) { sending.value = false; return; }
  try {
    await studentAgentApi.cancel(runId, reason); stream.value = await recover(runId); await refresh();
  } catch (error) { errorMessage.value = agentError(error, "停止请求未确认，请稍后重新打开查看最终状态。"); }
  finally { sending.value = Boolean(activeRunId()); }
}
async function closePanel() {
  if (activeRunId()) await stop("PAGE_CLOSED");
  panelOpen.value = false; await nextTick(); openButton.value?.focus();
}
async function openFullPage() {
  if (!conversation.value) return;
  if (activeRunId()) await stop("PAGE_CLOSED");
  await router.push(`/ai/conversations/${conversation.value.id}`);
}
function switchDraftToCurrentQuestion() {
  if (props.question) draftContext.value = capturePracticeDraftContext(props.question);
}
function activeRunId() { return stream.value && !stream.value.terminal ? stream.value.runId : conversation.value?.activeRunId ?? ""; }
async function loadAllMessages(id: string) {
  const first = await studentAgentApi.messages(id, 1, 100, lifetime.signal); const all = [...first.items];
  for (let page = 2; all.length < first.total; page += 1) {
    const next = await studentAgentApi.messages(id, page, 100, lifetime.signal); all.push(...next.items); if (!next.items.length) break;
  }
  return all;
}
async function refresh() {
  if (!conversation.value) return;
  const id = conversation.value.id;
  const [history, item] = await Promise.all([loadAllMessages(id), studentAgentApi.conversation(id, lifetime.signal)]);
  if (conversation.value?.id === id) { messages.value = history; conversation.value = item; stream.value = null; }
}
function onDocumentKeydown(event: KeyboardEvent) { if (event.key === "Escape" && panelOpen.value) { event.preventDefault(); void closePanel(); } }
async function scrollToBottom() {
  await nextTick(); const viewport = panel.value?.querySelector<HTMLElement>(".practice-chat__messages");
  if (!viewport) return;
  if (typeof viewport.scrollTo === "function") viewport.scrollTo({ top: viewport.scrollHeight });
  else viewport.scrollTop = viewport.scrollHeight;
}
</script>

<template>
  <div class="practice-floating-chat">
    <button v-if="!panelOpen" ref="openButton" class="practice-chat-trigger" type="button" aria-label="AI 答疑" @click="openPanel">
      <span aria-hidden="true">AI</span><strong>答疑</strong>
    </button>
    <section v-else ref="panel" class="practice-chat" role="dialog" aria-modal="true" aria-labelledby="practice-chat-title" tabindex="-1">
      <header class="practice-chat__header"><div><span>围绕当前公共题目</span><h2 id="practice-chat-title">AI 答疑</h2></div>
        <button class="practice-chat__close" type="button" aria-label="关闭 AI 答疑" @click="closePanel">×</button></header>
      <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
      <div class="practice-chat__messages" aria-live="polite" :aria-busy="sending">
        <p v-if="loading" class="practice-chat__empty" role="status">正在读取最近答疑…</p>
        <div v-else-if="!messages.length && !stream" class="practice-chat__empty"><strong>从这道题开始问</strong><p>我会附带当前题目，但不会提交答案、评分或修改学习进度。</p></div>
        <article v-for="message in displayMessages" :key="message.id" class="practice-chat-message" :class="`practice-chat-message--${message.role.toLowerCase()}`">
          <header><strong>{{ message.role === "USER" ? "你" : "AI 助教" }}</strong><span>{{ new Date(message.createdAt).toLocaleTimeString() }}</span></header>
          <p v-if="message.contentState === 'PURGED'" class="ai-purged">原始内容已按保留策略清理。</p>
          <SafeArticleContent v-else :source="message.content" :images="[]" />
          <AgentQuestionContextCard v-if="message.role === 'USER' && message.questionContextProjection"
            :conversation-id="conversation?.id ?? message.conversationId" :message="message" />
          <small v-if="message.completeness === 'PARTIAL' && message.content.trim() !== '回答已中断'" class="ai-partial">回答已中断</small>
          <footer v-if="message.id === lastAssistant?.id && canRegenerate" class="practice-chat__answer-actions">
            <button class="button button--quiet" type="button" @click="regenerate">重新生成回答</button>
            <small>沿用原消息附带题目，保留旧回答，并可能消耗额度。</small>
          </footer>
        </article>
        <article v-if="stream && (sending || stream.answer)" class="practice-chat-message practice-chat-message--assistant">
          <header><strong>AI 助教</strong><span>{{ stream.stage || "正在回答" }}</span></header>
          <SafeArticleContent :source="stream.answer" :images="[]" />
          <small v-if="stream.partial" class="ai-partial">回答已中断</small>
        </article>
      </div>
      <form class="practice-chat__composer" @submit.prevent="send">
        <p v-if="currentQuestionChanged" class="practice-chat__draft-note">这段提问仍关联上一道题
          <button type="button" @click="switchDraftToCurrentQuestion">切换为当前题目</button></p>
        <label for="practice-chat-question" class="sr-only">输入关于当前题目的问题</label>
        <textarea id="practice-chat-question" v-model="draft" rows="3" maxlength="10000"
          placeholder="输入关于这道题的疑问；不会发送正在编辑的答案"></textarea>
        <div class="practice-chat__actions"><button v-if="sending" class="button button--danger" type="button" @click="stop('USER_STOP')">停止</button>
          <button v-else class="button button--primary" type="submit" :disabled="creating || !draft.trim()">{{ creating ? "正在创建…" : "发送" }}</button>
          <button class="button button--quiet" type="button" :disabled="!conversation" @click="openFullPage">转到完整页面</button></div>
      </form>
    </section>
  </div>
</template>
