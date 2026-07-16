<script setup lang="ts">
import { DEFAULT_MOCK_BASE_URL } from "@edu/api-client";
</script>

<template>
  <section class="card login-card">
    <span class="badge">登录占位</span>
    <h2>欢迎回来</h2>
    <p>身份认证、Token 存储和权限守卫将在 T04 实现。</p>
    <label>账号<input disabled placeholder="student@example.com" /></label>
    <label>密码<input disabled type="password" placeholder="••••••••" /></label>
    <button disabled>登录</button>
    <small>OpenAPI Mock：{{ DEFAULT_MOCK_BASE_URL }}</small>
  </section>
</template>
