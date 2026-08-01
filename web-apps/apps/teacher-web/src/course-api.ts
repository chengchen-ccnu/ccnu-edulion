import {
  createGatewayClient,
  gatewayRequestError
} from "@edu/api-client";
import type {
  CategorySummary,
  CoursePublication,
  CreateCourseRequest,
  GatewayClient,
  ListTeacherCoursesQuery,
  ReplaceCurriculumRequest,
  TeacherCourse,
  TeacherCoursePage,
  UpdateCourseRequest,
  UpdatePublicationRequest
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

export interface TeacherCourseApi {
  list(query: ListTeacherCoursesQuery): Promise<TeacherCoursePage>;
  categories(): Promise<CategorySummary[]>;
  detail(courseId: string): Promise<TeacherCourse>;
  create(body: CreateCourseRequest): Promise<TeacherCourse>;
  update(courseId: string, body: UpdateCourseRequest): Promise<TeacherCourse>;
  replaceCurriculum(courseId: string, body: ReplaceCurriculumRequest): Promise<TeacherCourse>;
  updatePublication(courseId: string, body: UpdatePublicationRequest): Promise<CoursePublication>;
}

export function createTeacherCourseApi(
    store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): TeacherCourseApi {
  const authorized = <T extends { response: Response }>(request: (authorization: string) => Promise<T>) =>
    store.authorizedRequest(request);

  return {
    async list(query) {
      const result = await authorized(Authorization => client.GET("/api/v1/teacher/courses", {
        headers: { Authorization }, params: { query }
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async categories() {
      const result = await client.GET("/api/v1/courses/categories");
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async detail(courseId) {
      const result = await authorized(Authorization => client.GET("/api/v1/teacher/courses/{courseId}", {
        headers: { Authorization }, params: { path: { courseId } }
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async create(body) {
      const result = await authorized(Authorization => client.POST("/api/v1/teacher/courses", {
        headers: { Authorization }, body
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async update(courseId, body) {
      const result = await authorized(Authorization => client.PUT("/api/v1/teacher/courses/{courseId}", {
        headers: { Authorization }, params: { path: { courseId } }, body
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async replaceCurriculum(courseId, body) {
      const result = await authorized(Authorization => client.PUT("/api/v1/teacher/courses/{courseId}/curriculum", {
        headers: { Authorization }, params: { path: { courseId } }, body
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async updatePublication(courseId, body) {
      const result = await authorized(Authorization => client.PUT("/api/v1/teacher/courses/{courseId}/publication", {
        headers: { Authorization }, params: { path: { courseId } }, body
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    }
  };
}

export const teacherCourseApi = createTeacherCourseApi();
