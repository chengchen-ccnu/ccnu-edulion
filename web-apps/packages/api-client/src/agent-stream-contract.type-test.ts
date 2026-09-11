import type { components } from "./generated/schema";

/**
 * 编译期证明生成客户端可直接按 eventType 收窄九类 payload。
 * 本文件不在浏览器执行；OpenAPI 若退化为无类型 object，TypeScript typecheck 会失败。
 */
export function readAgentEvent(event: components["schemas"]["AgentClientStreamEvent"]): string {
  switch (event.eventType) {
    case "RUN_ACCEPTED":
      return event.payload.conversationId;
    case "STAGE_CHANGED":
      return `${event.payload.stage}:${event.payload.message}`;
    case "TEXT_DELTA":
      return event.payload.delta;
    case "CLARIFICATION_REQUIRED":
      return `${event.payload.question}:${event.payload.missingSlots.join(",")}`;
    case "REFERENCE":
      return event.payload.reference.appRoute ?? event.payload.reference.title;
    case "PARTIAL_CHECKPOINT":
      return `${event.payload.text}:${event.payload.persisted}`;
    case "HEARTBEAT":
      return event.payload.stage;
    case "RUN_ERROR":
      return `${event.payload.error.code}:${event.payload.error.retryable}:${event.payload.error.traceId}`;
    case "FINAL_RESULT":
      return `${event.payload.terminalStatus}:${event.payload.answerCompleteness}:${event.payload.finalAnswer ?? ""}`;
  }
}
