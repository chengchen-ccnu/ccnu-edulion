<script setup lang="ts">import { AppShell } from "@edu/ui";</script>
<template><AppShell title="平台运营中心" audience="管理端"><template #navigation><RouterLink to="/admin/dashboard">概览</RouterLink><RouterLink to="/admin/reviews">审核</RouterLink><RouterLink to="/login">登录</RouterLink></template><RouterView /></AppShell></template>
