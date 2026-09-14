import type { AgentClientStreamEvent } from "@edu/api-client";
import { describe, expect, it } from "vitest";
import { consumeAgentSse, emptyAgentStreamState, reduceAgentEvent } from "./agent-stream";

const base = { runId: "run-1", messageId: "message-1", occurredAt: "2026-09-11T00:00:00Z" };
describe("T15A Agent stream", () => {
  it("reduces all nine generated event variants and keeps authorized sources", () => {
    const events: AgentClientStreamEvent[] = [
      { ...base, sequence: 1, eventType: "RUN_ACCEPTED", payload: { conversationId: "conversation-1", messageId: "message-1", replayed: false } },
      { ...base, sequence: 2, eventType: "STAGE_CHANGED", payload: { stage: "UNDERSTANDING", message: "正在理解" } },
      { ...base, sequence: 3, eventType: "TEXT_DELTA", payload: { delta: "设 $x=1$" } },
      { ...base, sequence: 4, eventType: "CLARIFICATION_REQUIRED", payload: { question: "请补充条件", missingSlots: ["problemText"] } },
      { ...base, sequence: 5, eventType: "REFERENCE", payload: { reference: { referenceId: "ref-1", sourceType: "KNOWLEDGE_POINT", title: "方程", accessDecision: "AUTHORIZED_BY_JAVA" } } },
      { ...base, sequence: 6, eventType: "PARTIAL_CHECKPOINT", payload: { text: "已保存片段", complete: false, persisted: true } },
      { ...base, sequence: 7, eventType: "HEARTBEAT", payload: { stage: "EXPLAINING" } },
      { ...base, sequence: 8, eventType: "RUN_ERROR", payload: { error: { code: "TEMP", message: "暂时失败", retryable: true, traceId: "trace-1" }, terminal: false } },
      { ...base, sequence: 9, eventType: "FINAL_RESULT", payload: { terminal: true, terminalStatus: "COMPLETED", finalAnswer: "最终答案", answerCompleteness: "COMPLETE", references: [],
        pendingPracticeAction: { actionId: "action-1", conversationId: "conversation-1",
          criteria: { topic: "方程", difficulty: "MEDIUM", requestedCount: 5 }, requestedCount: 5,
          actualCount: 4, candidateQuestions: [], status: "PENDING", revision: 0, expiresAt: "2026-09-11T00:10:00Z" } } }
    ];
    const result = events.reduce(reduceAgentEvent, emptyAgentStreamState());
    expect(result).toMatchObject({ answer: "最终答案", terminal: true, lastSequence: 9, partial: false,
      pendingPracticeAction: { actionId: "action-1", actualCount: 4 } });
  });

  it("rejects gaps and events after the unique terminal", () => {
    const first = reduceAgentEvent(emptyAgentStreamState(), { ...base, sequence: 1, eventType: "FINAL_RESULT", payload: { terminal: true, terminalStatus: "CANCELLED", answerCompleteness: "INTERRUPTED", interruptionNotice: "回答已中断" } });
    expect(() => reduceAgentEvent(first, { ...base, sequence: 2, eventType: "HEARTBEAT", payload: { stage: "FINALIZING" } })).toThrow("AFTER_TERMINAL");
    expect(() => reduceAgentEvent(emptyAgentStreamState(), { ...base, sequence: 2, eventType: "TEXT_DELTA", payload: { delta: "x" } })).toThrow("SEQUENCE_GAP");
  });

  it("parses chunked CRLF SSE without inventing another event schema", async () => {
    const encoder = new TextEncoder(); const payload = JSON.stringify({ ...base, sequence: 1, eventType: "HEARTBEAT", payload: { stage: "PLANNING" } });
    const body = new ReadableStream({ start(controller) { controller.enqueue(encoder.encode(`data: ${payload.slice(0, 20)}`)); controller.enqueue(encoder.encode(`${payload.slice(20)}\r\n\r\n`)); controller.close(); } });
    const received: AgentClientStreamEvent[] = []; await consumeAgentSse(new Response(body), event => received.push(event), new AbortController().signal);
    expect(received).toHaveLength(1); expect(received[0]?.eventType).toBe("HEARTBEAT");
  });
});
