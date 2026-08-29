import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type {
  AttemptDraft, AttemptResult, GatewayClient, LearnerAssessment, LearnerAssessmentPage,
  ListLearnerAssessmentsQuery, ListWrongQuestionsQuery, MediaAccessGrant, RedoWrongQuestionRequest,
  SaveAttemptDraftRequest, StartAttemptRequest, SubmitAttemptRequest, UpdateWrongQuestionMasteryRequest,
  WrongQuestion, WrongQuestionPage, WrongQuestionRedoResult
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/** 学生测评 API 边界：所有 DTO 来自 OpenAPI，浏览器只携带 Gateway Bearer，不接触领域服务或存储地址。 */
export interface StudentAssessmentApi {
  list(query: ListLearnerAssessmentsQuery, signal?: AbortSignal): Promise<LearnerAssessmentPage>;
  detail(assessmentId: string, signal?: AbortSignal): Promise<LearnerAssessment>;
  start(assessmentId: string, body: StartAttemptRequest, signal?: AbortSignal): Promise<AttemptDraft>;
  draft(attemptId: string, signal?: AbortSignal): Promise<AttemptDraft>;
  save(attemptId: string, body: SaveAttemptDraftRequest, signal?: AbortSignal): Promise<AttemptDraft>;
  submit(attemptId: string, body: SubmitAttemptRequest, signal?: AbortSignal): Promise<AttemptResult>;
  result(attemptId: string, signal?: AbortSignal): Promise<AttemptResult>;
  wrongQuestions(query: ListWrongQuestionsQuery, signal?: AbortSignal): Promise<WrongQuestionPage>;
  wrongQuestion(wrongQuestionId: string, signal?: AbortSignal): Promise<WrongQuestion>;
  updateMastery(wrongQuestionId: string, body: UpdateWrongQuestionMasteryRequest,
    signal?: AbortSignal): Promise<WrongQuestion>;
  redo(wrongQuestionId: string, body: RedoWrongQuestionRequest,
    signal?: AbortSignal): Promise<WrongQuestionRedoResult>;
  mediaGrant(assessmentId: string, mediaId: string, disposition: "INLINE" | "ATTACHMENT",
    signal?: AbortSignal): Promise<MediaAccessGrant>;
}

/** 构造测评适配器；401 由共享认证层最多刷新一次，其余失败保留状态码、字段错误和 Retry-After。 */
export function createStudentAssessmentApi(store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): StudentAssessmentApi {
  async function request<T>(call: (authorization: string) => Promise<{ data?: T; error?: unknown; response: Response }>) {
    const result = await store.authorizedRequest(call);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
    return result.data;
  }
  return {
    list(query, signal) {
      return request(Authorization => client.GET("/api/v1/learning/assessments", {
        headers: { Authorization }, params: { query }, signal
      }));
    },
    detail(assessmentId, signal) {
      return request(Authorization => client.GET("/api/v1/learning/assessments/{assessmentId}", {
        headers: { Authorization }, params: { path: { assessmentId } }, signal
      }));
    },
    start(assessmentId, body, signal) {
      return request(Authorization => client.POST("/api/v1/learning/assessments/{assessmentId}/attempts", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      }));
    },
    draft(attemptId, signal) {
      return request(Authorization => client.GET("/api/v1/learning/attempts/{attemptId}/draft", {
        headers: { Authorization }, params: { path: { attemptId } }, signal
      }));
    },
    save(attemptId, body, signal) {
      return request(Authorization => client.PUT("/api/v1/learning/attempts/{attemptId}/draft", {
        headers: { Authorization }, params: { path: { attemptId } }, body, signal
      }));
    },
    submit(attemptId, body, signal) {
      return request(Authorization => client.POST("/api/v1/learning/attempts/{attemptId}/submission", {
        headers: { Authorization }, params: { path: { attemptId } }, body, signal
      }));
    },
    result(attemptId, signal) {
      return request(Authorization => client.GET("/api/v1/learning/attempts/{attemptId}/result", {
        headers: { Authorization }, params: { path: { attemptId } }, signal
      }));
    },
    wrongQuestions(query, signal) {
      return request(Authorization => client.GET("/api/v1/learning/wrong-questions", {
        headers: { Authorization }, params: { query }, signal
      }));
    },
    wrongQuestion(wrongQuestionId, signal) {
      return request(Authorization => client.GET("/api/v1/learning/wrong-questions/{wrongQuestionId}", {
        headers: { Authorization }, params: { path: { wrongQuestionId } }, signal
      }));
    },
    updateMastery(wrongQuestionId, body, signal) {
      return request(Authorization => client.PUT("/api/v1/learning/wrong-questions/{wrongQuestionId}/mastery", {
        headers: { Authorization }, params: { path: { wrongQuestionId } }, body, signal
      }));
    },
    redo(wrongQuestionId, body, signal) {
      return request(Authorization => client.POST("/api/v1/learning/wrong-questions/{wrongQuestionId}/redos", {
        headers: { Authorization }, params: { path: { wrongQuestionId } }, body, signal
      }));
    },
    mediaGrant(assessmentId, mediaId, disposition, signal) {
      return request(Authorization => client.POST(
        "/api/v1/learning/assessments/{assessmentId}/media/{mediaId}/access-grants", {
          headers: { Authorization }, params: { path: { assessmentId, mediaId } },
          body: { variant: "ORIGINAL", disposition }, signal
        }));
    }
  };
}

export const studentAssessmentApi = createStudentAssessmentApi();
