import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { ExportTeacherGradebookCsvQuery, GatewayClient, GetTeacherGradebookQuery, Gradebook } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

export type GradebookCsv = { blob: Blob; filename: string };

/**
 * 教师成绩册 API 边界：只消费 OpenAPI 生成的矩阵/CSV 路径，权限、PENDING 门禁和 owner 范围由服务端裁决。
 * CSV 适配器只把生成客户端返回的文本封装为 Blob，并读取响应头文件名；不会把 CSV 写入 URL、日志或持久化存储。
 */
export interface TeacherGradebookApi {
  get(query: GetTeacherGradebookQuery, signal?: AbortSignal): Promise<Gradebook>;
  exportCsv(query: ExportTeacherGradebookCsvQuery, signal?: AbortSignal): Promise<GradebookCsv>;
}

/** 使用当前教师会话访问 Gateway；失败保留统一错误，导出返回可下载的 UTF-8 BOM 文件。 */
export function createTeacherGradebookApi(store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): TeacherGradebookApi {
  async function jsonRequest<T>(call: (authorization: string) => Promise<{ data?: T; error?: unknown; response: Response }>) {
    const result = await store.authorizedRequest(call);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
    return result.data;
  }
  return {
    get(query, signal) {
      return jsonRequest(Authorization => client.GET("/api/v1/teacher/gradebook", {
        headers: { Authorization }, params: { query }, signal
      }));
    },
    async exportCsv(query, signal) {
      const result = await store.authorizedRequest(Authorization => client.GET("/api/v1/teacher/gradebook/export", {
        headers: { Authorization }, params: { query }, parseAs: "text", signal
      }));
      if (result.data === undefined) throw gatewayRequestError(result.response, result.error as never);
      // Fetch text 解码会消费响应的 BOM，重新编码下载文件时补回且避免重复前缀。
      const body = new Blob(["\uFEFF", result.data.replace(/^\uFEFF/, "")], { type: "text/csv;charset=utf-8" });
      return { blob: body, filename: filenameFrom(result.response.headers.get("Content-Disposition")) };
    }
  };
}

export const teacherGradebookApi = createTeacherGradebookApi();

/** 只接受服务端给出的 basename，防止 Content-Disposition 把路径或控制字符带入本地文件名。 */
function filenameFrom(contentDisposition: string | null): string {
  if (!contentDisposition) return "gradebook.csv";
  const encoded = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plain = contentDisposition.match(/filename="?([^";]+)"?/i)?.[1];
  let decoded = encoded ?? plain;
  try {
    decoded = encoded ? decodeURIComponent(encoded) : plain;
  } catch {
    // 服务端返回异常编码时仍保留可下载的安全文件名。
  }
  const safe = decoded?.replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").trim();
  return safe || "gradebook.csv";
}
