<script setup lang="ts">
/**
 * 悬浮会话 USER 消息的历史题目卡片。输入只能是消息绑定的安全投影；题图授权固定使用 conversationId/messageId，
 * 输出为默认折叠的题型、题干、选项和有序小问。UNAVAILABLE 明确失败关闭，不查询当前公共题代替历史版本。
 */
import type { AgentMessage, AgentQuestionContextMedia, AgentQuestionContextQuestion } from "@edu/api-client";
import { SafeAssessmentContent } from "@edu/ui";
import type { AssessmentMediaResolver } from "@edu/ui";
import { computed } from "vue";

import { studentAgentApi } from "../agent-api";

const props = defineProps<{ conversationId: string; message: AgentMessage }>();
const projection = computed(() => props.message.questionContextProjection);
const question = computed(() => projection.value?.status === "AVAILABLE" ? projection.value.question : null);
const typeLabels: Record<AgentQuestionContextQuestion["type"], string> = {
  SINGLE_CHOICE: "单选题", MULTIPLE_CHOICE: "多选题", TRUE_FALSE: "判断题", FILL_BLANK: "填空题",
  SHORT_ANSWER: "简答题", COMPOSITE: "组合题"
};
const resolveMedia: AssessmentMediaResolver = (mediaId, _purpose, signal) =>
  studentAgentApi.messageQuestionMediaGrant(props.conversationId, props.message.id, mediaId, signal);

type ContextContent = Pick<AgentQuestionContextQuestion, "stem" | "options" | "media">;
/** 返回没有正文占位符的题图，避免既不漏图也不把同一图片重复展示。 */
function looseImages(content: ContextContent): AgentQuestionContextMedia[] {
  const sources = [content.stem, ...content.options.map(option => option.content)];
  const direct = new Set(sources.flatMap(source => [...source.matchAll(/\{\{media:([0-9a-fA-F-]{36})}}/g)]
    .map(match => match[1]?.toLowerCase())));
  const legacyIndexes = new Set(sources.flatMap(source => [...source.matchAll(/\[图片(\d+)]/g)]
    .map(match => Number(match[1]) - 1)));
  const ordered = content.media.filter(item => item.purpose === "QUESTION_IMAGE").sort((left, right) => left.sequence - right.sequence);
  return ordered.filter((item, index) => !direct.has(item.mediaId.toLowerCase()) && !legacyIndexes.has(index));
}
</script>

<template>
  <details v-if="projection" class="floating-question-context">
    <summary>附带题目</summary>
    <p v-if="projection.status === 'UNAVAILABLE' || !question" class="floating-question-context__unavailable">
      该消息附带的题目当前无法查看
    </p>
    <div v-else class="floating-question-context__body">
      <strong>{{ typeLabels[question.type] }}</strong>
      <SafeAssessmentContent :source="question.stem" :media="question.media" :legacy-media="question.media"
        :media-cache-scope="`${conversationId}:${message.id}:${question.questionVersionId}`" :resolve-media="resolveMedia" />
      <ol v-if="question.options.length" class="floating-question-context__options">
        <li v-for="option in question.options" :key="option.key"><strong>{{ option.key }}</strong>
          <SafeAssessmentContent :source="option.content" :media="question.media" :legacy-media="question.media"
            :media-cache-scope="`${conversationId}:${message.id}:${question.questionVersionId}`" :resolve-media="resolveMedia" />
        </li>
      </ol>
      <SafeAssessmentContent v-for="media in looseImages(question)" :key="media.id" :source="`{{media:${media.mediaId}}}`"
        :media="[media]" :media-cache-scope="`${conversationId}:${message.id}:${question.questionVersionId}`" :resolve-media="resolveMedia" />
      <section v-for="child in question.children" :key="child.questionVersionId" class="floating-question-context__child">
        <h5>({{ child.sequence }})</h5>
        <SafeAssessmentContent :source="child.stem" :media="child.media" :legacy-media="child.media"
          :media-cache-scope="`${conversationId}:${message.id}:${child.questionVersionId}`" :resolve-media="resolveMedia" />
        <ol v-if="child.options.length" class="floating-question-context__options">
          <li v-for="option in child.options" :key="option.key"><strong>{{ option.key }}</strong>
            <SafeAssessmentContent :source="option.content" :media="child.media" :legacy-media="child.media"
              :media-cache-scope="`${conversationId}:${message.id}:${child.questionVersionId}`" :resolve-media="resolveMedia" />
          </li>
        </ol>
        <SafeAssessmentContent v-for="media in looseImages(child)" :key="media.id" :source="`{{media:${media.mediaId}}}`"
          :media="[media]" :media-cache-scope="`${conversationId}:${message.id}:${child.questionVersionId}`" :resolve-media="resolveMedia" />
      </section>
    </div>
  </details>
</template>
