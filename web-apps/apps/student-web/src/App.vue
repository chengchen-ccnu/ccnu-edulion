<script setup lang="ts">
import { AppShell } from "@edu/ui";
import { authStore } from "./auth";
import { useRouter } from "vue-router";

const router = useRouter();
async function logout() {
  await authStore.logout();
  await router.push({ name: "login" });
}
</script>

<template>
  <AppShell title="学生学习空间" audience="学生端">
    <template #navigation>
      <RouterLink to="/">首页</RouterLink>
      <RouterLink to="/courses">课程</RouterLink>
      <button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button>
      <RouterLink v-else to="/login">登录</RouterLink>
    </template>
    <RouterView />
  </AppShell>
</template>
