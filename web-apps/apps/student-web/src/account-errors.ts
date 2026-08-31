import { GatewayRequestError } from "@edu/api-client";

/** 将契约错误映射为页面可理解的提示，同时保留服务端字段错误和 Retry-After。 */
export function accountError(error: unknown, fallback: string): {
  message: string; fieldErrors: Record<string, string>; retryAfter: number | null; status: number | null;
} {
  if (!(error instanceof GatewayRequestError)) return { message: fallback, fieldErrors: {}, retryAfter: null, status: null };
  if (error.status === 429) return { message: "操作过于频繁，请稍后再试。", fieldErrors: error.fieldErrors,
    retryAfter: error.retryAfterSeconds, status: error.status };
  if (error.status === 503) return { message: "安全服务暂时不可用，请稍后重试。", fieldErrors: error.fieldErrors,
    retryAfter: null, status: error.status };
  if (error.status === 409) return { message: "账号状态已变化，请刷新后重试。", fieldErrors: error.fieldErrors,
    retryAfter: null, status: error.status };
  if (error.status === 401) return { message: "身份验证未通过，请检查输入后重试。", fieldErrors: error.fieldErrors,
    retryAfter: null, status: error.status };
  return { message: error.message || fallback, fieldErrors: error.fieldErrors, retryAfter: null, status: error.status };
}

export function randomRequestId(): string {
  return crypto.randomUUID();
}
