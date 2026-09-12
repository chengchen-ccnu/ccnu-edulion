<script setup lang="ts">
/**
 * 独立 AI 对话统一工作区。两条路由共享一份会话列表、抽屉和聊天状态；路由变化只替换中央内容。
 * 页面离开会中止上传/流请求并请求保存中断结果，移动抽屉关闭后把焦点归还给触发按钮。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AgentAuthorizedReference, AgentConversation, AgentExplanationPreference, AgentMessage } from "@edu/api-client";
import { SafeArticleContent } from "@edu/ui";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { studentAgentApi } from "../agent-api";
import { agentError } from "../agent-errors";
import { consumeAgentSse, emptyAgentStreamState, reduceAgentEvent } from "../agent-stream";
import type { AgentStreamState } from "../agent-stream";
import { uploadAiQuestionImage } from "../ai-image-upload";
import AiQuestionImage from "../components/AiQuestionImage.vue";

const route = useRoute(); const router = useRouter();
const conversations = ref<AgentConversation[]>([]); const conversation = ref<AgentConversation | null>(null);
const messages = ref<AgentMessage[]>([]); const preference = ref<AgentExplanationPreference | null>(null);
const stream = ref<AgentStreamState | null>(null); const references = ref<AgentAuthorizedReference[]>([]);
const content = ref(""); const imageIds = ref<string[]>([]); const loadingWorkspace = ref(true); const loadingConversation = ref(false);
const creating = ref(false); const sending = ref(false); const uploading = ref(false); const saving = ref(false);
const errorMessage = ref(""); const preferenceMessage = ref(""); const uploadMessage = ref("");
const drawerOpen = ref(false); const compact = ref(false); const historyButton = ref<HTMLButtonElement | null>(null);
const drawerFirstButton = ref<HTMLButtonElement | null>(null); const drawerPanel = ref<HTMLElement | null>(null); const messageViewport = ref<HTMLElement | null>(null);
let controller: AbortController | null = null; let uploadController: AbortController | null = null;
let mediaQuery: MediaQueryList | null = null;
const conversationId = computed(() => typeof route.params.conversationId === "string" ? route.params.conversationId : "");
const lastUserIndex = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    const item = messages.value[index]; if (item?.role === "USER" && item.contentState !== "PURGED") return index;
  }
  return -1;
});
const lastRegenerableAssistant = computed(() => {
  if (lastUserIndex.value < 0) return null;
  const candidates = messages.value.slice(lastUserIndex.value + 1).filter(item => item.role === "ASSISTANT"
    && item.contentState !== "PURGED" && (item.completeness === "COMPLETE" || item.completeness === "PARTIAL"));
  return candidates.at(-1) ?? null;
});
const canRegenerate = computed(() => !sending.value && !conversation.value?.activeRunId && Boolean(lastRegenerableAssistant.value));

onMounted(async () => {
  mediaQuery = window.matchMedia("(max-width: 960px)"); updateCompact(mediaQuery); mediaQuery.addEventListener("change", updateCompact);
  await loadWorkspace();
});
watch(conversationId, async id => { drawerOpen.value = false; if (id) await loadConversation(id); else clearConversation(); }, { immediate: true });
watch(() => [messages.value.length, stream.value?.answer], () => void scrollToBottom());
onBeforeUnmount(() => {
  mediaQuery?.removeEventListener("change", updateCompact); controller?.abort(); uploadController?.abort();
  if (stream.value?.runId && !stream.value.terminal) void studentAgentApi.cancel(stream.value.runId, "PAGE_CLOSED").catch(() => {});
});

function updateCompact(event: MediaQueryList | MediaQueryListEvent) { compact.value = event.matches; if (!event.matches) drawerOpen.value = false; }
async function loadWorkspace() {
  loadingWorkspace.value = true; errorMessage.value = "";
  try { const [page, setting] = await Promise.all([studentAgentApi.conversations(), studentAgentApi.preference()]); conversations.value = page.items; preference.value = setting; }
  catch (error) { errorMessage.value = agentError(error); }
  finally { loadingWorkspace.value = false; }
}
function clearConversation() { controller?.abort(); conversation.value = null; messages.value = []; stream.value = null; references.value = []; loadingConversation.value = false; }
async function loadConversation(id: string) {
  controller?.abort(); stream.value = null; messages.value = []; references.value = []; errorMessage.value = ""; loadingConversation.value = true;
  const active = new AbortController(); controller = active;
  try {
    const [item, history] = await Promise.all([studentAgentApi.conversation(id, active.signal), loadAllMessages(id, active.signal)]);
    if (active.signal.aborted || id !== conversationId.value) return;
    conversation.value = item; messages.value = history;
    if (item.activeRunId) await recover(item.activeRunId); else await restoreLastReferences(history);
    await scrollToBottom(false);
  } catch (error) { if (!active.signal.aborted) errorMessage.value = agentError(error); }
  finally { if (!active.signal.aborted) loadingConversation.value = false; }
}
async function createConversation() {
  creating.value = true; errorMessage.value = "";
  try { const item = await studentAgentApi.createConversation(crypto.randomUUID()); conversations.value = [item, ...conversations.value.filter(value => value.id !== item.id)]; await router.push(`/ai/conversations/${item.id}`); }
  catch (error) { errorMessage.value = agentError(error); }
  finally { creating.value = false; }
}
async function changePreference(event: Event) {
  if (!preference.value) return; const value = (event.target as HTMLSelectElement).value as AgentExplanationPreference["value"];
  const previous = preference.value; saving.value = true; preferenceMessage.value = "";
  try { preference.value = await studentAgentApi.updatePreference(value, previous.revision, crypto.randomUUID()); preferenceMessage.value = "讲解偏好已保存。"; }
  catch (error) { preferenceMessage.value = agentError(error, "讲解偏好保存失败。"); if (error instanceof GatewayRequestError && error.status === 409) { try { preference.value = await studentAgentApi.preference(); } catch {} } }
  finally { saving.value = false; }
}
async function send() {
  const question = content.value.trim(); if (!question || sending.value || uploading.value || !conversationId.value) return;
  content.value = ""; const context = imageIds.value.length ? { aiQuestionMediaIds: [...imageIds.value] } : undefined; imageIds.value = [];
  await run(signal => studentAgentApi.send(conversationId.value, { clientRequestId: crypto.randomUUID(), content: question, context }, signal), question, context?.aiQuestionMediaIds ?? []);
}
async function regenerate() {
  if (!canRegenerate.value || lastUserIndex.value < 0) return;
  const message = messages.value[lastUserIndex.value]; if (!message) return;
  await run(signal => studentAgentApi.regenerate(conversationId.value, message.id, crypto.randomUUID(), signal));
}
async function run(open: (signal: AbortSignal) => Promise<Response>, optimisticText?: string, mediaIds: string[] = []) {
  errorMessage.value = ""; sending.value = true; references.value = []; stream.value = emptyAgentStreamState();
  if (optimisticText) messages.value.push({ id: crypto.randomUUID(), conversationId: conversationId.value, role: "USER", content: optimisticText, completeness: "COMPLETE", context: mediaIds.length ? { mediaIds } : undefined, sequenceNo: messages.value.length + 1, createdAt: new Date().toISOString() });
  const active = new AbortController(); controller = active;
  try {
    const response = await open(active.signal); stream.value.runId = response.headers.get("X-Agent-Run-Id") ?? "";
    await consumeAgentSse(response, event => { stream.value = reduceAgentEvent(stream.value!, event); references.value = stream.value.references; }, active.signal);
    if (!stream.value.terminal) throw new Error("AGENT_STREAM_TRUNCATED"); await refreshMessages();
  } catch (error) { if (!active.signal.aborted) { errorMessage.value = agentError(error, "连接中断，正在读取已保存事件…"); const runId = responseRunId(stream.value); if (runId) await recover(runId); } }
  finally { sending.value = false; }
}
async function recover(runId: string) {
  try { const page = await studentAgentApi.events(runId); let state = emptyAgentStreamState(); for (const event of page.items) state = reduceAgentEvent(state, event); stream.value = state; references.value = state.references; if (state.terminal) await refreshMessages(); else errorMessage.value = "连接已中断；这里只显示服务端已保存的部分回答，可稍后刷新继续恢复。"; }
  catch (error) { errorMessage.value = agentError(error, "已保存事件暂时无法恢复。"); }
}
async function stop() {
  const current = stream.value; controller?.abort(); if (!current?.runId) { sending.value = false; return; }
  try { await studentAgentApi.cancel(current.runId, "USER_STOP"); await recover(current.runId); }
  catch (error) { errorMessage.value = agentError(error, "停止请求未确认，请刷新查看最终状态。"); }
  finally { sending.value = false; }
}
async function loadAllMessages(id = conversationId.value, signal?: AbortSignal) { const first = await studentAgentApi.messages(id, 1, 100, signal); const all = [...first.items]; for (let page = 2; all.length < first.total; page += 1) { const next = await studentAgentApi.messages(id, page, 100, signal); all.push(...next.items); if (!next.items.length) break; } return all; }
async function refreshMessages() { messages.value = await loadAllMessages(); conversation.value = await studentAgentApi.conversation(conversationId.value); const index = conversations.value.findIndex(item => item.id === conversation.value?.id); if (index >= 0 && conversation.value) conversations.value.splice(index, 1, conversation.value); await scrollToBottom(); }
async function restoreLastReferences(history: AgentMessage[]) { const runId = [...history].reverse().find(item => item.role === "ASSISTANT" && item.runId)?.runId; if (!runId) return; const page = await studentAgentApi.events(runId); references.value = page.items.flatMap(event => event.eventType === "REFERENCE" ? [event.payload.reference] : event.eventType === "FINAL_RESULT" ? event.payload.references ?? [] : []).filter((item, index, all) => all.findIndex(candidate => candidate.referenceId === item.referenceId) === index); }
async function chooseImage(event: Event) { const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ""; if (!file) return; uploadController?.abort(); const active = new AbortController(); uploadController = active; uploading.value = true; uploadMessage.value = ""; try { const id = await uploadAiQuestionImage(file, active.signal, message => uploadMessage.value = message); imageIds.value.push(id); uploadMessage.value = "题图已就绪，只会随消息发送稳定 mediaId。"; } catch (error) { if (!active.signal.aborted) uploadMessage.value = agentError(error, "题图上传失败。"); } finally { uploading.value = false; } }
async function openDrawer() { drawerOpen.value = true; await nextTick(); drawerFirstButton.value?.focus(); }
async function closeDrawer(returnFocus = true) { drawerOpen.value = false; if (returnFocus) { await nextTick(); historyButton.value?.focus(); } }
function drawerKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") { event.preventDefault(); void closeDrawer(); return; }
  if (event.key !== "Tab" || !compact.value || !drawerOpen.value || !drawerPanel.value) return;
  const focusable = [...drawerPanel.value.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],select:not(:disabled),[tabindex]:not([tabindex="-1"])')];
  const first = focusable[0]; const last = focusable.at(-1); if (!first || !last) return;
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
async function scrollToBottom(smooth = true) { await nextTick(); messageViewport.value?.scrollTo({ top: messageViewport.value.scrollHeight, behavior: smooth ? "smooth" : "auto" }); }
function responseRunId(state: AgentStreamState | null) { return state?.runId || conversation.value?.activeRunId || ""; }
function sourceLabel(source: AgentAuthorizedReference["sourceType"]) { return ({ QUESTION: "题目", KNOWLEDGE_POINT: "知识点", COURSE_TEXT: "课程正文", STUDENT_FACT: "本人学习事实", USER_HELP: "平台帮助" } as const)[source]; }
</script>

<template>
  <main class="ai-workspace" aria-label="AI 答疑工作区">
    <button v-if="compact && drawerOpen" class="ai-drawer-backdrop" type="button" aria-label="关闭会话历史" @click="closeDrawer()" />
    <aside ref="drawerPanel" class="ai-sidebar" :class="{ 'is-drawer': compact, 'is-open': drawerOpen }" :aria-hidden="compact && !drawerOpen" :inert="compact && !drawerOpen" :role="compact ? 'dialog' : undefined" :aria-modal="compact ? true : undefined" aria-label="会话历史" @keydown="drawerKeydown">
      <header><div><span class="section-kicker">EDU AI</span><h1>AI 答疑</h1></div><button v-if="compact" class="ai-drawer-close" type="button" aria-label="关闭会话历史" @click="closeDrawer()">×</button></header>
      <button ref="drawerFirstButton" class="button button--primary ai-new-conversation" type="button" :disabled="creating" @click="createConversation">{{ creating ? "正在创建…" : "新建会话" }}</button>
      <div class="ai-sidebar__history"><h2>最近会话</h2><p v-if="loadingWorkspace" role="status">正在读取会话…</p><p v-else-if="!conversations.length">还没有会话</p>
        <ol v-else class="ai-conversation-list"><li v-for="item in conversations" :key="item.id"><RouterLink :to="`/ai/conversations/${item.id}`" :aria-current="item.id === conversationId ? 'page' : undefined" @click="compact && closeDrawer(false)"><strong>{{ item.title || "新的数学问题" }}</strong><span>{{ new Date(item.lastMessageAt).toLocaleString() }}</span><small v-if="item.activeRunId">回答进行中</small></RouterLink></li></ol></div>
      <section class="ai-preference" aria-labelledby="preference-title"><div><h2 id="preference-title">讲解偏好</h2><p>只显示你明确选择的方式。</p></div><label><span class="sr-only">讲解方式</span><select :value="preference?.value" :disabled="!preference || saving" @change="changePreference"><option value="GUIDED_STEP_BY_STEP">分步引导</option><option value="COMPLETE_SOLUTION">完整解答</option></select></label><p v-if="preferenceMessage" role="status">{{ preferenceMessage }}</p></section>
    </aside>

    <section class="ai-main-panel">
      <header class="ai-chat__header"><button v-if="compact" ref="historyButton" class="button button--quiet" type="button" aria-haspopup="dialog" :aria-expanded="drawerOpen" @click="openDrawer">会话历史</button><div><span class="section-kicker">MATH LEARNING</span><h2>{{ conversationId ? (conversation?.title || "AI 数学答疑") : "开始一次数学讨论" }}</h2></div><button v-if="sending" class="button button--danger" type="button" @click="stop">停止生成</button></header>
      <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }} <button v-if="!conversationId" type="button" @click="loadWorkspace">重试</button></p>
      <div v-if="!conversationId" class="ai-welcome"><div><span class="section-kicker">WELCOME</span><h3>把疑问留在这里，我们一起拆开它</h3><p>你可以输入数学问题、附加题图，或从左侧继续最近会话。AI 可能出错，请核对关键步骤。</p><button class="button button--primary" type="button" :disabled="creating" @click="createConversation">新建会话</button></div><aside><strong>使用边界</strong><p>本页不提交作答、不自动评分；独立会话不会因 30 分钟空闲自动换新。</p></aside></div>
      <div v-else-if="loadingConversation" class="ai-empty" role="status">正在恢复会话…</div>
      <template v-else>
        <section ref="messageViewport" class="ai-message-viewport" aria-live="polite"><div class="ai-message-list"><article v-for="message in messages" :key="message.id" class="ai-message" :class="`ai-message--${message.role.toLowerCase()}`"><header><strong>{{ message.role === "USER" ? "你" : "AI 助教" }}</strong><span>{{ new Date(message.createdAt).toLocaleTimeString() }}</span></header><p v-if="message.contentState === 'PURGED'" class="ai-purged">原始内容已按保留策略清理。</p><SafeArticleContent v-else :source="message.content" :images="[]"/><div v-if="message.role === 'USER' && message.context?.mediaIds?.length" class="ai-message-images"><AiQuestionImage v-for="id in message.context.mediaIds" :key="id" :media-id="id" /></div><small v-if="message.completeness === 'PARTIAL'" class="ai-partial">回答已中断</small>
          <footer v-if="message.id === lastRegenerableAssistant?.id && canRegenerate" class="ai-answer-actions"><button class="button button--quiet" type="button" @click="regenerate">重新生成回答</button><small>会创建新运行、保留旧回答，并可能消耗额度。</small></footer></article>
          <article v-if="stream && (sending || !stream.terminal)" class="ai-message ai-message--assistant"><header><strong>AI 助教</strong><span>{{ stream.stage || "正在思考" }}</span></header><SafeArticleContent :source="stream.answer" :images="[]"/><small v-if="stream.partial">回答已中断</small></article></div>
          <section v-if="references.length" class="ai-references" aria-labelledby="reference-title"><h3 id="reference-title">授权来源</h3><ul><li v-for="item in references" :key="item.referenceId"><span>{{ sourceLabel(item.sourceType) }}</span><RouterLink v-if="item.appRoute" :to="item.appRoute">{{ item.title }}</RouterLink><strong v-else>{{ item.title }}</strong></li></ul></section></section>
        <form class="ai-composer" @submit.prevent="send"><label for="ai-question" class="sr-only">输入数学问题</label><textarea id="ai-question" v-model="content" rows="3" maxlength="8000" placeholder="输入问题；支持 Markdown 与 LaTeX 公式" :disabled="sending"></textarea><div class="ai-composer__actions"><label class="button button--quiet"><input class="sr-only" type="file" accept="image/jpeg,image/png,image/webp" :disabled="sending || uploading" @change="chooseImage">{{ uploading ? "正在上传…" : "添加题图" }}</label><span v-if="imageIds.length">已附加 {{ imageIds.length }} 张题图</span><button class="button button--primary" type="submit" :disabled="sending || uploading || !content.trim()">发送</button></div><p v-if="uploadMessage" role="status">{{ uploadMessage }}</p></form>
      </template>
    </section>
  </main>
</template>
