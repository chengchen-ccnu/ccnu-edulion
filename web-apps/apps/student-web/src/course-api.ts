import {
  createGatewayClient,
  gatewayRequestError
} from "@edu/api-client";
import type {
  CategorySummary,
  CourseMediaPlaybackGrant,
  CourseDetail,
  CoursePage,
  GatewayClient,
  ListCoursesQuery
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/** 学生端只读课程目录 API；所有请求经 Gateway，数据结构来自 OpenAPI。 */
export interface StudentCourseApi {
  list(query: ListCoursesQuery): Promise<CoursePage>;
  categories(): Promise<CategorySummary[]>;
  detail(courseId: string, signal?: AbortSignal): Promise<CourseDetail>;
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
    async categories() {
      const result = await client.GET("/api/v1/courses/categories");
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
