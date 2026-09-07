import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type {
  AssessmentPage, AssessmentPublicationCommandResult, AttemptResult, CloseAssessmentRequest, CloseAssessmentResult,
  CreateAssessmentRequest, GatewayClient, ListAssessmentResultsQuery, ListTeacherAssessmentsQuery,
  ManualGradeRequest, PublishAssessmentResultRequest, ReplaceAssessmentQuestionsRequest, TeacherAssessment,
  TeacherAttemptResultPage, UpdateAssessmentPublicationRequest, UpdateAssessmentRequest
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/** 教师测评 API 边界；课程所有权、revision、关闭和公布资格完全由服务端裁决。 */
export interface TeacherAssessmentApi {
  list(query: ListTeacherAssessmentsQuery, signal?: AbortSignal): Promise<AssessmentPage>;
  create(body: CreateAssessmentRequest, signal?: AbortSignal): Promise<TeacherAssessment>;
  detail(assessmentId: string, signal?: AbortSignal): Promise<TeacherAssessment>;
  update(assessmentId: string, body: UpdateAssessmentRequest, signal?: AbortSignal): Promise<TeacherAssessment>;
  remove(assessmentId: string, signal?: AbortSignal): Promise<void>;
  replaceQuestions(assessmentId: string, body: ReplaceAssessmentQuestionsRequest,
    signal?: AbortSignal): Promise<TeacherAssessment>;
  publication(assessmentId: string, body: UpdateAssessmentPublicationRequest,
    signal?: AbortSignal): Promise<TeacherAssessment>;
  close(assessmentId: string, body: CloseAssessmentRequest, signal?: AbortSignal): Promise<CloseAssessmentResult>;
  publishGrades(assessmentId: string, body: PublishAssessmentResultRequest,
    signal?: AbortSignal): Promise<AssessmentPublicationCommandResult>;
  publishAnswers(assessmentId: string, body: PublishAssessmentResultRequest,
    signal?: AbortSignal): Promise<AssessmentPublicationCommandResult>;
  results(assessmentId: string, query: ListAssessmentResultsQuery,
    signal?: AbortSignal): Promise<TeacherAttemptResultPage>;
  /** 读取批改深链指定的最终有效 attempt；不经过整场 results 分页门禁。 */
  getTeacherAttemptResult(attemptId: string, signal?: AbortSignal): Promise<AttemptResult>;
  grade(attemptId: string, body: ManualGradeRequest, signal?: AbortSignal): Promise<AttemptResult>;
}

/** 构造教师测评适配器；204 删除转换为 void，其他响应仍由生成路径类型约束。 */
export function createTeacherAssessmentApi(store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): TeacherAssessmentApi {
  async function request<T>(call: (authorization: string) => Promise<{ data?: T; error?: unknown; response: Response }>) {
    const result = await store.authorizedRequest(call);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
    return result.data;
  }
  return {
    list: (query, signal) => request(Authorization => client.GET("/api/v1/teacher/assessments", {
      headers: { Authorization }, params: { query }, signal
    })),
    create: (body, signal) => request(Authorization => client.POST("/api/v1/teacher/assessments", {
      headers: { Authorization }, body, signal
    })),
    detail: (assessmentId, signal) => request(Authorization => client.GET(
      "/api/v1/teacher/assessments/{assessmentId}", {
        headers: { Authorization }, params: { path: { assessmentId } }, signal
      })),
    update: (assessmentId, body, signal) => request(Authorization => client.PUT(
      "/api/v1/teacher/assessments/{assessmentId}", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      })),
    async remove(assessmentId, signal) {
      const result = await store.authorizedRequest(Authorization => client.DELETE(
        "/api/v1/teacher/assessments/{assessmentId}", {
          headers: { Authorization }, params: { path: { assessmentId } }, signal
        }));
      if (result.response.status !== 204) throw gatewayRequestError(result.response, result.error as never);
    },
    replaceQuestions: (assessmentId, body, signal) => request(Authorization => client.PUT(
      "/api/v1/teacher/assessments/{assessmentId}/questions", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      })),
    publication: (assessmentId, body, signal) => request(Authorization => client.PUT(
      "/api/v1/teacher/assessments/{assessmentId}/publication", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      })),
    close: (assessmentId, body, signal) => request(Authorization => client.POST(
      "/api/v1/teacher/assessments/{assessmentId}/closure", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      })),
    publishGrades: (assessmentId, body, signal) => request(Authorization => client.POST(
      "/api/v1/teacher/assessments/{assessmentId}/grade-publication", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      })),
    publishAnswers: (assessmentId, body, signal) => request(Authorization => client.POST(
      "/api/v1/teacher/assessments/{assessmentId}/answer-publication", {
        headers: { Authorization }, params: { path: { assessmentId } }, body, signal
      })),
    results: (assessmentId, query, signal) => request(Authorization => client.GET(
      "/api/v1/teacher/assessments/{assessmentId}/results", {
        headers: { Authorization }, params: { path: { assessmentId }, query }, signal
      })),
    getTeacherAttemptResult: (attemptId, signal) => request(Authorization => client.GET(
      "/api/v1/teacher/attempts/{attemptId}", {
        headers: { Authorization }, params: { path: { attemptId } }, signal
      })),
    grade: (attemptId, body, signal) => request(Authorization => client.POST(
      "/api/v1/teacher/attempts/{attemptId}/grades", {
        headers: { Authorization }, params: { path: { attemptId } }, body, signal
      }))
  };
}

export const teacherAssessmentApi = createTeacherAssessmentApi();
