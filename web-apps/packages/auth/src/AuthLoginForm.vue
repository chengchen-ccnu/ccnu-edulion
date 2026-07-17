<script setup lang="ts">
import type { AuthStore } from "./index";
import { reactive } from "vue";
import { useRoute, useRouter } from "vue-router";

const props = defineProps<{ store: AuthStore; heading: string; accountPlaceholder: string; homePath: string }>();
const form = reactive({ account: "", password: "" });
const router = useRouter();
const route = useRoute();

async function submit() {
  if (await props.store.login(form.account, form.password)) {
    const redirect = typeof route.query.redirect === "string" && route.query.redirect.startsWith("/")
      ? route.query.redirect : props.homePath;
    await router.replace(redirect);
  }
}
</script>

<template>
  <section class="card login-card" aria-live="polite">
    <span class="badge">安全登录</span>
    <h2>{{ heading }}</h2>
    <p>Access Token 仅保存在当前页面内存中，长期会话由 HttpOnly Cookie 续期。</p>
    <form @submit.prevent="submit">
      <label>账号
        <input v-model.trim="form.account" autocomplete="username" :placeholder="accountPlaceholder" :disabled="store.state.loading" />
        <small v-if="store.state.failure?.fieldErrors.account" class="field-error">{{ store.state.failure.fieldErrors.account }}</small>
      </label>
      <label>密码
        <input v-model="form.password" autocomplete="current-password" type="password" placeholder="至少 8 位" :disabled="store.state.loading" />
        <small v-if="store.state.failure?.fieldErrors.password" class="field-error">{{ store.state.failure.fieldErrors.password }}</small>
      </label>
      <button :disabled="store.state.loading || !form.account || form.password.length < 8">
        {{ store.state.loading ? "正在登录…" : "登录" }}
      </button>
    </form>
    <p v-if="store.state.failure" class="auth-error" :data-kind="store.state.failure.kind">
      {{ store.state.failure.message }}
      <span v-if="store.state.failure.retryAfter">（{{ store.state.failure.retryAfter }} 秒后重试）</span>
    </p>
  </section>
</template>
