<script setup lang="ts">
import { AuthLoginForm } from "@edu/auth";
import { authStore } from "../auth";
</script>

<template><AuthLoginForm :store="authStore" heading="管理员登录" account-placeholder="admin_test_01" home-path="/admin/dashboard" /></template>
