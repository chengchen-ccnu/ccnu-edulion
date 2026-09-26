import { GatewayRequestError } from "@edu/api-client";
import type { LocationQuery } from "vue-router";
import type { StudentQuery, TrendWindow } from "./learning-analytics-api";

export type StudentPanel = "overview" | "lessons" | "tasks";

/** 将可分享 URL 限定在 OpenAPI 允许的筛选和分页范围；非法值退回安全默认值，服务端仍最终校验。 */
export function studentState(query: LocationQuery) {
  const string = (key: string) => typeof query[key] === "string" ? query[key] as string : "";
  const number = (key: string, min: number, max: number) => {
    const value = Number(string(key));
    return string(key) !== "" && Number.isFinite(value) && value >= min && value <= max ? value : undefined;
  };
  const sort = string("sort");
  const direction = string("direction");
  const panel = string("panel");
  return {
    courseId: string("courseId"), learnerId: string("learnerId"),
    panel: (["overview", "lessons", "tasks"].includes(panel) ? panel : "overview") as StudentPanel,
    page: Number.isInteger(number("page", 1, 1_000_000)) ? number("page", 1, 1_000_000)! : 1,
    detailPage: Number.isInteger(number("detailPage", 1, 1_000_000)) ? number("detailPage", 1, 1_000_000)! : 1,
    sort: (["STUDENT_NUMBER", "LAST_ACTIVITY", "PROGRESS", "WATCHED_SECONDS", "HOMEWORK_AVERAGE", "EXAM_AVERAGE"].includes(sort)
      ? sort : "STUDENT_NUMBER") as NonNullable<StudentQuery["sort"]>,
    direction: (direction === "desc" ? "desc" : "asc") as NonNullable<StudentQuery["direction"]>,
    keyword: string("keyword").trim().slice(0, 120),
    minProgressPercent: number("minProgressPercent", 0, 100), maxProgressPercent: number("maxProgressPercent", 0, 100),
    missingOnly: string("missingOnly") === "true", pendingOnly: string("pendingOnly") === "true"
  };
}

export function trendWindow(value: unknown): TrendWindow {
  return value === "ALL" || value === "LAST_30_DAYS" ? value : "LAST_7_DAYS";
}

/** 错误文案只解释用户可采取的动作；404 不透露课程或学生是否存在。 */
export function analyticsError(error: unknown): string {
  if (error instanceof TypeError) return "网络不可用，请恢复连接后重试。";
  if (!(error instanceof GatewayRequestError)) return "学情暂时无法读取，请重试。";
  return ({
    401: "登录已失效，请重新登录。", 403: "没有教学访问权限。", 404: "课程或学生不存在，或你无权查看。",
    409: "课程或测评状态已变化，请重新读取。", 422: "筛选条件无效，请调整后重试。",
    429: "请求过于频繁，请稍后重试。", 503: "课程服务暂时不可用，无法确认当前发布内容。"
  } as Record<number, string>)[error.status] ?? error.message;
}

export function percent(value: number | null | undefined) { return value == null ? "暂不可用" : `${value.toFixed(1)}%`; }
export function duration(seconds: number | null | undefined) { return seconds == null ? "不适用" : `${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds % 3600 / 60)} 分`; }
export function dateTime(value: string | null) { return value ? new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) : "暂无"; }
