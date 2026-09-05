import {
  createGatewayClient,
  gatewayRequestError
} from "@edu/api-client";
import type {
  CourseProgress,
  ArticleLessonCompletion,
  CompleteArticleLessonRequest,
  Enrollment,
  EnrollmentPage,
  GatewayClient,
  LearningNote,
  LessonProgress,
  ListMyEnrollmentsQuery,
  ReportLessonProgressRequest,
  UpdateLearningNoteRequest
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

type AuthorizedResult<T> = { data?: T; error?: unknown; response: Response };
type Wait = (milliseconds: number, signal?: AbortSignal) => Promise<void>;

/**
 * 学生端学习事实 API。输入、输出全部引用 OpenAPI 生成类型，请求只经过 Gateway 和统一认证层。
 * 429 最多退避两次并复用原请求体；调用方传入的 AbortSignal 会同时取消等待与网络请求。
 * reportProgressKeepalive 仅用于 pagehide 的一次性尽力请求，使用现有 Authorization 头和 fetch keepalive，不把令牌放入 URL。
 */
export interface StudentLearningApi {
  enrollments(query: ListMyEnrollmentsQuery, signal?: AbortSignal): Promise<EnrollmentPage>;
  findActiveEnrollment(courseId: string, signal?: AbortSignal): Promise<Enrollment | null>;
  enroll(courseId: string, signal?: AbortSignal): Promise<Enrollment>;
  progress(courseId: string, signal?: AbortSignal): Promise<CourseProgress>;
  reportProgress(courseId: string, lessonId: string, body: ReportLessonProgressRequest,
    signal?: AbortSignal): Promise<LessonProgress>;
  reportProgressKeepalive(courseId: string, lessonId: string, body: ReportLessonProgressRequest): Promise<LessonProgress>;
  completeArticle(courseId: string, lessonId: string, body: CompleteArticleLessonRequest,
    signal?: AbortSignal): Promise<ArticleLessonCompletion>;
  notes(courseId: string, lessonId: string, signal?: AbortSignal): Promise<LearningNote[]>;
  createNote(courseId: string, lessonId: string, content: string, positionSeconds: number | null,
    signal?: AbortSignal): Promise<LearningNote>;
  updateNote(noteId: string, body: UpdateLearningNoteRequest, signal?: AbortSignal): Promise<LearningNote>;
  deleteNote(noteId: string, signal?: AbortSignal): Promise<void>;
}

/** 构造学习域 Gateway 适配器；测试可注入等待函数以验证有限 429 重试而不真实休眠。 */
export function createStudentLearningApi(client: GatewayClient = createGatewayClient(), store: AuthStore = authStore,
    wait: Wait = abortableDelay): StudentLearningApi {
  async function authorized<T>(request: (authorization: string) => Promise<AuthorizedResult<T>>,
      signal?: AbortSignal, maxAttempts = 3): Promise<T> {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
      const result = await store.authorizedRequest(request);
      if (result.data !== undefined) return result.data;
      if (result.response.status !== 429 || attempt === maxAttempts - 1 || attempt === 2) {
        throw gatewayRequestError(result.response, result.error as never);
      }
      await wait(retryDelay(result.response.headers.get("Retry-After"), attempt), signal);
    }
    throw new Error("LEARNING_RETRY_EXHAUSTED");
  }

  return {
    enrollments(query, signal) {
      return authorized(Authorization => client.GET("/api/v1/learning/enrollments", {
        headers: { Authorization }, params: { query }, signal
      }), signal);
    },
    async findActiveEnrollment(courseId, signal) {
      let page = 1;
      do {
        const result = await this.enrollments({ page, size: 100, status: "ACTIVE" }, signal);
        const match = result.items.find(item => item.courseId === courseId);
        if (match) return match;
        if (page * result.size >= result.total) return null;
        page += 1;
      } while (!signal?.aborted);
      throw new DOMException("Aborted", "AbortError");
    },
    enroll(courseId, signal) {
      return authorized(Authorization => client.POST("/api/v1/learning/enrollments", {
        headers: { Authorization }, body: { courseId }, signal
      }), signal);
    },
    progress(courseId, signal) {
      return authorized(Authorization => client.GET("/api/v1/learning/courses/{courseId}/progress", {
        headers: { Authorization }, params: { path: { courseId } }, signal
      }), signal);
    },
    reportProgress(courseId, lessonId, body, signal) {
      return authorized(Authorization => client.POST(
        "/api/v1/learning/courses/{courseId}/lessons/{lessonId}/progress-events", {
          headers: { Authorization }, params: { path: { courseId, lessonId } }, body, signal
        }), signal);
    },
    reportProgressKeepalive(courseId, lessonId, body) {
      return authorized(Authorization => client.POST(
        "/api/v1/learning/courses/{courseId}/lessons/{lessonId}/progress-events", {
          headers: { Authorization }, params: { path: { courseId, lessonId } }, body, keepalive: true
        }), undefined, 1);
    },
    completeArticle(courseId, lessonId, body, signal) {
      return authorized(Authorization => client.POST(
        "/api/v1/learning/courses/{courseId}/lessons/{lessonId}/article-completion", {
          headers: { Authorization }, params: { path: { courseId, lessonId } }, body, signal
        }), signal);
    },
    notes(courseId, lessonId, signal) {
      return authorized(Authorization => client.GET(
        "/api/v1/learning/courses/{courseId}/lessons/{lessonId}/notes", {
          headers: { Authorization }, params: { path: { courseId, lessonId } }, signal
        }), signal);
    },
    createNote(courseId, lessonId, content, positionSeconds, signal) {
      return authorized(Authorization => client.POST(
        "/api/v1/learning/courses/{courseId}/lessons/{lessonId}/notes", {
          headers: { Authorization }, params: { path: { courseId, lessonId } },
          body: { content, positionSeconds }, signal
        }), signal);
    },
    updateNote(noteId, body, signal) {
      return authorized(Authorization => client.PUT("/api/v1/learning/notes/{noteId}", {
        headers: { Authorization }, params: { path: { noteId } }, body, signal
      }), signal);
    },
    async deleteNote(noteId, signal) {
      await authorized<null>(Authorization => client.DELETE("/api/v1/learning/notes/{noteId}", {
        headers: { Authorization }, params: { path: { noteId } }, signal
      }).then(result => result.response.status === 204 ? { ...result, data: null } : result), signal);
    }
  };
}

function retryDelay(retryAfter: string | null, attempt: number): number {
  const seconds = Number(retryAfter);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.min(5_000, seconds * 1_000);
  return 500 * 2 ** attempt;
}

function abortableDelay(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const handleAbort = () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", handleAbort);
      resolve();
    }, milliseconds);
    signal?.addEventListener("abort", handleAbort, { once: true });
  });
}

export const studentLearningApi = createStudentLearningApi();

/** 识别统一认证层在无可用刷新会话时抛出的稳定失败，不依赖页面猜测 HTTP 响应字段。 */
export function isAuthenticationRequired(error: unknown): boolean {
  return error instanceof Error && error.message === "AUTHENTICATION_REQUIRED";
}
