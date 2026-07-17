<script setup lang="ts">
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
  <AppShell title="教师工作台" audience="教师端">
    <template #navigation>
      <RouterLink to="/teacher/dashboard">工作台</RouterLink>
      <RouterLink to="/teacher/courses">课程</RouterLink>
      <a v-if="canSwitchWorkspace" :href="workspaceChooserUrl">选择工作台</a>
      <button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button>
      <a v-else :href="loginUrl">统一登录</a>
    </template>
    <RouterView />
  </AppShell>
</template>
