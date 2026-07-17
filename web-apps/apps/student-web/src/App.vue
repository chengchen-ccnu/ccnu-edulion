<script setup lang="ts">
import { availableWorkspaces } from "@edu/auth";
import { AppShell } from "@edu/ui";
import { computed } from "vue";
import { useRouter } from "vue-router";
import { authStore } from "./auth";

const router = useRouter();
const canSwitchWorkspace = computed(() => availableWorkspaces(authStore.state.currentUser).length > 1);
async function logout() {
  await authStore.logout();
  await router.push({ name: "login" });
}
</script>

<template>
  <AppShell title="学生学习空间" audience="公开门户与学生端">
    <template #navigation>
      <RouterLink to="/">首页</RouterLink>
      <RouterLink to="/courses">课程</RouterLink>
      <RouterLink v-if="canSwitchWorkspace" to="/workspaces">选择工作台</RouterLink>
      <button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button>
      <RouterLink v-else to="/login">统一登录</RouterLink>
    </template>
    <RouterView />
  </AppShell>
</template>
