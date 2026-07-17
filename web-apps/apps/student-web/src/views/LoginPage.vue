<script setup lang="ts">
import { AuthLoginForm } from "@edu/auth";
import { authStore } from "../auth";
</script>

<template><AuthLoginForm :store="authStore" heading="学生登录" account-placeholder="student_test_01" home-path="/" /></template>
