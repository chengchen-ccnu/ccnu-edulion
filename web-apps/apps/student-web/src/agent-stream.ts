import type { AgentAuthorizedReference, AgentClientStreamEvent, PendingPracticeAction } from "@edu/api-client";

export interface AgentStreamState {
  runId: string;
  messageId: string;
  answer: string;
  stage: string;
  references: AgentAuthorizedReference[];
  lastSequence: number;
  lastOccurredAt: string;
  terminal: boolean;
  partial: boolean;
  error: string;
  pendingPracticeAction: PendingPracticeAction | null;
}

export function emptyAgentStreamState(): AgentStreamState {
  return { runId: "", messageId: "", answer: "", stage: "", references: [], lastSequence: 0,
    lastOccurredAt: "", terminal: false, partial: false, error: "", pendingPracticeAction: null };
}

/**
 * 将 OpenAPI 九类事件归并为页面状态；输入必须按 sequence 连续，唯一终态后拒绝任何事件。
 * 返回新状态供 Vue 渲染，断线时已持久化的 PARTIAL_CHECKPOINT 会替换未确认的增量文本。
 */
export function reduceAgentEvent(state: AgentStreamState, event: AgentClientStreamEvent): AgentStreamState {
  if (state.terminal) throw new Error("AGENT_EVENT_AFTER_TERMINAL");
  if (event.sequence !== state.lastSequence + 1) throw new Error("AGENT_EVENT_SEQUENCE_GAP");
  const next = { ...state, lastSequence: event.sequence, runId: event.runId, messageId: event.messageId,
    lastOccurredAt: event.occurredAt, references: [...state.references] };
  switch (event.eventType) {
    case "RUN_ACCEPTED": return next;
    case "STAGE_CHANGED": return { ...next, stage: event.payload.message };
    case "TEXT_DELTA": return { ...next, answer: next.answer + event.payload.delta };
    case "CLARIFICATION_REQUIRED": return { ...next, answer: next.answer + event.payload.question };
    case "REFERENCE":
      return next.references.some(item => item.referenceId === event.payload.reference.referenceId)
        ? next : { ...next, references: [...next.references, event.payload.reference] };
    case "PARTIAL_CHECKPOINT": return { ...next, answer: event.payload.text, partial: true };
    case "HEARTBEAT": return { ...next, stage: next.stage || event.payload.stage };
    case "RUN_ERROR": return { ...next, error: event.payload.error.message };
    case "FINAL_RESULT":
      return { ...next, terminal: true,
        answer: event.payload.finalAnswer ?? next.answer,
        references: event.payload.references ?? next.references,
        partial: event.payload.answerCompleteness === "INTERRUPTED",
        error: event.payload.error?.message ?? next.error,
        pendingPracticeAction: event.payload.pendingPracticeAction ?? null };
  }
}

/** 解析 fetch 响应中的 UTF-8 SSE，仅把每个 data JSON 交给生成联合类型消费者。 */
export async function consumeAgentSse(response: Response,
    onEvent: (event: AgentClientStreamEvent) => void, signal: AbortSignal): Promise<void> {
  if (!response.ok || !response.body) throw new Error(`AGENT_STREAM_${response.status}`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
        const data = block.split("\n").filter(line => line.startsWith("data:"))
          .map(line => line.slice(5).trimStart()).join("\n");
        if (data) onEvent(JSON.parse(data) as AgentClientStreamEvent);
        boundary = buffer.indexOf("\n\n");
      }
    }
    if (!signal.aborted && buffer.trim()) throw new Error("AGENT_STREAM_TRUNCATED");
  } finally { reader.releaseLock(); }
}
