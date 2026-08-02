<script setup lang="ts">
/** 管理应用根壳；导航面向 ADMIN，页面访问仍由共享守卫校验角色与 administration:access 权限。 */
import { availableWorkspaces, buildUnifiedLoginUrl, buildWorkspaceChooserUrl } from "@edu/auth";
import { AppShell } from "@edu/ui";
import { computed } from "vue";
import { authStore } from "./auth";

const loginUrl = buildUnifiedLoginUrl();
const workspaceChooserUrl = buildWorkspaceChooserUrl();
const canSwitchWorkspace = computed(() => availableWorkspaces(authStore.state.currentUser).length > 1);
async function logout() {
  await authStore.logout();
  window.location.assign(loginUrl);
}
</script>

<template>
  <AppShell title="平台运营中心" audience="管理端">
    <template #navigation>
      <RouterLink to="/admin/dashboard">概览</RouterLink>
      <RouterLink to="/admin/reviews">审核</RouterLink>
      <a v-if="canSwitchWorkspace" :href="workspaceChooserUrl">选择工作台</a>
      <button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button>
      <a v-else :href="loginUrl">统一登录</a>
    </template>
    <RouterView />
  </AppShell>
</template>
