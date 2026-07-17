import { AccessDeniedPage, installAuthGuard } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import PlaceholderPage from "./views/PlaceholderPage.vue";

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: "/", redirect: "/admin/dashboard" },
  { path: "/admin/dashboard", name: "admin-dashboard", component: PlaceholderPage,
    meta: { requiresAuth: true, requiredRole: "ADMIN" },
    props: { heading: "运营概览", note: "已通过 ADMIN 身份和最小权限校验。" } },
  { path: "/admin/reviews", name: "admin-reviews", component: PlaceholderPage,
    meta: { requiresAuth: true, requiredRole: "ADMIN" },
    props: { heading: "审核工作台", note: "审核业务留给后续任务。" } },
  { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
    meta: { requiresAuth: true }, props: { store: authStore } },
  { path: "/:pathMatch(.*)*", component: PlaceholderPage,
    props: { heading: "页面不存在", note: "请检查访问地址。" } }
] });

installAuthGuard(router, authStore, { onLoginRedirect: url => window.location.assign(url) });
