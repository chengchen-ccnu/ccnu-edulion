<script setup lang="ts">import { AppShell } from "@edu/ui";</script>
<template><AppShell title="教师工作台" audience="教师端"><template #navigation><RouterLink to="/teacher/dashboard">工作台</RouterLink><RouterLink to="/teacher/courses">课程</RouterLink><RouterLink to="/login">登录</RouterLink></template><RouterView /></AppShell></template>
