<script setup lang="ts">
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
