import type { GetTeacherGradebookQuery, GradebookSortField } from "@edu/api-client";

export type GradebookUrlState = {
  courseId: string;
  assessmentId?: string;
  type?: "HOMEWORK" | "EXAM";
  submissionStatus?: GetTeacherGradebookQuery["submissionStatus"];
  gradingStatus?: GetTeacherGradebookQuery["gradingStatus"];
  late?: boolean;
  keyword?: string;
  page: number;
  size: number;
  sort: GradebookSortField;
  direction: "asc" | "desc";
};

const submissionStatuses = ["NOT_STARTED", "IN_PROGRESS", "SUBMITTED", "MISSING", "WITHDRAWN"] as const;
const gradingStatuses = ["NOT_REQUIRED", "PENDING", "PARTIALLY_GRADED", "GRADED"] as const;
const sortFields = ["STUDENT_NUMBER", "LAST_SUBMITTED_AT", "SCORE", "SUBMISSION_STATUS", "GRADING_STATUS"] as const;

/**
 * 将浏览器查询参数收敛到 OpenAPI 允许的成绩册筛选；无效值回退为明确默认值。
 * 该函数不读取本机时间，也不改变服务端成绩可见性，调用方可用其结果稳定重建 URL 和请求。
 */
export function parseGradebookQuery(query: Record<string, unknown>): GradebookUrlState {
  const stringValue = (key: string) => typeof query[key] === "string" ? query[key] as string : "";
  const positive = (key: string, fallback: number, maximum?: number) => {
    const value = Number(stringValue(key));
    return Number.isInteger(value) && value >= 1 && (!maximum || value <= maximum) ? value : fallback;
  };
  const type = stringValue("type");
  const submissionStatus = stringValue("submissionStatus");
  const gradingStatus = stringValue("gradingStatus");
  const late = stringValue("late");
  const sort = stringValue("sort");
  const direction = stringValue("direction");
  const keyword = stringValue("keyword").trim().slice(0, 120);
  return {
    courseId: stringValue("courseId").trim(),
    ...(stringValue("assessmentId").trim() ? { assessmentId: stringValue("assessmentId").trim() } : {}),
    ...(type === "HOMEWORK" || type === "EXAM" ? { type } : {}),
    ...(isOneOf(submissionStatuses, submissionStatus) ? { submissionStatus } : {}),
    ...(isOneOf(gradingStatuses, gradingStatus) ? { gradingStatus } : {}),
    ...(late === "true" || late === "false" ? { late: late === "true" } : {}),
    ...(keyword ? { keyword } : {}),
    page: positive("page", 1), size: positive("size", 20, 100),
    sort: isOneOf(sortFields, sort) ? sort : "STUDENT_NUMBER",
    direction: direction === "desc" ? "desc" : "asc"
  } as GradebookUrlState;
}

/** 只写规范化后的有效状态，默认值也落入 URL，确保刷新和复制链接不会改变筛选解释。 */
export function toGradebookQuery(state: GradebookUrlState): Record<string, string> {
  return {
    courseId: state.courseId,
    ...(state.assessmentId ? { assessmentId: state.assessmentId } : {}),
    ...(state.type ? { type: state.type } : {}),
    ...(state.submissionStatus ? { submissionStatus: state.submissionStatus } : {}),
    ...(state.gradingStatus ? { gradingStatus: state.gradingStatus } : {}),
    ...(state.late === undefined ? {} : { late: String(state.late) }),
    ...(state.keyword ? { keyword: state.keyword } : {}),
    page: String(state.page), size: String(state.size), sort: state.sort, direction: state.direction
  };
}

export function gradebookRequestQuery(state: GradebookUrlState): GetTeacherGradebookQuery {
  return {
    courseId: state.courseId, assessmentId: state.assessmentId, type: state.type,
    submissionStatus: state.submissionStatus, gradingStatus: state.gradingStatus, late: state.late,
    keyword: state.keyword, page: state.page, size: state.size, sort: state.sort, direction: state.direction
  };
}

function isOneOf<T extends readonly string[]>(values: T, value: string): value is T[number] {
  return (values as readonly string[]).includes(value);
}
