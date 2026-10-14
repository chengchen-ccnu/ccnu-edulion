<script setup lang="ts">
/**
 * 教师应用根壳提供冻结的七槽导航；身份只影响课程管理是否可操作，账号工具维持固定位置。
 * 页面标题和课程编辑上下文由 RouterView 内页面负责，本组件只处理退出这一会话副作用。
 */
import { availableWorkspaces, buildUnifiedLoginUrl, buildWorkspaceChooserUrl } from "@edu/auth";
import { AppShell, buildAccountTools } from "@edu/ui";
import { getPlatformUrls, useNotificationCounts } from "@edu/auth";
import { computed } from "vue";
import { useRoute } from "vue-router";
import { authStore } from "./auth";
import { buildTeacherNavigation } from "./navigation";

const route = useRoute();
const { counts, unavailable } = useNotificationCounts(authStore);
const loginUrl = buildUnifiedLoginUrl();
const workspaceChooserUrl = buildWorkspaceChooserUrl();
const workspaceLinks = computed(() => availableWorkspaces(authStore.state.currentUser));
const navigationItems = computed(() => buildTeacherNavigation(route.name?.toString(), authStore.state.currentUser));
const accountTools = computed(() => buildAccountTools({
  authenticated: authStore.state.status === "authenticated",
  notificationsHref: new URL("/notifications", getPlatformUrls().student).toString(),
  unreadCount: counts.value?.unreadCount,
  notificationsUnavailable: unavailable.value,
  accountLabel: authStore.state.currentUser?.displayName,
  loginHref: loginUrl,
  workspaceHref: workspaceLinks.value.length > 1 ? workspaceChooserUrl : undefined
}));
async function logout() {
  await authStore.logout();
  window.location.assign(loginUrl);
}
</script>

<template>
  <AppShell workspace-label="教师端" :navigation-items="navigationItems" :account-tools="accountTools"
    @tool-activate="logout">
    <RouterView />
  </AppShell>
</template>
