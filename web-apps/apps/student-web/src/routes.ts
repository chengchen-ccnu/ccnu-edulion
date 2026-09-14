/** student-web 路由表：公共课程无需身份，我的学习与课程学习均按 learning:access 能力保护。 */
import { AccessDeniedPage, WorkspaceChooser } from "@edu/auth";
import type { RouteRecordRaw } from "vue-router";

import { authStore } from "./auth";
import CourseDetailPage from "./views/CourseDetailPage.vue";
import CourseLearningPage from "./views/CourseLearningPage.vue";
import CourseListPage from "./views/CourseListPage.vue";
import LoginPage from "./views/LoginPage.vue";
import RegisterPage from "./views/RegisterPage.vue";
import ForgotPasswordPage from "./views/ForgotPasswordPage.vue";
import ResetPasswordPage from "./views/ResetPasswordPage.vue";
import ProfilePage from "./views/ProfilePage.vue";
import ProfileSecurityPage from "./views/ProfileSecurityPage.vue";
import ApplyTeacherPage from "./views/ApplyTeacherPage.vue";
import ProtocolPage from "./views/ProtocolPage.vue";
import HelpPage from "./views/HelpPage.vue";
import MyLearningPage from "./views/MyLearningPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";
import PracticeAttemptPage from "./views/PracticeAttemptPage.vue";
import QuestionBankPage from "./views/QuestionBankPage.vue";
import SystemQuestionDetailPage from "./views/SystemQuestionDetailPage.vue";
import AttemptResultPage from "./views/AttemptResultPage.vue";
import WrongQuestionsPage from "./views/WrongQuestionsPage.vue";
import CourseTasksPage from "./views/CourseTasksPage.vue";
import AiHomePage from "./views/AiHomePage.vue";

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
  { path: "/learn/:courseId/tasks", name: "course-tasks", component: CourseTasksPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/learn/:courseId/tasks/:assessmentId", name: "course-task-attempt", component: PracticeAttemptPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/learn/:courseId/tasks/:assessmentId/result", name: "course-task-result", component: AttemptResultPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/learn/:courseId/:lessonId", name: "course-learning", component: CourseLearningPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/practice", name: "practice", component: QuestionBankPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/practice/questions/:questionId", name: "practice-question", component: SystemQuestionDetailPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/practice/:assessmentId", name: "practice-autonomous-attempt", component: PracticeAttemptPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/attempt/:attemptId/result", name: "attempt-result", component: AttemptResultPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/wrong-questions", name: "wrong-questions", component: WrongQuestionsPage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/ai", name: "ai", component: AiHomePage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/ai/conversations/:conversationId", name: "ai-conversation", component: AiHomePage,
    meta: { requiresAuth: true, requiredPermission: "learning:access" } },
  { path: "/login", name: "login", component: LoginPage },
  { path: "/register", name: "register", component: RegisterPage },
  { path: "/forgot-password", name: "forgot-password", component: ForgotPasswordPage },
  { path: "/reset-password", name: "reset-password", component: ResetPasswordPage },
  { path: "/terms", name: "terms", component: ProtocolPage, props: { type: "TERMS" } },
  { path: "/privacy", name: "privacy", component: ProtocolPage, props: { type: "PRIVACY" } },
  { path: "/help", name: "help", component: HelpPage },
  { path: "/profile", name: "profile", component: ProfilePage, meta: { requiresAuth: true } },
  { path: "/profile/security", name: "profile-security", component: ProfileSecurityPage, meta: { requiresAuth: true } },
  { path: "/apply-teacher", name: "apply-teacher", component: ApplyTeacherPage, meta: { requiresAuth: true } },
  { path: "/workspaces", name: "workspaces", component: WorkspaceChooser,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", name: "not-found", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
];
