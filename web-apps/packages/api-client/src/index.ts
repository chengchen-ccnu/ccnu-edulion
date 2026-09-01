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
export type ArithmeticCaptcha = components["schemas"]["ArithmeticCaptcha"];
export type ArithmeticCaptchaAnswer = components["schemas"]["ArithmeticCaptchaAnswer"];
export type AuthenticationChannelCapabilities = components["schemas"]["AuthenticationChannelCapabilities"];
export type AuthenticationChannelCapability = components["schemas"]["AuthenticationChannelCapability"];
export type IdentityType = components["schemas"]["IdentityType"];
export type VerificationPurpose = components["schemas"]["VerificationPurpose"];
export type VerificationChallenge = components["schemas"]["VerificationChallenge"];
export type CurrentProtocolDocuments = components["schemas"]["CurrentProtocolDocuments"];
export type ProtocolDocumentType = components["schemas"]["ProtocolDocumentType"];
export type ProtocolAgreementInput = components["schemas"]["ProtocolAgreementInput"];
export type RegisterStudentRequest = components["schemas"]["RegisterStudentRequest"];
export type RegistrationResult = components["schemas"]["RegistrationResult"];
export type ResetPasswordRequest = components["schemas"]["ResetPasswordRequest"];
export type ChangePasswordRequest = components["schemas"]["ChangePasswordRequest"];
export type AccountSession = components["schemas"]["AccountSession"];
export type VerifiedIdentityCollection = components["schemas"]["VerifiedIdentityCollection"];
export type CreateIdentityVerificationChallengeRequest = components["schemas"]["CreateIdentityVerificationChallengeRequest"];
export type BindIdentityRequest = components["schemas"]["BindIdentityRequest"];
export type ReplaceIdentityRequest = components["schemas"]["ReplaceIdentityRequest"];
export type UnbindIdentityRequest = components["schemas"]["UnbindIdentityRequest"];
export type IdentityMutationResult = components["schemas"]["IdentityMutationResult"];
export type SubmitTeacherApplicationRequest = components["schemas"]["SubmitTeacherApplicationRequest"];
export type TeacherApplication = components["schemas"]["TeacherApplication"];
export type LoginResponse = components["schemas"]["LoginResponse"];
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
export type MediaAssetScope = components["schemas"]["MediaAssetScope"];
export type CreateMediaUploadRequest = components["schemas"]["CreateMediaUploadRequest"];
export type AuthorizeMediaPartRequest = components["schemas"]["AuthorizeMediaPartRequest"];
export type CompleteMediaUploadRequest = components["schemas"]["CompleteMediaUploadRequest"];
export type CompletedMediaPart = components["schemas"]["CompletedMediaPart"];
export type MediaUploadGrant = components["schemas"]["MediaUploadGrant"];
export type MediaUploadSession = components["schemas"]["MediaUploadSession"];
export type MediaAsset = components["schemas"]["MediaAsset"];
export type CourseMediaPlaybackGrant = components["schemas"]["CourseMediaPlaybackGrant"];
export type CourseMediaAccessGrant = components["schemas"]["CourseMediaAccessGrant"];
export type CourseResourceAction = components["schemas"]["CourseResourceAction"];
export type CreateCourseResourceAccessGrantRequest = components["schemas"]["CreateCourseResourceAccessGrantRequest"];
export type LessonContent = components["schemas"]["LessonContent"];
export type LessonInlineImage = components["schemas"]["LessonInlineImage"];
export type LessonContentResource = components["schemas"]["LessonContentResource"];
export type KnowledgePoint = components["schemas"]["KnowledgePoint"];
export type KnowledgePointSearchItem = components["schemas"]["KnowledgePointSearchItem"];
export type KnowledgePointSearchPage = components["schemas"]["KnowledgePointSearchPage"];
export type KnowledgePointGraph = components["schemas"]["KnowledgePointGraph"];
export type QuestionStage = components["schemas"]["QuestionStage"];
export type QuestionGrade = components["schemas"]["QuestionGrade"];
export type QuestionType = components["schemas"]["QuestionType"];
export type QuestionInputType = components["schemas"]["QuestionInputType"];
export type QuestionDifficulty = components["schemas"]["QuestionDifficulty"];
export type QuestionStatus = components["schemas"]["QuestionStatus"];
export type QuestionScope = components["schemas"]["QuestionScope"];
export type QuestionMediaPurpose = components["schemas"]["QuestionMediaPurpose"];
export type QuestionMediaInput = components["schemas"]["QuestionMediaInput"];
export type QuestionVersionInput = components["schemas"]["QuestionVersionInput"];
export type QuestionChildInput = components["schemas"]["QuestionChildInput"];
export type CreateQuestionRequest = components["schemas"]["CreateQuestionRequest"];
export type UpdateQuestionRequest = components["schemas"]["UpdateQuestionRequest"];
export type UpdateQuestionStatusRequest = components["schemas"]["UpdateQuestionStatusRequest"];
export type QuestionPage = components["schemas"]["QuestionPage"];
export type QuestionSummary = components["schemas"]["QuestionSummary"];
export type TeacherQuestion = components["schemas"]["TeacherQuestion"];
export type QuestionVersion = components["schemas"]["QuestionVersion"];
export type QuestionVersionPage = components["schemas"]["QuestionVersionPage"];
export type SystemQuestion = components["schemas"]["SystemQuestion"];
export type SystemQuestionPage = components["schemas"]["SystemQuestionPage"];
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
export type EnrollmentStatus = components["schemas"]["EnrollmentStatus"];
export type CreateEnrollmentRequest = components["schemas"]["CreateEnrollmentRequest"];
export type Enrollment = components["schemas"]["Enrollment"];
export type EnrollmentPage = components["schemas"]["EnrollmentPage"];
export type ReportLessonProgressRequest = components["schemas"]["ReportLessonProgressRequest"];
export type LessonProgress = components["schemas"]["LessonProgress"];
export type CourseProgress = components["schemas"]["CourseProgress"];
export type CompleteArticleLessonRequest = components["schemas"]["CompleteArticleLessonRequest"];
export type ArticleLessonCompletion = components["schemas"]["ArticleLessonCompletion"];
export type CreateLearningNoteRequest = components["schemas"]["CreateLearningNoteRequest"];
export type UpdateLearningNoteRequest = components["schemas"]["UpdateLearningNoteRequest"];
export type LearningNote = components["schemas"]["LearningNote"];
export type AssessmentType = components["schemas"]["AssessmentType"];
export type AssessmentStatus = components["schemas"]["AssessmentStatus"];
export type AssessmentSummary = components["schemas"]["AssessmentSummary"];
export type LearnerAssessmentState = components["schemas"]["LearnerAssessmentState"];
export type LearnerAssessmentSummary = components["schemas"]["LearnerAssessmentSummary"];
export type LearnerAssessmentPage = components["schemas"]["LearnerAssessmentPage"];
export type LearnerAssessmentQuestion = components["schemas"]["LearnerAssessmentQuestion"];
export type LearnerAssessment = components["schemas"]["LearnerAssessment"];
export type AttemptStatus = components["schemas"]["AttemptStatus"];
export type AttemptAnswerInput = components["schemas"]["AttemptAnswerInput"];
export type AttemptDraft = components["schemas"]["AttemptDraft"];
export type AttemptResult = components["schemas"]["AttemptResult"];
export type StartAttemptRequest = components["schemas"]["StartAttemptRequest"];
export type SaveAttemptDraftRequest = components["schemas"]["SaveAttemptDraftRequest"];
export type SubmitAttemptRequest = components["schemas"]["SubmitAttemptRequest"];
export type WrongQuestion = components["schemas"]["WrongQuestion"];
export type WrongQuestionPage = components["schemas"]["WrongQuestionPage"];
export type WrongQuestionMasteryStatus = components["schemas"]["WrongQuestionMasteryStatus"];
export type UpdateWrongQuestionMasteryRequest = components["schemas"]["UpdateWrongQuestionMasteryRequest"];
export type RedoWrongQuestionRequest = components["schemas"]["RedoWrongQuestionRequest"];
export type WrongQuestionRedoResult = components["schemas"]["WrongQuestionRedoResult"];
export type CreateAssessmentMediaAccessGrantRequest = components["schemas"]["CreateAssessmentMediaAccessGrantRequest"];
export type AssessmentPage = components["schemas"]["AssessmentPage"];
export type TeacherAssessment = components["schemas"]["TeacherAssessment"];
export type CreateAssessmentRequest = components["schemas"]["CreateAssessmentRequest"];
export type UpdateAssessmentRequest = components["schemas"]["UpdateAssessmentRequest"];
export type ReplaceAssessmentQuestionsRequest = components["schemas"]["ReplaceAssessmentQuestionsRequest"];
export type UpdateAssessmentPublicationRequest = components["schemas"]["UpdateAssessmentPublicationRequest"];
export type CloseAssessmentRequest = components["schemas"]["CloseAssessmentRequest"];
export type CloseAssessmentResult = components["schemas"]["CloseAssessmentResult"];
export type PublishAssessmentResultRequest = components["schemas"]["PublishAssessmentResultRequest"];
export type AssessmentPublicationCommandResult = components["schemas"]["AssessmentPublicationCommandResult"];
export type TeacherAttemptResultPage = components["schemas"]["TeacherAttemptResultPage"];
export type ManualGradeRequest = components["schemas"]["ManualGradeRequest"];
export type ListCoursesQuery = NonNullable<paths["/api/v1/courses"]["get"]["parameters"]["query"]>;
export type ListTeacherCoursesQuery = NonNullable<paths["/api/v1/teacher/courses"]["get"]["parameters"]["query"]>;
export type ListTeacherQuestionsQuery = NonNullable<paths["/api/v1/teacher/questions"]["get"]["parameters"]["query"]>;
export type ListTeacherKnowledgePointsQuery = NonNullable<paths["/api/v1/teacher/knowledge-points"]["get"]["parameters"]["query"]>;
export type SearchTeacherKnowledgePointsQuery = NonNullable<paths["/api/v1/teacher/knowledge-points/search"]["get"]["parameters"]["query"]>;
export type ListTeacherQuestionVersionsQuery = NonNullable<paths["/api/v1/teacher/questions/{questionId}/versions"]["get"]["parameters"]["query"]>;
export type ListQuestionImportsQuery = NonNullable<paths["/api/v1/teacher/question-imports"]["get"]["parameters"]["query"]>;
export type ListLegacyQuestionImportItemsQuery = NonNullable<paths["/api/v1/teacher/question-imports/{importTaskId}/items"]["get"]["parameters"]["query"]>;
export type ListMyEnrollmentsQuery = NonNullable<paths["/api/v1/learning/enrollments"]["get"]["parameters"]["query"]>;
export type ListLearnerAssessmentsQuery = NonNullable<paths["/api/v1/learning/assessments"]["get"]["parameters"]["query"]>;
export type ListWrongQuestionsQuery = NonNullable<paths["/api/v1/learning/wrong-questions"]["get"]["parameters"]["query"]>;
export type ListTeacherAssessmentsQuery = NonNullable<paths["/api/v1/teacher/assessments"]["get"]["parameters"]["query"]>;
export type ListAssessmentResultsQuery = NonNullable<paths["/api/v1/teacher/assessments/{assessmentId}/results"]["get"]["parameters"]["query"]>;

/** Gateway HTTP 状态、稳定错误码和按字段错误的人工维护异常投影。 */
export class GatewayRequestError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: Record<string, string>;
  readonly retryAfterSeconds: number | null;

  constructor(response: Response, error?: ApiError) {
    super(error?.message ?? `Gateway request failed with status ${response.status}`);
    this.name = "GatewayRequestError";
    this.status = response.status;
    this.code = error?.code ?? "COMMON.UNEXPECTED_ERROR";
    this.fieldErrors = Object.fromEntries((error?.fieldErrors ?? []).map(item => [item.field, item.reason]));
    const retryAfterHeader = response.headers.get("Retry-After");
    const retryAfter = retryAfterHeader === null ? Number.NaN : Number(retryAfterHeader);
    this.retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter >= 0 ? retryAfter : null;
  }
}

export function gatewayRequestError(response: Response, error?: ApiError): GatewayRequestError {
  return new GatewayRequestError(response, error);
}
