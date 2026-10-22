import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { AdminInvitation, AdminInvitationCreateRequest, AdminInvitationResendRequest, GatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { authStore } from "./auth";

/** 邀请管理仅消费生成契约，通过Gateway认证；每次写入可能发邮件，不在适配层自动重试。 */
export function createAdminInvitationsApi(store: AuthStore = authStore, client: GatewayClient = createGatewayClient()) {
  async function request(send: (Authorization: string) => Promise<{ data?: AdminInvitation;
    error?: Parameters<typeof gatewayRequestError>[1]; response: Response }>): Promise<AdminInvitation> {
    const result = await store.authorizedRequest(send);
    if (!result.data) throw gatewayRequestError(result.response, result.error);
    return result.data;
  }
  return {
    /** 新建邀请，clientRequestId及邮箱/理由由页面固定，只在内存持有。 */
    create: (body: AdminInvitationCreateRequest, signal: AbortSignal) => request(Authorization =>
      client.POST("/api/v1/admin/invitations", { headers: { Authorization }, body, signal })),
    /** 根据非秘密UUID读取单条脱敏事实，无列表或邮箱检索。 */
    get: (invitationId: string, signal: AbortSignal) => request(Authorization =>
      client.GET("/api/v1/admin/invitations/{invitationId}", { headers: { Authorization }, params: { path: { invitationId } }, signal })),
    /** 以当前revision明确重发；服务端裁决冷却和六次上限，并作废旧凭证。 */
    resend: (invitationId: string, body: AdminInvitationResendRequest, signal: AbortSignal) => request(Authorization =>
      client.POST("/api/v1/admin/invitations/{invitationId}/resend", { headers: { Authorization }, params: { path: { invitationId } }, body, signal }))
  };
}
export const adminInvitationsApi = createAdminInvitationsApi();
