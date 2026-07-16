<script setup lang="ts">
import { AppShell } from "@edu/ui";
</script>

<template>
  <AppShell title="学生学习空间" audience="学生端">
    <template #navigation>
      <RouterLink to="/">首页</RouterLink>
      <RouterLink to="/courses">课程</RouterLink>
      <RouterLink to="/login">登录</RouterLink>
    </template>
    <RouterView />
  </AppShell>
</template>
