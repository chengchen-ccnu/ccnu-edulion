<script setup lang="ts">
import type { AuthStore } from "./index";
import { reactive } from "vue";

const props = defineProps<{ store: AuthStore }>();
const emit = defineEmits<{ authenticated: [] }>();
const form = reactive({ account: "", password: "" });

async function submit() {
  if (await props.store.login(form.account, form.password)) emit("authenticated");
}
</script>

<template>
  <section class="auth-stage" aria-labelledby="login-heading">
    <div class="auth-login">
      <aside class="auth-login__intro" aria-label="平台介绍">
        <div class="auth-login__orb auth-login__orb--one" aria-hidden="true" />
        <div class="auth-login__orb auth-login__orb--two" aria-hidden="true" />
        <div class="auth-login__brand">
          <span class="auth-login__mark" aria-hidden="true">E</span>
          <span>EDU PLATFORM</span>
        </div>
        <div class="auth-login__intro-copy">
          <span class="auth-login__kicker">EDU LEARNING SPACE</span>
          <h2>让学习与成长，自然发生</h2>
          <p>使用一个账号，轻松进入属于你的学习空间。</p>
        </div>
      </aside>

      <div class="auth-login__panel">
        <div class="auth-login__heading">
          <span class="badge">安全登录</span>
          <h2 id="login-heading">欢迎回来</h2>
          <p>请输入平台账号，登录后将自动进入你有权访问的工作台。</p>
        </div>

        <form @submit.prevent="submit">
          <label>
            <span>账号</span>
            <input v-model.trim="form.account" autocomplete="username" inputmode="text"
              placeholder="请输入账号" :disabled="store.state.loading" />
            <small v-if="store.state.failure?.fieldErrors.account" class="field-error">
              {{ store.state.failure.fieldErrors.account }}
            </small>
          </label>
          <label>
            <span>密码</span>
            <input v-model="form.password" autocomplete="current-password" type="password"
              placeholder="请输入密码" :disabled="store.state.loading" />
            <small v-if="store.state.failure?.fieldErrors.password" class="field-error">
              {{ store.state.failure.fieldErrors.password }}
            </small>
          </label>
          <button :disabled="store.state.loading || !form.account || form.password.length < 8">
            <span>{{ store.state.loading ? "正在验证身份…" : "登录平台" }}</span>
            <span v-if="!store.state.loading" aria-hidden="true">→</span>
          </button>
        </form>

        <div v-if="store.state.failure" class="auth-error" :data-kind="store.state.failure.kind" role="alert">
          <strong>登录未完成</strong>
          <span>{{ store.state.failure.message }}</span>
          <small v-if="store.state.failure.retryAfter">请在 {{ store.state.failure.retryAfter }} 秒后重试</small>
        </div>

        <p class="auth-login__assurance"><span aria-hidden="true">●</span> 由 EDU Platform IAM 提供安全认证</p>
      </div>
    </div>
  </section>
</template>
