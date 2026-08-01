import {
  createGatewayClient,
  gatewayRequestError
} from "@edu/api-client";
import type {
  CategorySummary,
  CourseDetail,
  CoursePage,
  GatewayClient,
  ListCoursesQuery
} from "@edu/api-client";

export interface StudentCourseApi {
  list(query: ListCoursesQuery): Promise<CoursePage>;
  categories(): Promise<CategorySummary[]>;
  detail(courseId: string): Promise<CourseDetail>;
}

export function createStudentCourseApi(client: GatewayClient = createGatewayClient()): StudentCourseApi {
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
    async detail(courseId) {
      const result = await client.GET("/api/v1/courses/{courseId}", { params: { path: { courseId } } });
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    }
  };
}

export const studentCourseApi = createStudentCourseApi();
