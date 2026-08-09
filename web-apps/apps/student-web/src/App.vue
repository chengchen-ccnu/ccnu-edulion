<script setup lang="ts">
/**
 * student-web 根壳提供规格冻结的固定导航；页面、登录状态和工作台数量只改变高亮与账号工具状态。
 * 课程标题和学习上下文继续由 RouterView 内页面负责，根壳没有数据写入副作用。
 */
import { availableWorkspaces } from "@edu/auth";
import { AppShell, buildAccountTools } from "@edu/ui";
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStore } from "./auth";
import { buildStudentNavigation } from "./navigation";

const route = useRoute();
const router = useRouter();
const workspaceLinks = computed(() => availableWorkspaces(authStore.state.currentUser));
const navigationItems = computed(() => buildStudentNavigation(route.name?.toString()));
const accountTools = computed(() => buildAccountTools({
  authenticated: authStore.state.status === "authenticated",
  accountLabel: authStore.state.currentUser?.displayName,
  loginHref: "/login",
  workspaceHref: workspaceLinks.value.length > 1 ? "/workspaces" : undefined,
  workspaceLabel: authStore.state.status === "authenticated" ? "学习端" : undefined
}));

async function logout() {
  await authStore.logout();
  await router.push({ name: "login" });
}
</script>

<template>
  <AppShell workspace-label="学习端" :navigation-items="navigationItems" :account-tools="accountTools"
    @tool-activate="logout">
    <RouterView />
  </AppShell>
</template>
