<script setup lang="ts">
import { AppShell } from "@edu/ui";
import { useRouter } from "vue-router";
import { authStore } from "./auth";
const router = useRouter();
async function logout() { await authStore.logout(); await router.push({ name: "login" }); }
</script>
<template><AppShell title="平台运营中心" audience="管理端"><template #navigation><RouterLink to="/admin/dashboard">概览</RouterLink><RouterLink to="/admin/reviews">审核</RouterLink><button v-if="authStore.state.status === 'authenticated'" type="button" @click="logout">退出</button><RouterLink v-else to="/login">登录</RouterLink></template><RouterView /></AppShell></template>
