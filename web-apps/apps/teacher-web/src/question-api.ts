import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type {
  CreateLegacyQuestionImportRequest,
  CreateQuestionImportRequest,
  CreateQuestionRequest,
  GatewayClient,
  KnowledgePoint,
  LegacyQuestionImportBatchRequest,
  LegacyQuestionImportBatchResult,
  LegacyQuestionImportCleanupResult,
  LegacyQuestionImportItemPage,
  LegacyQuestionImportReviewItem,
  ListLegacyQuestionImportItemsQuery,
  ListQuestionImportsQuery,
  ListTeacherQuestionsQuery,
  ListTeacherQuestionVersionsQuery,
  QuestionImportPage,
  QuestionImportTask,
  QuestionPage,
  QuestionVersion,
  QuestionVersionPage,
  ResolveLegacyQuestionImportItemRequest,
  TeacherQuestion,
  UpdateQuestionRequest,
  UpdateQuestionStatusRequest
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/**
 * T10 教师题库 API 边界。
 * 输入和输出全部引用 OpenAPI 生成类型；所有权、生命周期和 revision 仍由服务端裁决，页面不得本地伪造成功。
 */
export interface TeacherQuestionApi {
  knowledgePoints(signal?: AbortSignal): Promise<KnowledgePoint[]>;
  list(query: ListTeacherQuestionsQuery, signal?: AbortSignal): Promise<QuestionPage>;
  detail(questionId: string, signal?: AbortSignal): Promise<TeacherQuestion>;
  create(body: CreateQuestionRequest, signal?: AbortSignal): Promise<TeacherQuestion>;
  update(questionId: string, body: UpdateQuestionRequest, signal?: AbortSignal): Promise<TeacherQuestion>;
  updateStatus(questionId: string, body: UpdateQuestionStatusRequest, signal?: AbortSignal): Promise<TeacherQuestion>;
  versions(questionId: string, query: ListTeacherQuestionVersionsQuery,
    signal?: AbortSignal): Promise<QuestionVersionPage>;
  version(questionId: string, versionId: string, signal?: AbortSignal): Promise<QuestionVersion>;
  imports(query: ListQuestionImportsQuery, signal?: AbortSignal): Promise<QuestionImportPage>;
  createImport(body: CreateQuestionImportRequest, signal?: AbortSignal): Promise<QuestionImportTask>;
  importDetail(importTaskId: string, signal?: AbortSignal): Promise<QuestionImportTask>;
  createLegacyImport(body: CreateLegacyQuestionImportRequest, signal?: AbortSignal): Promise<QuestionImportTask>;
  submitLegacyBatch(importTaskId: string, body: LegacyQuestionImportBatchRequest,
    signal?: AbortSignal): Promise<LegacyQuestionImportBatchResult>;
  legacyItems(importTaskId: string, query: ListLegacyQuestionImportItemsQuery,
    signal?: AbortSignal): Promise<LegacyQuestionImportItemPage>;
  legacyItem(importTaskId: string, itemId: string, signal?: AbortSignal): Promise<LegacyQuestionImportReviewItem>;
  resolveLegacyItem(importTaskId: string, itemId: string, body: ResolveLegacyQuestionImportItemRequest,
    signal?: AbortSignal): Promise<LegacyQuestionImportReviewItem>;
  cleanupLegacyImport(importTaskId: string, signal?: AbortSignal): Promise<LegacyQuestionImportCleanupResult>;
}

/**
 * 构造经 Gateway 与统一登录状态调用的题库客户端。
 * 401 由 AuthStore 最多刷新一次；404/409/422/429/5xx 原样投影为 GatewayRequestError 交给页面处理。
 */
export function createTeacherQuestionApi(
    store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): TeacherQuestionApi {
  const authorized = <T extends { response: Response }>(request: (authorization: string) => Promise<T>) =>
    store.authorizedRequest(request);
  const data = <T>(result: { data?: T; error?: unknown; response: Response }): T => {
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
    return result.data;
  };

  return {
    async knowledgePoints(signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/teacher/knowledge-points", {
        headers: { Authorization }, signal
      })));
    },
    async list(query, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/teacher/questions", {
        headers: { Authorization }, params: { query }, signal
      })));
    },
    async detail(questionId, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/teacher/questions/{questionId}", {
        headers: { Authorization }, params: { path: { questionId } }, signal
      })));
    },
    async create(body, signal) {
      return data(await authorized(Authorization => client.POST("/api/v1/teacher/questions", {
        headers: { Authorization }, body, signal
      })));
    },
    async update(questionId, body, signal) {
      return data(await authorized(Authorization => client.PUT("/api/v1/teacher/questions/{questionId}", {
        headers: { Authorization }, params: { path: { questionId } }, body, signal
      })));
    },
    async updateStatus(questionId, body, signal) {
      return data(await authorized(Authorization => client.PUT("/api/v1/teacher/questions/{questionId}/status", {
        headers: { Authorization }, params: { path: { questionId } }, body, signal
      })));
    },
    async versions(questionId, query, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/teacher/questions/{questionId}/versions", {
        headers: { Authorization }, params: { path: { questionId }, query }, signal
      })));
    },
    async version(questionId, versionId, signal) {
      return data(await authorized(Authorization => client.GET(
        "/api/v1/teacher/questions/{questionId}/versions/{versionId}", {
          headers: { Authorization }, params: { path: { questionId, versionId } }, signal
        })));
    },
    async imports(query, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/teacher/question-imports", {
        headers: { Authorization }, params: { query }, signal
      })));
    },
    async createImport(body, signal) {
      return data(await authorized(Authorization => client.POST("/api/v1/teacher/question-imports", {
        headers: { Authorization }, body, signal
      })));
    },
    async importDetail(importTaskId, signal) {
      return data(await authorized(Authorization => client.GET("/api/v1/teacher/question-imports/{importTaskId}", {
        headers: { Authorization }, params: { path: { importTaskId } }, signal
      })));
    },
    async createLegacyImport(body, signal) {
      return data(await authorized(Authorization => client.POST("/api/v1/teacher/legacy-question-imports", {
        headers: { Authorization }, body, signal
      })));
    },
    async submitLegacyBatch(importTaskId, body, signal) {
      return data(await authorized(Authorization => client.POST(
        "/api/v1/teacher/legacy-question-imports/{importTaskId}/batches", {
          headers: { Authorization }, params: { path: { importTaskId } }, body, signal
        })));
    },
    async legacyItems(importTaskId, query, signal) {
      return data(await authorized(Authorization => client.GET(
        "/api/v1/teacher/question-imports/{importTaskId}/items", {
          headers: { Authorization }, params: { path: { importTaskId }, query }, signal
        })));
    },
    async legacyItem(importTaskId, itemId, signal) {
      return data(await authorized(Authorization => client.GET(
        "/api/v1/teacher/question-imports/{importTaskId}/items/{itemId}", {
          headers: { Authorization }, params: { path: { importTaskId, itemId } }, signal
        })));
    },
    async resolveLegacyItem(importTaskId, itemId, body, signal) {
      return data(await authorized(Authorization => client.POST(
        "/api/v1/teacher/question-imports/{importTaskId}/items/{itemId}/resolution", {
          headers: { Authorization }, params: { path: { importTaskId, itemId } }, body, signal
        })));
    },
    async cleanupLegacyImport(importTaskId, signal) {
      return data(await authorized(Authorization => client.DELETE(
        "/api/v1/teacher/legacy-question-imports/{importTaskId}/created-resources", {
          headers: { Authorization }, params: { path: { importTaskId } }, signal
        })));
    }
  };
}

export const teacherQuestionApi = createTeacherQuestionApi();
