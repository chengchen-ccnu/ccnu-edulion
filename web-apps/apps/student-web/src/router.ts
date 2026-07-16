import { createRouter, createWebHistory } from "vue-router";

import LoginPage from "./views/LoginPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "home", component: PlaceholderPage, props: { heading: "学习首页", note: "首页业务将在后续纵向任务实现。" } },
    { path: "/courses", name: "courses", component: PlaceholderPage, props: { heading: "课程列表", note: "本轮仅冻结列表契约，不实现课程业务。" } },
    { path: "/courses/:courseId", name: "course-detail", component: PlaceholderPage, props: { heading: "课程详情", note: "详情字段来自 OpenAPI，页面实现留给课程前端任务。" } },
    { path: "/login", name: "login", component: LoginPage },
    { path: "/:pathMatch(.*)*", name: "not-found", component: PlaceholderPage, props: { heading: "页面不存在", note: "请检查访问地址。" } }
  ]
});
