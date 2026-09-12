import { GatewayRequestError } from "@edu/api-client";

/** 把统一 Gateway 状态映射为可操作页面文案，不展示服务端内部细节。 */
export function agentError(error: unknown, fallback = "AI 答疑暂时不可用，请稍后重试。"): string {
  if (error instanceof TypeError) return "当前离线，请恢复网络后重试。";
  if (!(error instanceof GatewayRequestError)) return error instanceof Error && /题图|图片/.test(error.message) ? error.message : fallback;
  return ({ 401: "登录状态已失效，请重新登录。", 403: "当前账号没有学习空间访问能力。",
    404: "会话不存在或你无权查看。", 409: "会话状态已变化，请刷新后重试。",
    422: "输入不符合要求，请检查问题或图片。", 429: `请求过于频繁${error.retryAfterSeconds === null ? "" : `，请在 ${error.retryAfterSeconds} 秒后重试`}。`,
    500: "AI 服务处理失败，已保存的内容仍可恢复。", 502: "AI 服务连接失败，请稍后恢复。",
    503: "AI 服务暂时不可用，请稍后重试。" } as Record<number, string>)[error.status] ?? fallback;
}
