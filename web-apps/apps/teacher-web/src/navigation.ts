/** 教师端固定导航模型：身份只能改变受保护入口状态，不能改变标签、顺序或数量。 */
import type { CurrentUser } from "@edu/api-client";
import { buildCourseCenterUrl } from "@edu/auth";
import type { PlatformUrls } from "@edu/auth";
import type { ShellNavigationItem } from "@edu/ui";

/**
 * 根据当前路由和可空身份生成七个固定槽位。
 * 无教学能力时课程管理保留原位但无 href；未交付的 T10/T12/T17 页面始终是不可操作的建设中状态。
 */
export function buildTeacherNavigation(routeName: string | null | undefined, user: CurrentUser | null,
    urls?: PlatformUrls): ShellNavigationItem[] {
  const canTeach = user?.roles.includes("TEACHER") && user.permissions.includes("teaching:access");
  const inCourseManagement = routeName === "teacher-courses" || routeName === "teacher-course-new"
    || routeName === "teacher-course-edit" || routeName === "teacher-course-curriculum";
  return [
    { id: "course-center", label: "课程中心", state: "enabled", current: false,
      kind: "external", href: buildCourseCenterUrl(urls) },
    { id: "dashboard", label: "工作台", state: "coming-soon", current: routeName === "teacher-dashboard" },
    { id: "course-management", label: "课程管理", state: canTeach ? "enabled" : "permission-required",
      current: inCourseManagement, kind: canTeach ? "router" : undefined,
      href: canTeach ? "/teacher/courses" : undefined },
    { id: "question-bank", label: "题库", state: "coming-soon", current: routeName === "teacher-questions" },
    { id: "assessment-management", label: "测评管理", state: "coming-soon",
      current: routeName === "teacher-assessments" },
    { id: "student-analysis", label: "学情分析", state: "coming-soon", current: routeName === "teacher-students" },
    { id: "media-center", label: "媒体中心", state: "coming-soon", current: routeName === "teacher-media" }
  ];
}
