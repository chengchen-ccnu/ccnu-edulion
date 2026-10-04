import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { AdminTeacherApplicationDetail, AdminTeacherApplicationPage, GatewayClient, GatewayPaths,
  MediaAccessGrant, TeacherQualificationAccessRequest, TeacherReviewDecisionRequest } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { authStore } from "./auth";

export type ReviewQuery = NonNullable<GatewayPaths["/api/v1/admin/teacher-applications"]["get"]["parameters"]["query"]>;

/** 管理审核适配器仅消费生成契约；输入分页/申请/轮次或稳定决定，输出服务端授权投影，所有请求可取消。 */
export interface TeacherReviewApi {
  /** 列表只读，不开启审核，不预取正文或材料。 */
  list(query: ReviewQuery, signal: AbortSignal): Promise<AdminTeacherApplicationPage>;
  /** 主动打开或深链接详情会在服务端记录首次查看并推进审核中；不用于逐行预取。 */
  detail(applicationId: string, signal: AbortSignal): Promise<AdminTeacherApplicationDetail>;
  /** 按申请及轮次申请短期原件授权；不接受任意 mediaId。 */
  material(applicationId: string, body: TeacherQualificationAccessRequest, signal: AbortSignal): Promise<MediaAccessGrant>;
  /** 同请求ID、版本及载荷可重放决定；审批授能由IAM事务完成，409禁止自动覆盖。 */
  decide(applicationId: string, body: TeacherReviewDecisionRequest, signal: AbortSignal): Promise<AdminTeacherApplicationDetail>;
}

/** 通过统一认证的一次401恢复调用Gateway；其他失败由页面决定是否允许手动重试。 */
export function createTeacherReviewApi(store: AuthStore = authStore, client: GatewayClient = createGatewayClient()): TeacherReviewApi {
  async function request<T>(send: (authorization: string) => Promise<{ data?: T;
      error?: Parameters<typeof gatewayRequestError>[1]; response: Response }>): Promise<T> {
    const result = await store.authorizedRequest(send);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error);
    return result.data;
  }
  return {
    list: (query, signal) => request(Authorization => client.GET("/api/v1/admin/teacher-applications", {
      headers: { Authorization }, params: { query }, signal
    })),
    detail: (applicationId, signal) => request(Authorization => client.GET("/api/v1/admin/teacher-applications/{applicationId}", {
      headers: { Authorization }, params: { path: { applicationId } }, signal
    })),
    material: (applicationId, body, signal) => request(Authorization => client.POST("/api/v1/admin/teacher-applications/{applicationId}/material-access", {
      headers: { Authorization }, params: { path: { applicationId } }, body, signal
    })),
    decide: (applicationId, body, signal) => request(Authorization => client.POST("/api/v1/admin/teacher-applications/{applicationId}/decision", {
      headers: { Authorization }, params: { path: { applicationId } }, body, signal
    }))
  };
}
export const teacherReviewApi = createTeacherReviewApi();
