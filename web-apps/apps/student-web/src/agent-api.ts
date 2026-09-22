import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { AgentConversation, AgentConversationPage, AgentExplanationPreference, AgentMessagePage,
  AgentRunEventPage, CancelAgentRunResponse, CancelPendingPracticeActionRequest,
  ConfirmPendingPracticeActionRequest, GatewayClient, MediaAccessGrant, PendingPracticeAction,
  SendAgentMessageRequest } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { authStore } from "./auth";

export interface StudentAgentApi {
  conversations(page?: number, size?: number, signal?: AbortSignal): Promise<AgentConversationPage>;
  createConversation(clientRequestId: string, entryPoint?: AgentConversation["entryPoint"],
    signal?: AbortSignal): Promise<AgentConversation>;
  conversation(id: string, signal?: AbortSignal): Promise<AgentConversation>;
  renameConversation(id: string, title: string, expectedRevision: number, requestId: string,
    signal?: AbortSignal): Promise<AgentConversation>;
  archiveConversation(id: string, expectedRevision: number, requestId: string,
    signal?: AbortSignal): Promise<void>;
  messages(id: string, page?: number, size?: number, signal?: AbortSignal): Promise<AgentMessagePage>;
  preference(signal?: AbortSignal): Promise<AgentExplanationPreference>;
  updatePreference(value: AgentExplanationPreference["value"], revision: number,
    clientRequestId: string): Promise<AgentExplanationPreference>;
  events(runId: string, afterSequence?: number, signal?: AbortSignal): Promise<AgentRunEventPage>;
  cancel(runId: string, reason: "USER_STOP" | "CLIENT_DISCONNECTED" | "PAGE_CLOSED",
    signal?: AbortSignal): Promise<CancelAgentRunResponse>;
  send(conversationId: string, body: SendAgentMessageRequest, signal: AbortSignal): Promise<Response>;
  regenerate(conversationId: string, messageId: string, clientRequestId: string,
    signal: AbortSignal): Promise<Response>;
  messageQuestionMediaGrant(conversationId: string, messageId: string, mediaId: string,
    signal?: AbortSignal): Promise<MediaAccessGrant>;
  pendingPracticeActionForConversation(conversationId: string, signal?: AbortSignal): Promise<PendingPracticeAction>;
  pendingPracticeAction(actionId: string, signal?: AbortSignal): Promise<PendingPracticeAction>;
  confirmPendingPracticeAction(actionId: string, body: ConfirmPendingPracticeActionRequest,
    signal?: AbortSignal): Promise<PendingPracticeAction>;
  cancelPendingPracticeAction(actionId: string, body: CancelPendingPracticeActionRequest,
    signal?: AbortSignal): Promise<PendingPracticeAction>;
}

/** 学生独立页与练习悬浮答疑共用的 API，只以生成路径/DTO 调用 Gateway；流响应也执行一次 401 刷新。 */
export function createStudentAgentApi(client: GatewayClient = createGatewayClient(), store: AuthStore = authStore): StudentAgentApi {
  const authorized = <T extends { response: Response }>(fn: (authorization: string) => Promise<T>) => store.authorizedRequest(fn);
  const data = <T>(result: { data?: T; error?: unknown; response: Response }): T => {
    if (!result.data) throw gatewayRequestError(result.response, result.error as never); return result.data;
  };
  return {
    async conversations(page = 1, size = 30, signal) { return data(await authorized(Authorization => client.GET("/api/v1/ai/conversations", { headers: { Authorization }, params: { query: { page, size } }, signal }))); },
    async createConversation(clientRequestId, entryPoint = "INDEPENDENT_CHAT", signal) { return data(await authorized(Authorization => client.POST("/api/v1/ai/conversations", { headers: { Authorization }, body: { clientRequestId, entryPoint }, signal }))); },
    async conversation(conversationId, signal) { return data(await authorized(Authorization => client.GET("/api/v1/ai/conversations/{conversationId}", { headers: { Authorization }, params: { path: { conversationId } }, signal }))); },
    async renameConversation(conversationId, title, expectedRevision, requestId, signal) { return data(await authorized(Authorization => client.PATCH("/api/v1/ai/conversations/{conversationId}", { headers: { Authorization }, params: { path: { conversationId } }, body: { title, expectedRevision, requestId }, signal }))); },
    async archiveConversation(conversationId, expectedRevision, requestId, signal) { const result = await authorized(Authorization => client.DELETE("/api/v1/ai/conversations/{conversationId}", { headers: { Authorization }, params: { path: { conversationId } }, body: { expectedRevision, requestId }, signal })); if (!result.response.ok) throw gatewayRequestError(result.response, result.error as never); },
    async messages(conversationId, page = 1, size = 100, signal) { return data(await authorized(Authorization => client.GET("/api/v1/ai/conversations/{conversationId}/messages", { headers: { Authorization }, params: { path: { conversationId }, query: { page, size } }, signal }))); },
    async preference(signal) { return data(await authorized(Authorization => client.GET("/api/v1/ai/preferences/explanation", { headers: { Authorization }, signal }))); },
    async updatePreference(value, expectedRevision, clientRequestId) { return data(await authorized(Authorization => client.PUT("/api/v1/ai/preferences/explanation", { headers: { Authorization }, body: { value, expectedRevision, clientRequestId } }))); },
    async events(runId, afterSequence, signal) { return data(await authorized(Authorization => client.GET("/api/v1/ai/runs/{runId}/events", { headers: { Authorization }, params: { path: { runId }, query: { afterSequence } }, signal }))); },
    async cancel(runId, reason, signal) { return data(await authorized(Authorization => client.POST("/api/v1/ai/runs/{runId}/cancel", { headers: { Authorization }, params: { path: { runId } }, body: { cancelRequestId: crypto.randomUUID(), reason }, signal }))); },
    async send(conversationId, body, signal) { const result = await authorized(Authorization => client.POST("/api/v1/ai/conversations/{conversationId}/messages", { headers: { Authorization }, params: { path: { conversationId } }, body, parseAs: "stream", signal })); if (!result.response.ok) throw gatewayRequestError(result.response, result.error as never); return result.response; },
    async regenerate(conversationId, messageId, clientRequestId, signal) { const result = await authorized(Authorization => client.POST("/api/v1/ai/conversations/{conversationId}/messages/{messageId}/regenerations", { headers: { Authorization }, params: { path: { conversationId, messageId } }, body: { clientRequestId }, parseAs: "stream", signal })); if (!result.response.ok) throw gatewayRequestError(result.response, result.error as never); return result.response; },
    async messageQuestionMediaGrant(conversationId, messageId, mediaId, signal) {
      return data(await authorized(Authorization => client.POST("/api/v1/ai/conversations/{conversationId}/messages/{messageId}/question-media/{mediaId}/access-grants", {
        headers: { Authorization }, params: { path: { conversationId, messageId, mediaId } },
        body: { variant: "ORIGINAL", disposition: "INLINE" }, signal
      })));
    },
    async pendingPracticeActionForConversation(conversationId, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/ai/conversations/{conversationId}/pending-practice-action", {
        headers: { Authorization }, params: { path: { conversationId }, query: { requestId: crypto.randomUUID() } }, signal
      })));
    },
    async pendingPracticeAction(actionId, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/ai/pending-practice-actions/{actionId}", {
        headers: { Authorization }, params: { path: { actionId }, query: { requestId: crypto.randomUUID() } }, signal
      })));
    },
    async confirmPendingPracticeAction(actionId, body, signal) {
      return data(await authorized(Authorization => client.POST("/api/v1/ai/pending-practice-actions/{actionId}:confirm", {
        headers: { Authorization }, params: { path: { actionId } }, body, signal
      })));
    },
    async cancelPendingPracticeAction(actionId, body, signal) {
      return data(await authorized(Authorization => client.POST("/api/v1/ai/pending-practice-actions/{actionId}:cancel", {
        headers: { Authorization }, params: { path: { actionId } }, body, signal
      })));
    }
  };
}

export const studentAgentApi = createStudentAgentApi();
