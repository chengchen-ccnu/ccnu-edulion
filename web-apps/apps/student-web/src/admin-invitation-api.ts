import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { AdminInvitationAcceptRequest, AdminInvitationAcceptance, GatewayClient } from "@edu/api-client";

/** 公共一次性开户只发送正文，不附Authorization、不携带Cookie、不恢复会话，也不自动重试。 */
export function createAdminInvitationAcceptanceApi(client: GatewayClient = createGatewayClient()) {
  return async (body: AdminInvitationAcceptRequest, signal: AbortSignal): Promise<AdminInvitationAcceptance> => {
    const result = await client.POST("/api/v1/auth/admin-invitations/accept", { body, signal, credentials: "omit" });
    if (result.response.status !== 201 || !result.data) throw gatewayRequestError(result.response, result.error);
    return result.data;
  };
}
export const acceptAdminInvitation = createAdminInvitationAcceptanceApi();
