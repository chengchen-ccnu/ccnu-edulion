import { installAuthGuard } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import { studentRoutes } from "./routes";

export const router = createRouter({
  history: createWebHistory(),
  routes: studentRoutes
});

/** 开户只接收手动正文；直接输入、同页导航及刷新均剔除所有查询与片段，不恢复他人会话。 */
router.beforeEach(to => to.name === "accept-admin-invitation" && (to.hash || Object.keys(to.query).length)
  ? { name: "accept-admin-invitation", query: {}, hash: "", replace: true } : true);
installAuthGuard(router, authStore);
