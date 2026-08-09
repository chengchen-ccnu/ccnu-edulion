import type { ShellNavigationItem } from "@edu/ui";

/**
 * 构造管理端固定七槽侧栏。
 * T17C 前所有目标均为建设中，当前深链接只可改变高亮，返回模型不会提供 href 或键盘触发入口。
 */
export function buildAdminNavigation(routeName: string | null | undefined): ShellNavigationItem[] {
  return [
    { id: "dashboard", label: "工作台", state: "coming-soon", current: routeName === "admin-dashboard" },
    { id: "teacher-review", label: "教师审核", state: "coming-soon", current: routeName === "admin-teacher-review" },
    { id: "course-review", label: "课程审核", state: "coming-soon", current: routeName === "admin-course-review" },
    { id: "content-moderation", label: "内容治理", state: "coming-soon",
      current: routeName === "admin-content-moderation" },
    { id: "operations", label: "运营配置", state: "coming-soon", current: routeName === "admin-operations" },
    { id: "reports", label: "数据报表", state: "coming-soon", current: routeName === "admin-reports" },
    { id: "system", label: "系统管理", state: "coming-soon", current: routeName === "admin-system" }
  ];
}
