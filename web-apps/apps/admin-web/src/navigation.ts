import type { ShellNavigationItem } from "@edu/ui";

/**
 * 输入当前路由名，输出固定两项管理侧栏；只启用教师审核，账号权限保留不可点击的建设中槽位。
 */
export function buildAdminNavigation(routeName: string | null | undefined): ShellNavigationItem[] {
  return [
    { id: "teacher-review", label: "教师审核", state: "enabled", kind: "router", href: "/admin/teacher-review",
      current: routeName === "admin-teacher-review" },
    { id: "users", label: "账号权限", state: "coming-soon", current: false }
  ];
}
