import { installAuthGuard } from "@edu/auth";
import { createRouter, createWebHistory } from "vue-router";

import { authStore } from "./auth";
import { studentRoutes } from "./routes";

export const router = createRouter({
  history: createWebHistory(),
  routes: studentRoutes
});

installAuthGuard(router, authStore);
