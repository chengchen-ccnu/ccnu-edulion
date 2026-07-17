<script setup lang="ts">
import { AppShell } from "@edu/ui";
import { useRouter } from "vue-router";
import { authStore } from "./auth";
const router = useRouter();
async function logout() { await authStore.logout(); await router.push({ name: "login" }); }
</script>
<template><AppShell title="教师工作台" audience="教师端"><template #navigation><RouterLink to="/teacher/dashboard">工作台</RouterLink><RouterLink to="/teacher/courses">课程</RouterLink><button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button><RouterLink v-else to="/login">登录</RouterLink></template><RouterView /></AppShell></template>
