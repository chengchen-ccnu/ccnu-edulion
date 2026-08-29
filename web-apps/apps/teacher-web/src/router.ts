import { AccessDeniedPage, installAuthGuard } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import PlaceholderPage from "./views/PlaceholderPage.vue";
import TeacherCourseCreatePage from "./views/TeacherCourseCreatePage.vue";
import TeacherCourseCurriculumPage from "./views/TeacherCourseCurriculumPage.vue";
import TeacherCourseEditPage from "./views/TeacherCourseEditPage.vue";
import TeacherCourseListPage from "./views/TeacherCourseListPage.vue";

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: "/", redirect: "/teacher/courses" },
  { path: "/teacher/dashboard", name: "teacher-dashboard", component: PlaceholderPage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" },
    props: { heading: "教师工作台", note: "已通过 TEACHER 身份和最小权限校验。" } },
  { path: "/teacher/courses", name: "teacher-courses", component: TeacherCourseListPage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" } },
  { path: "/teacher/courses/new", name: "teacher-course-new", component: TeacherCourseCreatePage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" } },
  { path: "/teacher/courses/:courseId/edit", name: "teacher-course-edit", component: TeacherCourseEditPage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" } },
  { path: "/teacher/courses/:courseId/curriculum", name: "teacher-course-curriculum", component: TeacherCourseCurriculumPage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" } },
  { path: "/teacher/questions", name: "teacher-questions", component: () => import("./views/TeacherQuestionListPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" } },
  { path: "/teacher/questions/new", name: "teacher-question-new", component: () => import("./views/TeacherQuestionEditorPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" }, props: { mode: "create" } },
  { path: "/teacher/questions/:questionId", name: "teacher-question-detail", component: () => import("./views/TeacherQuestionDetailPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" } },
  { path: "/teacher/questions/:questionId/edit", name: "teacher-question-edit", component: () => import("./views/TeacherQuestionEditorPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" }, props: { mode: "edit" } },
  { path: "/teacher/question-imports", name: "teacher-question-imports", component: () => import("./views/TeacherQuestionImportListPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" } },
  { path: "/teacher/question-imports/:importTaskId", name: "teacher-question-import-detail", component: () => import("./views/TeacherQuestionImportDetailPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" } },
  { path: "/teacher/assessments", name: "teacher-assessments", component: () => import("./views/TeacherAssessmentPage.vue"),
    meta: { requiresAuth: true, requiredPermission: "teaching:access" } },
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
] });

installAuthGuard(router, authStore, { onLoginRedirect: url => window.location.assign(url) });
