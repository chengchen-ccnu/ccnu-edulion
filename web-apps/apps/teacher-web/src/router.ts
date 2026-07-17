import { AccessDeniedPage, installAuthGuard } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import PlaceholderPage from "./views/PlaceholderPage.vue";

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: "/", redirect: "/teacher/dashboard" },
  { path: "/teacher/dashboard", name: "teacher-dashboard", component: PlaceholderPage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" },
    props: { heading: "教师工作台", note: "已通过 TEACHER 身份和最小权限校验。" } },
  { path: "/teacher/courses", name: "teacher-courses", component: PlaceholderPage,
    meta: { requiresAuth: true, requiredRole: "TEACHER" },
    props: { heading: "课程管理", note: "课程业务留给 T05。" } },
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
] });

installAuthGuard(router, authStore, { onLoginRedirect: url => window.location.assign(url) });
