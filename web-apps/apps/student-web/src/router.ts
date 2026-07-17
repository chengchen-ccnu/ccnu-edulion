import { AccessDeniedPage, installAuthGuard, WorkspaceChooser } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import LoginPage from "./views/LoginPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "home", component: PlaceholderPage,
      meta: { requiresAuth: true, requiredRole: "STUDENT" },
      props: { heading: "学习首页", note: "已通过 STUDENT 身份和最小权限校验。" } },
    { path: "/courses", name: "courses", component: PlaceholderPage,
      meta: { requiresAuth: true, requiredRole: "STUDENT" },
      props: { heading: "课程列表", note: "课程业务留给 T05。" } },
    { path: "/courses/:courseId", name: "course-detail", component: PlaceholderPage,
      meta: { requiresAuth: true, requiredRole: "STUDENT" },
      props: { heading: "课程详情", note: "详情字段来自 OpenAPI，页面实现留给课程前端任务。" } },
    { path: "/login", name: "login", component: LoginPage },
    { path: "/workspaces", name: "workspaces", component: WorkspaceChooser,
      meta: { requiresAuth: true }, props: { store: authStore } },
    { path: "/forbidden", name: "forbidden", component: AccessDeniedPage,
      meta: { requiresAuth: true }, props: { store: authStore } },
    { path: "/:pathMatch(.*)*", name: "not-found", component: PlaceholderPage,
      props: { heading: "页面不存在", note: "请检查访问地址。" } }
  ]
});

installAuthGuard(router, authStore);
