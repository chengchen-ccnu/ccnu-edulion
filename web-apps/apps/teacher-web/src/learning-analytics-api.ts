import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { GatewayClient, GatewayPaths } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { authStore } from "./auth";

type StudentPath = "/api/v1/teacher/learning/courses/{courseId}/students";
type StudentItemPath = "/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/overview";
type StudentLessonPath = "/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/lessons";
type StudentTaskPath = "/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/tasks";
type StudentTrendPath = "/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/trend";
type CourseOverviewPath = "/api/v1/teacher/learning/courses/{courseId}/analytics/overview";
type CourseLessonPath = "/api/v1/teacher/learning/courses/{courseId}/analytics/lessons";
type CourseTaskPath = "/api/v1/teacher/learning/courses/{courseId}/analytics/tasks";
type CourseTrendPath = "/api/v1/teacher/learning/courses/{courseId}/analytics/trend";
type Json<P extends keyof GatewayPaths> = GatewayPaths[P] extends { get: { responses: { 200: { content: { "application/json": infer T } } } } } ? T : never;
type Query<P extends keyof GatewayPaths> = GatewayPaths[P] extends { get: { parameters: { query?: infer T } } } ? NonNullable<T> : never;

export type StudentPage = Json<StudentPath>;
export type StudentOverview = Json<StudentItemPath>;
export type StudentLessonPage = Json<StudentLessonPath>;
export type StudentTaskPage = Json<StudentTaskPath>;
export type CourseOverview = Json<CourseOverviewPath>;
export type CourseLessonPage = Json<CourseLessonPath>;
export type CourseTaskPage = Json<CourseTaskPath>;
export type LearningTrendData = Json<CourseTrendPath>;
export type StudentQuery = Query<StudentPath>;
export type TrendWindow = NonNullable<Query<CourseTrendPath>["window"]>;

/** 教师学情的唯一网络边界。入参为课程及 ACTIVE 学生标识，输出为生成契约投影；owner、成绩门禁和聚合均由服务端执行。 */
export function createLearningAnalyticsApi(store: AuthStore = authStore, client: GatewayClient = createGatewayClient()) {
  async function read<T>(call: (authorization: string) => Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
    const result = await store.authorizedRequest(call);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
    return result.data;
  }
  return {
    students(courseId: string, query: StudentQuery, signal?: AbortSignal): Promise<StudentPage> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/students", {
        headers: { Authorization }, params: { path: { courseId }, query }, signal
      }));
    },
    studentOverview(courseId: string, learnerId: string, signal?: AbortSignal): Promise<StudentOverview> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/overview", {
        headers: { Authorization }, params: { path: { courseId, learnerId } }, signal
      }));
    },
    studentLessons(courseId: string, learnerId: string, page: number, signal?: AbortSignal): Promise<StudentLessonPage> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/lessons", {
        headers: { Authorization }, params: { path: { courseId, learnerId }, query: { page, size: 50 } }, signal
      }));
    },
    studentTasks(courseId: string, learnerId: string, page: number, signal?: AbortSignal): Promise<StudentTaskPage> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/tasks", {
        headers: { Authorization }, params: { path: { courseId, learnerId }, query: { page, size: 20 } }, signal
      }));
    },
    studentTrend(courseId: string, learnerId: string, window: TrendWindow, signal?: AbortSignal): Promise<LearningTrendData> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/students/{learnerId}/trend", {
        headers: { Authorization }, params: { path: { courseId, learnerId }, query: { window } }, signal
      }));
    },
    courseOverview(courseId: string, signal?: AbortSignal): Promise<CourseOverview> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/analytics/overview", {
        headers: { Authorization }, params: { path: { courseId } }, signal
      }));
    },
    courseLessons(courseId: string, page: number, signal?: AbortSignal): Promise<CourseLessonPage> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/analytics/lessons", {
        headers: { Authorization }, params: { path: { courseId }, query: { page, size: 50 } }, signal
      }));
    },
    courseTasks(courseId: string, page: number, signal?: AbortSignal): Promise<CourseTaskPage> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/analytics/tasks", {
        headers: { Authorization }, params: { path: { courseId }, query: { page, size: 20 } }, signal
      }));
    },
    courseTrend(courseId: string, window: TrendWindow, lessonId?: string, signal?: AbortSignal): Promise<LearningTrendData> {
      return read(Authorization => client.GET("/api/v1/teacher/learning/courses/{courseId}/analytics/trend", {
        headers: { Authorization }, params: { path: { courseId }, query: { window, lessonId } }, signal
      }));
    }
  };
}

export const learningAnalyticsApi = createLearningAnalyticsApi();
