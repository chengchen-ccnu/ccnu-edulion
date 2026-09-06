import type { ShellNavigationItem } from "@edu/ui";

/**
 * 构造学生端固定五槽主导航。
 * 输入仅是当前路由名，输出标签与顺序恒定；T12A 只启用冻结的“我的学习”槽位。
 */
export function buildStudentNavigation(routeName: string | null | undefined): ShellNavigationItem[] {
  const isCourse = routeName === "courses" || routeName === "course-detail";
  const isLearning = routeName === "course-learning" || routeName === "my-learning" || routeName === "course-tasks"
    || routeName === "course-task-attempt" || routeName === "course-task-result";
  const isPractice = routeName === "practice" || routeName === "practice-attempt"
    || routeName === "attempt-result" || routeName === "wrong-questions";
  const isAi = routeName === "ai" || routeName === "ai-conversation";
  return [
    { id: "home", label: "首页", state: "coming-soon", current: routeName === "home" },
    { id: "course-center", label: "课程中心", state: "enabled", current: isCourse,
      kind: "router", href: "/courses" },
    { id: "my-learning", label: "我的学习", state: "enabled", current: isLearning,
      kind: "router", href: "/my-learning" },
    { id: "practice-center", label: "练习中心", state: "enabled", current: isPractice,
      kind: "router", href: "/practice" },
    { id: "ai-tutor", label: "AI 答疑", state: "coming-soon", current: isAi }
  ];
}
