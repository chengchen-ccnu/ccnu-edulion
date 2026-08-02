<script setup lang="ts">
/**
 * student-web 根壳同时承载角色中立的公开门户/统一登录，以及受保护的学生学习空间。
 * 公共课程页恢复会话后展示可进入的工作台与退出入口，页面数据本身不依赖登录结果。
 */
import { availableWorkspaces } from "@edu/auth";
import { AppShell } from "@edu/ui";
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStore } from "./auth";

const route = useRoute();
const router = useRouter();
const workspaceLinks = computed(() => availableWorkspaces(authStore.state.currentUser));
const singleWorkspace = computed(() => workspaceLinks.value.length === 1 ? workspaceLinks.value[0] : null);
const canSwitchWorkspace = computed(() => workspaceLinks.value.length > 1);
const isPublicCourse = computed(() => route.name === "courses" || route.name === "course-detail");
const isStudentLearningSpace = computed(() => route.meta.requiredRole === "STUDENT");
const canEnterStudentHome = computed(() => authStore.state.status !== "authenticated" || authStore.canAccess("STUDENT"));
const shellTitle = computed(() => isStudentLearningSpace.value ? "学生学习中心" : "Edu Lion 在线教育平台");
const shellAudience = computed(() => route.name === "login" ? "统一登录门户"
  : isPublicCourse.value ? "课程中心" : "公共门户");

async function logout() {
  await authStore.logout();
  await router.push({ name: "login" });
}
</script>

<template>
  <AppShell :title="shellTitle" :audience="shellAudience">
    <template #navigation>
      <RouterLink v-if="canEnterStudentHome" to="/">学生学习中心</RouterLink>
      <RouterLink to="/courses">课程中心</RouterLink>
      <a v-if="isPublicCourse && singleWorkspace" :href="singleWorkspace.href">{{ singleWorkspace.label }}</a>
      <RouterLink v-else-if="isPublicCourse && canSwitchWorkspace" to="/workspaces">选择工作台</RouterLink>
      <button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button>
      <RouterLink v-else to="/login">统一登录</RouterLink>
    </template>
    <RouterView />
  </AppShell>
</template>
