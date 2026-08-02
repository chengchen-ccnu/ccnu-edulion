<script setup lang="ts">
/** 教师应用根壳；公共课程中心跨应用跳转，教学管理仅对已恢复的教师身份显示。 */
import { availableWorkspaces, buildUnifiedLoginUrl, buildWorkspaceChooserUrl } from "@edu/auth";
import { AppShell } from "@edu/ui";
import { computed } from "vue";
import { authStore } from "./auth";
import { buildTeacherNavigation } from "./navigation";

const loginUrl = buildUnifiedLoginUrl();
const workspaceChooserUrl = buildWorkspaceChooserUrl();
const navigationItems = computed(() => buildTeacherNavigation(authStore.state.currentUser));
const canSwitchWorkspace = computed(() => availableWorkspaces(authStore.state.currentUser).length > 1);
async function logout() {
  await authStore.logout();
  window.location.assign(loginUrl);
}
</script>

<template>
  <AppShell title="教师工作台" audience="教师端">
    <template #navigation>
      <template v-for="item in navigationItems" :key="item.id">
        <RouterLink v-if="item.kind === 'router'" :to="item.href">{{ item.label }}</RouterLink>
        <a v-else :href="item.href">{{ item.label }}</a>
      </template>
      <a v-if="canSwitchWorkspace" :href="workspaceChooserUrl">选择工作台</a>
      <button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button>
      <a v-else :href="loginUrl">统一登录</a>
    </template>
    <RouterView />
  </AppShell>
</template>
