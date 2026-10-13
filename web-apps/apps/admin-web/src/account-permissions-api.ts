import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type { AccountPermissionAction, AccountPermissionCommand, AccountPermissionCommandRequest,
  AccountPermissionPreview, AdminUser, AdminUserPage, GatewayClient, GatewayPaths,
  PermissionCoordinationRetryRequest } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { authStore } from "./auth";

export type AccountQuery = NonNullable<GatewayPaths["/api/v1/admin/users"]["get"]["parameters"]["query"]>;

/** 账号管理只调用生成的Gateway契约，输入有界查询或稳定命令，返回脱敏事实；所有请求接受取消信号。 */
export interface AccountPermissionsApi {
  /** 读取单页脱敏账号，不逐行预取详情。 */
  list(query: AccountQuery, signal: AbortSignal): Promise<AdminUserPage>;
  /** 主动读取当前能力、封禁、短期锁定及revision。 */
  detail(userId: string, signal: AbortSignal): Promise<AdminUser>;
  /** 从服务端核对动作资格和真实课程/学生影响，失败不能解释为零。 */
  preview(userId: string, action: AccountPermissionAction, signal: AbortSignal): Promise<AccountPermissionPreview>;
  /** 明确确认后提交IAM决定；202不代表Course或异步消息已完成。 */
  change(userId: string, body: AccountPermissionCommandRequest, signal: AbortSignal): Promise<AccountPermissionCommand>;
  /** 跨管理员恢复最近事实；204先返回null，不解析空正文、不猜命令ID。 */
  latest(userId: string, signal: AbortSignal): Promise<AccountPermissionCommand | null>;
  /** 按公开返回的命令ID读取状态；页面还须校验目标userId。 */
  command(commandId: string, signal: AbortSignal): Promise<AccountPermissionCommand>;
  /** 仅补偿原FAILED命令，以expectedAttempts防并发，不新建撤权周期。 */
  retry(commandId: string, body: PermissionCoordinationRetryRequest, signal: AbortSignal): Promise<AccountPermissionCommand>;
}

/** 共用认证的一次401恢复，其他错误及有限重试由页面处理；不包含unlock、邀请或旧disable接口。 */
export function createAccountPermissionsApi(store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): AccountPermissionsApi {
  async function request<T>(send: (authorization: string) => Promise<{ data?: T;
      error?: Parameters<typeof gatewayRequestError>[1]; response: Response }>): Promise<T> {
    const result = await store.authorizedRequest(send);
    if (result.data === undefined) throw gatewayRequestError(result.response, result.error);
    return result.data;
  }
  return {
    list: (query, signal) => request(Authorization => client.GET("/api/v1/admin/users", { headers: { Authorization }, params: { query }, signal })),
    detail: (userId, signal) => request(Authorization => client.GET("/api/v1/admin/users/{userId}", { headers: { Authorization }, params: { path: { userId } }, signal })),
    preview: (userId, action, signal) => request(Authorization => client.POST("/api/v1/admin/users/{userId}/permission-previews", {
      headers: { Authorization }, params: { path: { userId } }, body: { action }, signal })),
    change: (userId, body, signal) => request(Authorization => client.POST("/api/v1/admin/users/{userId}/permission-commands", {
      headers: { Authorization }, params: { path: { userId } }, body, signal })),
    latest: async (userId, signal) => {
      const result = await store.authorizedRequest(Authorization => client.GET("/api/v1/admin/users/{userId}/permission-commands/latest", {
        headers: { Authorization }, params: { path: { userId } }, signal }));
      if (result.response.status === 204) return null;
      if (result.data === undefined) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    command: (commandId, signal) => request(Authorization => client.GET("/api/v1/admin/permission-commands/{commandId}", {
      headers: { Authorization }, params: { path: { commandId } }, signal })),
    retry: (commandId, body, signal) => request(Authorization => client.POST("/api/v1/admin/permission-commands/{commandId}/retry", {
      headers: { Authorization }, params: { path: { commandId } }, body, signal }))
  };
}
export const accountPermissionsApi = createAccountPermissionsApi();
