import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { GatewayClient, ListSystemQuestionsQuery, MediaAccessGrant, SystemQuestion,
  SystemQuestionPage } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/**
 * 学生公共题 API 边界。列表、详情和媒体均经 Gateway 调用 T10C.1 安全投影；
 * 页面不能使用教师题库或通用媒体授权，也不会在读取时创建作答事实。
 */
export interface StudentQuestionApi {
  list(query: ListSystemQuestionsQuery, signal?: AbortSignal): Promise<SystemQuestionPage>;
  detail(questionId: string, signal?: AbortSignal): Promise<SystemQuestion>;
  mediaGrant(questionId: string, mediaId: string, disposition: "INLINE" | "ATTACHMENT",
    signal?: AbortSignal): Promise<MediaAccessGrant>;
}

/** 构造公共题适配器；401 由共享认证层处理，其余 HTTP 状态保留给页面形成可恢复反馈。 */
export function createStudentQuestionApi(store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): StudentQuestionApi {
  async function request<T>(call: (authorization: string) => Promise<{ data?: T; error?: unknown; response: Response }>) {
    const result = await store.authorizedRequest(call);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
    return result.data;
  }
  return {
    list(query, signal) {
      return request(Authorization => client.GET("/api/v1/learning/system-questions", {
        headers: { Authorization }, params: { query }, signal
      }));
    },
    detail(questionId, signal) {
      return request(Authorization => client.GET("/api/v1/learning/system-questions/{questionId}", {
        headers: { Authorization }, params: { path: { questionId } }, signal
      }));
    },
    mediaGrant(questionId, mediaId, disposition, signal) {
      return request(Authorization => client.POST(
        "/api/v1/learning/system-questions/{questionId}/media/{mediaId}/access-grants", {
          headers: { Authorization }, params: { path: { questionId, mediaId } },
          body: { variant: "ORIGINAL", disposition }, signal
        }));
    }
  };
}

export const studentQuestionApi = createStudentQuestionApi();
