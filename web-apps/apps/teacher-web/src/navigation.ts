/** 教师端导航模型：公共课程中心对所有身份可见，教学入口只对有教学能力的教师显示。 */
import type { CurrentUser } from "@edu/api-client";
import { buildCourseCenterUrl } from "@edu/auth";
import type { PlatformUrls } from "@edu/auth";

/** 教师应用根壳可渲染的站内路由或跨应用绝对链接。 */
export interface TeacherNavigationItem {
  id: "dashboard" | "course-center" | "teaching-management";
  label: string;
  kind: "router" | "external";
  href: string;
}

/**
 * 根据当前身份生成教师应用导航；输入为可空的当前用户，输出不会向学生暴露教学管理入口。
 */
export function buildTeacherNavigation(user: CurrentUser | null, urls?: PlatformUrls): TeacherNavigationItem[] {
  const courseCenter: TeacherNavigationItem = {
    id: "course-center",
    label: "课程中心",
    kind: "external",
    href: buildCourseCenterUrl(urls)
  };
  const canTeach = user?.roles.includes("TEACHER") && user.permissions.includes("teaching:access");
  if (!canTeach) return [courseCenter];
  return [
    { id: "dashboard", label: "工作台", kind: "router", href: "/teacher/dashboard" },
    courseCenter,
    { id: "teaching-management", label: "教学管理", kind: "router", href: "/teacher/courses" }
  ];
}
