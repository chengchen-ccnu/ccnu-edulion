<script setup lang="ts">
/**
 * 全平台统一登录入口；登录成功后依据 /users/me 角色权限和安全 redirect 选择工作台。
 * 无权限进入拒绝页，多角色进入选择页，非法外部 redirect 会被丢弃。
 */
import { AuthLoginForm, selectPostLoginDestination } from "@edu/auth";
import { useRoute, useRouter } from "vue-router";
import { authStore } from "../auth";

const route = useRoute();
const router = useRouter();

async function completeLogin() {
  const user = authStore.state.currentUser;
  if (!user) return;
  const redirect = typeof route.query.redirect === "string" ? route.query.redirect : undefined;
  const destination = selectPostLoginDestination(user, redirect);
  if (destination.kind === "choose") {
    await router.replace({ name: "workspaces" });
    return;
  }
  if (destination.kind === "forbidden") {
    await router.replace({ name: "forbidden" });
    return;
  }
  const targetUrl = new URL(destination.target.href);
  if (targetUrl.origin === window.location.origin) {
    await router.replace(destination.target.path);
  } else {
    window.location.assign(destination.target.href);
  }
}
</script>

<template><AuthLoginForm :store="authStore" @authenticated="completeLogin" /></template>
