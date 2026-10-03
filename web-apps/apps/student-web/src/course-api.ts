import {
  createGatewayClient,
  gatewayRequestError
} from "@edu/api-client";
import type {
  CategorySummary,
  CourseMediaAccessGrant,
  CourseMediaPlaybackGrant,
  CourseDetail,
  CoursePage,
  GatewayClient,
  LessonContent,
  ListCoursesQuery,
  CourseResourceAction
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/** 学生端课程 API；公开目录与受保护课时内容严格使用 OpenAPI 生成投影。 */
export interface StudentCourseApi {
  list(query: ListCoursesQuery): Promise<CoursePage>;
  categories(signal?: AbortSignal): Promise<CategorySummary[]>;
  detail(courseId: string, signal?: AbortSignal): Promise<CourseDetail>;
  lessonContent(courseId: string, lessonId: string, signal?: AbortSignal): Promise<LessonContent>;
  resourceGrant(courseId: string, lessonId: string, resourceId: string, action: CourseResourceAction,
    signal?: AbortSignal): Promise<CourseMediaAccessGrant>;
  playbackGrant(courseId: string, lessonId: string, resourceId: string,
    signal?: AbortSignal): Promise<CourseMediaPlaybackGrant>;
}

/**
 * 构造公开课程 API 适配器。
 * @returns 分页目录、启用分类与公开详情；非成功响应统一抛出 GatewayRequestError。
 */
export function createStudentCourseApi(
    client: GatewayClient = createGatewayClient(), store: AuthStore = authStore): StudentCourseApi {
  return {
    async list(query) {
      const result = await client.GET("/api/v1/courses", { params: { query } });
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async categories(signal) {
      const result = await client.GET("/api/v1/courses/categories", { signal });
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async detail(courseId, signal) {
      const result = await client.GET("/api/v1/courses/{courseId}", {
        params: { path: { courseId } }, signal
      });
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async lessonContent(courseId, lessonId, signal) {
      const result = await store.authorizedRequest(Authorization => client.GET(
        "/api/v1/courses/{courseId}/lessons/{lessonId}/content", {
          headers: { Authorization }, params: { path: { courseId, lessonId } }, signal
        }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async resourceGrant(courseId, lessonId, resourceId, action, signal) {
      const result = await store.authorizedRequest(Authorization => client.POST(
        "/api/v1/courses/{courseId}/lessons/{lessonId}/resources/{resourceId}/access-grants", {
          headers: { Authorization }, params: { path: { courseId, lessonId, resourceId } }, body: { action }, signal
        }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async playbackGrant(courseId, lessonId, resourceId, signal) {
      const result = await store.authorizedRequest(Authorization => client.POST(
        "/api/v1/courses/{courseId}/lessons/{lessonId}/resources/{resourceId}/playback-grants", {
          headers: { Authorization }, params: { path: { courseId, lessonId, resourceId } }, signal
        }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    }
  };
}

export const studentCourseApi = createStudentCourseApi();
