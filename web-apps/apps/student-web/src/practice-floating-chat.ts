import type { AgentConversation, AgentQuestionContextQuestion, SystemQuestion } from "@edu/api-client";

export const PRACTICE_FLOATING_IDLE_MS = 30 * 60 * 1000;

/**
 * 判断悬浮会话在下一次发送时是否还能复用。活动运行永不因空闲计时被打断；有 run 时由调用方传入终态事件时间。
 * 输入为本人会话、当前时间和可选精确活动时间，输出只用于前端选择会话，不改变服务端并发或额度裁决。
 */
export function isReusablePracticeConversation(conversation: AgentConversation, now = Date.now(),
    resolvedActivityAt?: number): boolean {
  if (conversation.entryPoint !== "PRACTICE_FLOATING" || conversation.status !== "ACTIVE") return false;
  if (conversation.activeRunId) return true;
  const activityAt = resolvedActivityAt ?? fallbackPracticeActivityAt(conversation);
  return Number.isFinite(activityAt) && now - activityAt < PRACTICE_FLOATING_IDLE_MS;
}

/** OpenAPI 没有单列 run 结束时间时的安全兜底；有 run 的页面会优先使用 FINAL_RESULT.occurredAt。 */
export function fallbackPracticeActivityAt(conversation: AgentConversation): number {
  return Math.max(Date.parse(conversation.updatedAt), Date.parse(conversation.lastMessageAt));
}

/** 从最近会话页选择仍在 30 分钟窗口内的悬浮会话；独立 AI 会话永远不会被这里选中。 */
export function selectReusablePracticeConversation(items: AgentConversation[], now = Date.now()) {
  return items.find(item => isReusablePracticeConversation(item, now)) ?? null;
}

export type PracticeDraftContext = {
  questionId: string;
  mediaIds: string[];
  projection: AgentQuestionContextQuestion;
};

/**
 * 固定一段未发送草稿的题目关联。输入是页面当前安全题目，结果只含正文安全投影和最多八个题图 mediaId，
 * 不包含答案、解析、签名地址或学生作答。
 */
export function capturePracticeDraftContext(question: SystemQuestion): PracticeDraftContext {
  const mediaIds = [...new Set([question.media, ...question.children.map(child => child.media)].flat()
    .filter(media => media.purpose === "QUESTION_IMAGE").map(media => media.mediaId))].slice(0, 8);
  return {
    questionId: question.id,
    mediaIds,
    projection: {
      questionId: question.id,
      questionVersionId: question.versionId,
      type: question.type,
      stem: question.stem,
      options: question.options.map(option => ({ key: option.key, content: option.content })),
      media: question.media.map(media => ({ id: media.id, mediaId: media.mediaId, purpose: media.purpose, sequence: media.sequence })),
      children: question.children.map(child => ({
        questionId: child.questionId,
        questionVersionId: child.versionId,
        sequence: child.sequence,
        type: child.type,
        stem: child.stem,
        options: child.options.map(option => ({ key: option.key, content: option.content })),
        media: child.media.map(media => ({ id: media.id, mediaId: media.mediaId, purpose: media.purpose, sequence: media.sequence }))
      }))
    }
  };
}
