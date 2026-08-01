import createClient from "openapi-fetch";

import type { components, paths } from "./generated/schema";

export const DEFAULT_MOCK_BASE_URL = "http://127.0.0.1:4010";
const runtimeEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
export const DEFAULT_GATEWAY_BASE_URL = runtimeEnv?.VITE_GATEWAY_BASE_URL ?? "http://localhost:8080";

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
export type ListCoursesQuery = NonNullable<paths["/api/v1/courses"]["get"]["parameters"]["query"]>;
export type ListTeacherCoursesQuery = NonNullable<paths["/api/v1/teacher/courses"]["get"]["parameters"]["query"]>;

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
