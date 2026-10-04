<script setup lang="ts">
/**
 * 管理应用根壳固定渲染教师审核与账号权限侧栏；仅启用审核，权限守卫保护直接深链接。
 * 组件只处理账号退出副作用，不把管理角色判断下沉到共享 AppShell。
 */
import { availableWorkspaces, buildUnifiedLoginUrl, buildWorkspaceChooserUrl } from "@edu/auth";
import { AppShell, buildAccountTools } from "@edu/ui";
import { getPlatformUrls, useNotificationCounts } from "@edu/auth";
import { computed } from "vue";
import { useRoute } from "vue-router";
import { authStore } from "./auth";
import { buildAdminNavigation } from "./navigation";

const route = useRoute();
const { counts, unavailable } = useNotificationCounts(authStore);
const loginUrl = buildUnifiedLoginUrl();
const workspaceChooserUrl = buildWorkspaceChooserUrl();
const workspaceLinks = computed(() => availableWorkspaces(authStore.state.currentUser));
const navigationItems = computed(() => buildAdminNavigation(route.name?.toString()));
const accountTools = computed(() => buildAccountTools({
  authenticated: authStore.state.status === "authenticated",
  notificationsHref: new URL("/notifications", getPlatformUrls().student).toString(),
  unreadCount: counts.value?.unreadCount,
  notificationsUnavailable: unavailable.value,
  accountLabel: authStore.state.currentUser?.displayName,
  loginHref: loginUrl,
  workspaceHref: workspaceLinks.value.length > 1 ? workspaceChooserUrl : undefined,
  workspaceLabel: authStore.state.status === "authenticated" ? "管理端" : undefined
}));
/** 调用既有退出流程后返回统一登录；共享认证清理身份，审核页监听身份变化清理私有资源。 */
async function logout() {
  await authStore.logout();
  window.location.assign(loginUrl);
}
</script>

<template>
  <AppShell workspace-label="管理端" navigation-placement="side" :navigation-items="navigationItems"
    :account-tools="accountTools" @tool-activate="logout">
    <RouterView />
  </AppShell>
</template>
