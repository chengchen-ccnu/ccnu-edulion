/** student-web 路由表：公共课程无需身份，我的学习与课程学习均按 learning:access 能力保护。 */
import { AccessDeniedPage, WorkspaceChooser } from "@edu/auth";
import type { RouteRecordRaw } from "vue-router";

import { authStore } from "./auth";
import CourseDetailPage from "./views/CourseDetailPage.vue";
import CourseLearningPage from "./views/CourseLearningPage.vue";
import CourseListPage from "./views/CourseListPage.vue";
import LoginPage from "./views/LoginPage.vue";
import MyLearningPage from "./views/MyLearningPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";
import PracticeAttemptPage from "./views/PracticeAttemptPage.vue";
import PracticeCenterPage from "./views/PracticeCenterPage.vue";
import AttemptResultPage from "./views/AttemptResultPage.vue";
import WrongQuestionsPage from "./views/WrongQuestionsPage.vue";

/** 实际交给 Vue Router 的路由记录，也是公共/受保护边界测试的唯一来源。 */
export const studentRoutes: RouteRecordRaw[] = [
  { path: "/", name: "home", component: PlaceholderPage,
    meta: { requiresAuth: true, requiredRole: "STUDENT" },
    props: { heading: "学习首页", note: "已通过 STUDENT 身份和最小权限校验。" } },
  { path: "/courses", name: "courses", component: CourseListPage,
    meta: { restoreSession: true } },
  { path: "/courses/:courseId", name: "course-detail", component: CourseDetailPage,
    meta: { restoreSession: true } },
  { path: "/my-learning", name: "my-learning", component: MyLearningPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/learn/:courseId/:lessonId", name: "course-learning", component: CourseLearningPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/practice", name: "practice", component: PracticeCenterPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/practice/:assessmentId", name: "practice-attempt", component: PracticeAttemptPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/attempt/:attemptId/result", name: "attempt-result", component: AttemptResultPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/wrong-questions", name: "wrong-questions", component: WrongQuestionsPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/login", name: "login", component: LoginPage },
  { path: "/workspaces", name: "workspaces", component: WorkspaceChooser,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", name: "not-found", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
];
