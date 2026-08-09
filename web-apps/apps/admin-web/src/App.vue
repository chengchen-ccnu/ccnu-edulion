<script setup lang="ts">
/**
 * 管理应用根壳固定渲染 T17C 规划侧栏；当前入口均为建设中且不可跳转，权限守卫仍保护直接深链接。
 * 组件只处理账号退出副作用，不把管理角色判断下沉到共享 AppShell。
 */
import { availableWorkspaces, buildUnifiedLoginUrl, buildWorkspaceChooserUrl } from "@edu/auth";
import { AppShell, buildAccountTools } from "@edu/ui";
import { computed } from "vue";
import { useRoute } from "vue-router";
import { authStore } from "./auth";
import { buildAdminNavigation } from "./navigation";

const route = useRoute();
const loginUrl = buildUnifiedLoginUrl();
const workspaceChooserUrl = buildWorkspaceChooserUrl();
const workspaceLinks = computed(() => availableWorkspaces(authStore.state.currentUser));
const navigationItems = computed(() => buildAdminNavigation(route.name?.toString()));
const accountTools = computed(() => buildAccountTools({
  authenticated: authStore.state.status === "authenticated",
  accountLabel: authStore.state.currentUser?.displayName,
  loginHref: loginUrl,
  workspaceHref: workspaceLinks.value.length > 1 ? workspaceChooserUrl : undefined,
  workspaceLabel: authStore.state.status === "authenticated" ? "管理端" : undefined
}));
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
