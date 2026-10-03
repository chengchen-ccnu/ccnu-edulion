import { GatewayRequestError } from "@edu/api-client";
import { ref } from "vue";

/** 将授权/状态冲突/参数/限流/网络失败转换成安全产品文案，不输出私有审核或服务内部原因。 */
export function portalError(error: unknown): string {
  if (error instanceof TypeError) return "当前离线，请检查网络后重试。";
  if (error instanceof Error && error.message === "AUTHENTICATION_REQUIRED") return "登录状态已失效，请重新登录。";
  if (!(error instanceof GatewayRequestError)) return "暂时无法加载，请稍后重试。";
  return ({
    401: "登录状态已失效，请重新登录。",
    403: "当前账号或报名状态不允许访问。",
    404: "内容不存在、已撤回或当前不可见。",
    409: "内容或状态已变化，请重新加载后再操作。",
    422: "请求参数不符合要求，请检查访问地址或输入。",
    429: "请求过于频繁，请稍后重试。",
    503: "服务暂时不可用，请稍后重试。"
  } as Record<number, string>)[error.status] ?? "服务请求失败，请稍后重试。";
}

/** 每区域记录限流冷却；按钮手动重试也须尊重Retry-After，无后台定时重发。 */
export function usePortalFailure() {
  const error = ref("");
  const retryAt = ref(0);
  function fail(value: unknown) {
    error.value = portalError(value);
    if (value instanceof GatewayRequestError && value.status === 429) {
      const seconds = Math.max(1, value.retryAfterSeconds ?? 30);
      retryAt.value = Date.now() + seconds * 1000;
      error.value += ` 请至少等待 ${seconds} 秒。`;
    }
  }
  function allowed() { return Date.now() >= retryAt.value; }
  return { error, fail, allowed };
}

export function pageNumber(value: unknown) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : 1;
}
export function dateLabel(value: string) {
  return new Date(value).toLocaleString("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}
