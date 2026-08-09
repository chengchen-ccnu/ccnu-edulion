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
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
] });

installAuthGuard(router, authStore, { onLoginRedirect: url => window.location.assign(url) });
