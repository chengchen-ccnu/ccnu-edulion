import { AccessDeniedPage, installAuthGuard } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import PlaceholderPage from "./views/PlaceholderPage.vue";
import TeacherReviewPage from "./views/TeacherReviewPage.vue";
import AccountUsersPage from "./views/AccountUsersPage.vue";

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: "/", redirect: "/admin/teacher-review" },
  { path: "/admin/dashboard", redirect: "/admin/teacher-review" },
  { path: "/admin/reviews", redirect: "/admin/teacher-review" },
  { path: "/admin/teacher-review", name: "admin-teacher-review", component: TeacherReviewPage,
    meta: { requiresAuth: true, requiredRole: "ADMIN", requiredPermission: "administration:access" } },
  { path: "/admin/users", name: "admin-users", component: AccountUsersPage,
    meta: { requiresAuth: true, requiredRole: "ADMIN", requiredPermission: "administration:access" } },
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
] });

installAuthGuard(router, authStore, { onLoginRedirect: url => window.location.assign(url) });
