import { createGatewayClient, gatewayRequestError } from "./index";
import type { ApiError, GatewayClient, AnnouncementRevisionRequest } from "./index";

type Authorize = <T extends { response: Response }>(call: (authorization: string) => Promise<T>) => Promise<T>;

/**
 * 运营只读投影与本人消息/公告命令；字段和路径全部由生成客户端约束。
 * 输入是统一认证执行器与可取消请求，输出服务端事实；仅401由执行器刷新一次，不自动重试写命令。
 */
export function createOperationsApi(authorize: Authorize, client: GatewayClient = createGatewayClient()) {
  async function request<T>(call: (authorization: string) => Promise<{ data?: T; error?: ApiError; response: Response }>) {
    const result = await authorize(call);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error);
    return result.data;
  }
  return {
    /** 匿名获取当前榜单；不缓存签名URL，Redis降级状态原样交给页面。 */
    async hotCourses(signal?: AbortSignal) {
      const result = await client.GET("/api/v1/operations/hot-courses", { signal, cache: "no-store" });
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    /** 读取本人第page页安全消息，固定每页20条，不跨页扫描。 */
    notifications(page: number, signal?: AbortSignal) {
      return request(Authorization => client.GET("/api/v1/notifications", {
        headers: { Authorization }, params: { query: { page, size: 20 } }, signal
      }));
    },
    /** 返回服务端当前授权口径的未读/待确认数量，失败不合成零。 */
    counts(signal?: AbortSignal) {
      return request(Authorization => client.GET("/api/v1/notifications/unread-count", { headers: { Authorization }, signal }));
    },
    /** 将普通消息设为已读；返回剩余公告确认数，公告不在此确认。 */
    readAll(signal?: AbortSignal) {
      return request(Authorization => client.POST("/api/v1/notifications/read-all", { headers: { Authorization }, signal }));
    },
    /** 重新授权notificationId的固定目标；查询不会提交已读。 */
    target(notificationId: string, signal?: AbortSignal) {
      return request(Authorization => client.GET("/api/v1/notifications/{notificationId}/target", {
        headers: { Authorization }, params: { path: { notificationId } }, signal
      }));
    },
    /** 目标成功显示后提交精确消息revision；冲突由页面处理，不自动重写。 */
    read(notificationId: string, expectedRevision: number, signal?: AbortSignal) {
      return request(Authorization => client.POST("/api/v1/notifications/{notificationId}/read", {
        headers: { Authorization }, params: { path: { notificationId } }, body: { expectedRevision }, signal
      }));
    },
    /** ACTIVE报名学生的课程公告分页；权限与最新未确认由领域裁决。 */
    announcements(courseId: string, page: number, signal?: AbortSignal) {
      return request(Authorization => client.GET("/api/v1/courses/{courseId}/announcements", {
        headers: { Authorization }, params: { path: { courseId }, query: { page, size: 20 } }, signal
      }));
    },
    /** 深链接单公告正文；撤回/退课/不可见均保持服务端404语义。 */
    announcement(courseId: string, announcementId: string, signal?: AbortSignal) {
      return request(Authorization => client.GET("/api/v1/courses/{courseId}/announcements/{announcementId}", {
        headers: { Authorization }, params: { path: { courseId, announcementId } }, signal
      }));
    },
    /** 仅明确点击提交当前正文revision与请求ID；成功改变确认事实及未读投影。 */
    confirm(courseId: string, announcementId: string, body: AnnouncementRevisionRequest, signal?: AbortSignal) {
      return request(Authorization => client.POST("/api/v1/courses/{courseId}/announcements/{announcementId}/confirm", {
        headers: { Authorization }, params: { path: { courseId, announcementId } }, body, signal
      }));
    }
  };
}
