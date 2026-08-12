import createClient from "openapi-fetch";

import type { components, paths } from "./generated/schema";

export const DEFAULT_MOCK_BASE_URL = "http://127.0.0.1:4010";
const runtimeEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
export const DEFAULT_GATEWAY_BASE_URL = runtimeEnv?.VITE_GATEWAY_BASE_URL ?? "http://localhost:8080";

/**
 * 创建唯一 Gateway 客户端；``credentials: include`` 仅用于浏览器自动携带 HttpOnly 刷新 Cookie。
 * 所有路径与 DTO 继续由 OpenAPI 生成，本适配层不维护第二套字段定义。
 */
export function createGatewayClient(baseUrl = DEFAULT_GATEWAY_BASE_URL) {
  return createClient<paths>({ baseUrl, credentials: "include" });
}

export type GatewayPaths = paths;
export type GatewayClient = ReturnType<typeof createGatewayClient>;
export type CurrentUser = components["schemas"]["CurrentUser"];
export type ApiError = components["schemas"]["ApiError"];
export type CategorySummary = components["schemas"]["CategorySummary"];
export type CoursePage = components["schemas"]["CoursePage"];
export type CourseSummary = components["schemas"]["CourseSummary"];
export type CourseDetail = components["schemas"]["CourseDetail"];
export type CourseLevel = components["schemas"]["CourseLevel"];
export type CourseAccessType = components["schemas"]["CourseAccessType"];
export type CoursePublicationStatus = components["schemas"]["CoursePublicationStatus"];
export type TeacherCourse = components["schemas"]["TeacherCourse"];
export type TeacherCoursePage = components["schemas"]["TeacherCoursePage"];
export type CoursePublication = components["schemas"]["CoursePublication"];
export type CreateCourseRequest = components["schemas"]["CreateCourseRequest"];
export type UpdateCourseRequest = components["schemas"]["UpdateCourseRequest"];
export type ReplaceCurriculumRequest = components["schemas"]["ReplaceCurriculumRequest"];
export type ChapterInput = components["schemas"]["ChapterInput"];
export type LessonInput = components["schemas"]["LessonInput"];
export type UpdatePublicationRequest = components["schemas"]["UpdatePublicationRequest"];
export type LessonMediaResource = components["schemas"]["LessonMediaResource"];
export type LessonMediaResourceInput = components["schemas"]["LessonMediaResourceInput"];
export type MediaPurpose = components["schemas"]["MediaPurpose"];
export type MediaUploadStatus = components["schemas"]["MediaUploadStatus"];
export type MediaAssetStatus = components["schemas"]["MediaAssetStatus"];
export type CreateMediaUploadRequest = components["schemas"]["CreateMediaUploadRequest"];
export type AuthorizeMediaPartRequest = components["schemas"]["AuthorizeMediaPartRequest"];
export type CompleteMediaUploadRequest = components["schemas"]["CompleteMediaUploadRequest"];
export type CompletedMediaPart = components["schemas"]["CompletedMediaPart"];
export type MediaUploadGrant = components["schemas"]["MediaUploadGrant"];
export type MediaUploadSession = components["schemas"]["MediaUploadSession"];
export type MediaAsset = components["schemas"]["MediaAsset"];
export type CourseMediaPlaybackGrant = components["schemas"]["CourseMediaPlaybackGrant"];
export type KnowledgePoint = components["schemas"]["KnowledgePoint"];
export type QuestionType = components["schemas"]["QuestionType"];
export type QuestionInputType = components["schemas"]["QuestionInputType"];
export type QuestionDifficulty = components["schemas"]["QuestionDifficulty"];
export type QuestionStatus = components["schemas"]["QuestionStatus"];
export type QuestionMediaPurpose = components["schemas"]["QuestionMediaPurpose"];
export type QuestionMediaInput = components["schemas"]["QuestionMediaInput"];
export type QuestionVersionInput = components["schemas"]["QuestionVersionInput"];
export type QuestionChildInput = components["schemas"]["QuestionChildInput"];
export type CreateQuestionRequest = components["schemas"]["CreateQuestionRequest"];
export type UpdateQuestionRequest = components["schemas"]["UpdateQuestionRequest"];
export type UpdateQuestionStatusRequest = components["schemas"]["UpdateQuestionStatusRequest"];
export type QuestionPage = components["schemas"]["QuestionPage"];
export type TeacherQuestion = components["schemas"]["TeacherQuestion"];
export type QuestionVersion = components["schemas"]["QuestionVersion"];
export type QuestionVersionPage = components["schemas"]["QuestionVersionPage"];
export type QuestionImportTask = components["schemas"]["QuestionImportTask"];
export type QuestionImportPage = components["schemas"]["QuestionImportPage"];
export type QuestionImportSourceFormat = components["schemas"]["QuestionImportSourceFormat"];
export type QuestionImportItemOutcome = components["schemas"]["QuestionImportItemOutcome"];
export type CreateQuestionImportRequest = components["schemas"]["CreateQuestionImportRequest"];
export type CreateLegacyQuestionImportRequest = components["schemas"]["CreateLegacyQuestionImportRequest"];
export type LegacyQuestionImportItem = components["schemas"]["LegacyQuestionImportItem"];
export type LegacyQuestionImportBatchRequest = components["schemas"]["LegacyQuestionImportBatchRequest"];
export type LegacyQuestionImportBatchResult = components["schemas"]["LegacyQuestionImportBatchResult"];
export type LegacyQuestionImportReviewItem = components["schemas"]["LegacyQuestionImportReviewItem"];
export type LegacyQuestionImportItemPage = components["schemas"]["LegacyQuestionImportItemPage"];
export type ResolveLegacyQuestionImportItemRequest = components["schemas"]["ResolveLegacyQuestionImportItemRequest"];
export type LegacyQuestionImportCleanupResult = components["schemas"]["LegacyQuestionImportCleanupResult"];
export type MediaAccessGrant = components["schemas"]["MediaAccessGrant"];
export type ListCoursesQuery = NonNullable<paths["/api/v1/courses"]["get"]["parameters"]["query"]>;
export type ListTeacherCoursesQuery = NonNullable<paths["/api/v1/teacher/courses"]["get"]["parameters"]["query"]>;
export type ListTeacherQuestionsQuery = NonNullable<paths["/api/v1/teacher/questions"]["get"]["parameters"]["query"]>;
export type ListTeacherQuestionVersionsQuery = NonNullable<paths["/api/v1/teacher/questions/{questionId}/versions"]["get"]["parameters"]["query"]>;
export type ListQuestionImportsQuery = NonNullable<paths["/api/v1/teacher/question-imports"]["get"]["parameters"]["query"]>;
export type ListLegacyQuestionImportItemsQuery = NonNullable<paths["/api/v1/teacher/question-imports/{importTaskId}/items"]["get"]["parameters"]["query"]>;

/** Gateway HTTP 状态、稳定错误码和按字段错误的人工维护异常投影。 */
export class GatewayRequestError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: Record<string, string>;

  constructor(response: Response, error?: ApiError) {
    super(error?.message ?? `Gateway request failed with status ${response.status}`);
    this.name = "GatewayRequestError";
    this.status = response.status;
    this.code = error?.code ?? "COMMON.UNEXPECTED_ERROR";
    this.fieldErrors = Object.fromEntries((error?.fieldErrors ?? []).map(item => [item.field, item.reason]));
  }
}

export function gatewayRequestError(response: Response, error?: ApiError): GatewayRequestError {
  return new GatewayRequestError(response, error);
}
