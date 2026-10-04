import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { AnnouncementPage, AnnouncementRevisionPage, AnnouncementRevisionRequest,
  AnnouncementUpdateRequest, AnnouncementWriteRequest, CourseAnnouncement, GatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { authStore } from "./auth";

/** 教师公告的五个 owner 操作；输入为课程、分页或生成的命令 DTO，输出为公告/审计投影。所有请求可取消。 */
export interface TeacherAnnouncementApi {
  /** 读取 owner 当前一页（每页20条，页码从1开始），包含撤回记录；授权错误不伪装为空列表。 */
  list(courseId: string, page: number, signal: AbortSignal): Promise<AnnouncementPage>;
  /** 读取选中公告一页只读历史；输入课程/公告 UUID，返回审计 revision，不扫描其他页。 */
  revisions(courseId: string, announcementId: string, page: number, signal: AbortSignal): Promise<AnnouncementRevisionPage>;
  /** 提交稳定请求号及纯文本，保存公告/审计/Outbox后返回新公告；实际写资格由服务端裁决。 */
  create(courseId: string, body: AnnouncementWriteRequest, signal: AbortSignal): Promise<CourseAnnouncement>;
  /** 按眼前 expectedRevision 改版，返回新版；409交给用户核对，不在适配层覆盖或重试。 */
  update(courseId: string, announcementId: string, body: AnnouncementUpdateRequest, signal: AbortSignal): Promise<CourseAnnouncement>;
  /** 明确确认后按 expectedRevision 撤回，返回 WITHDRAWN；取消浏览器请求不能回滚已提交事实。 */
  withdraw(courseId: string, announcementId: string, body: AnnouncementRevisionRequest, signal: AbortSignal): Promise<CourseAnnouncement>;
}

/** 只通过 Gateway 和既有授权恢复发送命令；401 的一次重放保持原 requestId，其他错误交由页面处理。 */
export function createTeacherAnnouncementApi(store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): TeacherAnnouncementApi {
  async function request<T>(send: (authorization: string) => Promise<{ data?: T; error?: Parameters<typeof gatewayRequestError>[1]; response: Response }>): Promise<T> {
    const result = await store.authorizedRequest(send);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error);
    return result.data;
  }
  return {
    list: (courseId, page, signal) => request(Authorization => client.GET("/api/v1/teacher/courses/{courseId}/announcements", {
      headers: { Authorization }, params: { path: { courseId }, query: { page, size: 20 } }, signal
    })),
    revisions: (courseId, announcementId, page, signal) => request(Authorization => client.GET("/api/v1/teacher/courses/{courseId}/announcements/{announcementId}/revisions", {
      headers: { Authorization }, params: { path: { courseId, announcementId }, query: { page, size: 20 } }, signal
    })),
    create: (courseId, body, signal) => request(Authorization => client.POST("/api/v1/teacher/courses/{courseId}/announcements", {
      headers: { Authorization }, params: { path: { courseId } }, body, signal
    })),
    update: (courseId, announcementId, body, signal) => request(Authorization => client.PUT("/api/v1/teacher/courses/{courseId}/announcements/{announcementId}", {
      headers: { Authorization }, params: { path: { courseId, announcementId } }, body, signal
    })),
    withdraw: (courseId, announcementId, body, signal) => request(Authorization => client.POST("/api/v1/teacher/courses/{courseId}/announcements/{announcementId}/withdraw", {
      headers: { Authorization }, params: { path: { courseId, announcementId } }, body, signal
    }))
  };
}
export const teacherAnnouncementApi = createTeacherAnnouncementApi();
