import type { ShellNavigationItem } from "@edu/ui";

/**
 * 输入当前路由名，输出固定两项管理侧栏及活动项；审核与账号权限均进入真实契约页面。
 */
export function buildAdminNavigation(routeName: string | null | undefined): ShellNavigationItem[] {
  return [
    { id: "teacher-review", label: "教师审核", state: "enabled", kind: "router", href: "/admin/teacher-review",
      current: routeName === "admin-teacher-review" },
    { id: "users", label: "账号权限", state: "enabled", kind: "router", href: "/admin/users", current: routeName === "admin-users" }
  ];
}
