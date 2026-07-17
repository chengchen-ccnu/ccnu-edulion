<script setup lang="ts">
import { AuthLoginForm } from "@edu/auth";
import { authStore } from "../auth";
</script>

<template><AuthLoginForm :store="authStore" heading="教师登录" account-placeholder="teacher_test_01" home-path="/teacher/dashboard" /></template>
