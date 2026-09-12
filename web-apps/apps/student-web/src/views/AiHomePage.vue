<script setup lang="ts">
/** 独立 AI 答疑入口：读取本人最近会话和公开讲解偏好，并创建可深链的新会话。 */
import { GatewayRequestError } from "@edu/api-client";
import type { AgentConversation, AgentExplanationPreference } from "@edu/api-client";
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { studentAgentApi } from "../agent-api";
import { agentError } from "../agent-errors";
const router = useRouter(); const conversations = ref<AgentConversation[]>([]); const preference = ref<AgentExplanationPreference | null>(null);
const loading = ref(true); const creating = ref(false); const saving = ref(false); const errorMessage = ref(""); const preferenceMessage = ref("");
onMounted(load);
async function load() { loading.value = true; errorMessage.value = ""; try { const [page, setting] = await Promise.all([studentAgentApi.conversations(), studentAgentApi.preference()]); conversations.value = page.items; preference.value = setting; } catch (error) { errorMessage.value = agentError(error); } finally { loading.value = false; } }
async function createConversation() { creating.value = true; errorMessage.value = ""; try { const item = await studentAgentApi.createConversation(crypto.randomUUID()); await router.push(`/ai/conversations/${item.id}`); } catch (error) { errorMessage.value = agentError(error); } finally { creating.value = false; } }
async function changePreference(event: Event) { if (!preference.value) return; const value = (event.target as HTMLSelectElement).value as AgentExplanationPreference["value"]; const previous = preference.value; saving.value = true; preferenceMessage.value = ""; try { preference.value = await studentAgentApi.updatePreference(value, previous.revision, crypto.randomUUID()); preferenceMessage.value = "讲解偏好已保存。"; } catch (error) { preferenceMessage.value = agentError(error, "讲解偏好保存失败。"); if (error instanceof GatewayRequestError && error.status === 409) { try { preference.value = await studentAgentApi.preference(); } catch {} } } finally { saving.value = false; } }
</script>
<template><main class="ai-home" aria-labelledby="ai-home-title"><header class="ai-hero"><div><span class="section-kicker">MATH AI</span><h1 id="ai-home-title">AI 答疑</h1><p>提出数学问题、上传题图或继续历史会话。AI 可能出错，请核对关键步骤。</p></div><button class="button button--primary" type="button" :disabled="creating" @click="createConversation">{{ creating ? "正在创建…" : "新建会话" }}</button></header>
  <section class="ai-preference" aria-labelledby="preference-title"><div><h2 id="preference-title">讲解偏好</h2><p>这里只显示你明确选择的方式，不展示推断画像或记忆。</p></div><label><span class="sr-only">讲解方式</span><select :value="preference?.value" :disabled="!preference || saving" @change="changePreference"><option value="GUIDED_STEP_BY_STEP">分步引导</option><option value="COMPLETE_SOLUTION">完整解答</option></select></label><p v-if="preferenceMessage" role="status">{{ preferenceMessage }}</p></section>
  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }} <button type="button" @click="load">重试</button></p><div v-if="loading" class="ai-empty" role="status">正在读取会话…</div>
  <section v-else aria-labelledby="recent-title"><h2 id="recent-title">最近会话</h2><div v-if="!conversations.length" class="ai-empty"><strong>还没有会话</strong><p>新建会话后再发送问题，空会话不会自动调用模型。</p></div><ol v-else class="ai-conversation-list"><li v-for="item in conversations" :key="item.id"><RouterLink :to="`/ai/conversations/${item.id}`"><strong>{{ item.title || "新的数学问题" }}</strong><span>{{ new Date(item.lastMessageAt).toLocaleString() }}</span><small v-if="item.activeRunId">回答进行中，可进入恢复</small></RouterLink></li></ol></section>
  <aside class="ai-boundary"><strong>使用边界</strong><p>本页不提交作答、不自动评分，也不提供课程播放器、作业或考试内嵌答疑。独立会话不会因 30 分钟空闲自动换新。</p></aside></main></template>
