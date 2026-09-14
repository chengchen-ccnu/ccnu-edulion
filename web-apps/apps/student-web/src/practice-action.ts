import { GatewayRequestError } from "@edu/api-client";
import type { PendingPracticeAction, PracticeDifficulty } from "@edu/api-client";

const commandLocks = new Map<string, Promise<PendingPracticeAction>>();
const commandRequestIds = new Map<string, string>();

/**
 * 合并同一浏览器页面对同一 action 的并发确认/取消。输入包含服务端 revision 和真正的命令函数，
 * 输出始终是服务端动作投影；网络结果未知时保留同一 requestId，供用户查询后按原键有限重试。
 */
export function runPracticeCommand(action: PendingPracticeAction, kind: "confirm" | "cancel",
    command: (requestId: string, expectedRevision: number) => Promise<PendingPracticeAction>) {
  const key = `${kind}:${action.actionId}`;
  const existing = commandLocks.get(key);
  if (existing) return existing;
  const requestId = commandRequestIds.get(key) ?? crypto.randomUUID();
  commandRequestIds.set(key, requestId);
  const running = command(requestId, action.revision).finally(() => commandLocks.delete(key));
  commandLocks.set(key, running);
  return running;
}

/** 把用户明确编辑的条件变为新一轮自然语言请求；它不携带候选题 ID，也不会表达自动确认语义。 */
export function practiceRegenerationPrompt(topic: string, difficulty: PracticeDifficulty, count: number) {
  const difficultyLabel = { EASY: "简单", MEDIUM: "中等", HARD: "困难" }[difficulty];
  return `请按新条件生成自主练习建议：主题“${topic.trim()}”，难度${difficultyLabel}，数量${count}道。请只生成待确认建议，不要自动创建、开始、提交或评分。`;
}

/** 将动作接口错误转成人能恢复的提示；字段输入和服务端动作始终由调用组件保留。 */
export function practiceActionError(error: unknown, fallback = "练习建议暂时无法处理。") {
  if (error instanceof TypeError || error instanceof DOMException && error.name === "AbortError") {
    return "网络中断或请求超时，结果可能已经发生，请查询真实结果。";
  }
  if (!(error instanceof GatewayRequestError)) return fallback;
  const labels: Record<number, string> = {
    401: "登录状态已失效，请重新登录。",
    403: "当前账号无权处理这条练习建议。",
    404: "练习建议不存在、已不可见或会话不属于当前账号。",
    409: "建议状态或 revision 已变化，已为你查询最新状态。",
    422: error.message,
    429: "请求过于频繁，请稍后查询或重试。",
    503: "创建依赖暂时不可用；建议条件仍已保留。"
  };
  return labels[error.status] ?? error.message;
}

export function isUnknownPracticeCommandError(error: unknown) {
  return error instanceof TypeError || error instanceof DOMException && error.name === "AbortError"
    || error instanceof GatewayRequestError && error.status >= 500;
}
