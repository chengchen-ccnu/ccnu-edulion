<script setup lang="ts">
/**
 * 统一登录表单，面向所有平台角色；通过 AuthStore 调用 IAM 登录接口。
 * props.store 提供认证状态与字段错误，成功时 emit authenticated，失败时保留 IAM 错误和限流提示。
 */
import type { AuthStore } from "./index";
import { reactive } from "vue";

const props = defineProps<{ store: AuthStore }>();
const emit = defineEmits<{ authenticated: [] }>();
const form = reactive({ identity: "", password: "" });

async function submit() {
  if (await props.store.login(form.identity, form.password)) emit("authenticated");
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
          <span class="auth-login__kicker">EDU LION ONLINE EDUCATION</span>
          <h2>Edu Lion 在线教育平台</h2>
          <p>学生、教师和管理员使用同一账号登录。</p>
        </div>
      </aside>

      <div class="auth-login__panel">
        <div class="auth-login__heading">
          <span class="badge">安全登录</span>
          <h2 id="login-heading">统一登录门户</h2>
          <p>请输入已验证邮箱或中国大陆手机号，登录后将自动进入你有权访问的学习中心或工作台。</p>
        </div>

        <form @submit.prevent="submit">
          <label>
            <span>邮箱或手机号</span>
            <input v-model.trim="form.identity" autocomplete="username" inputmode="text"
              placeholder="请输入已验证邮箱或手机号" :disabled="store.state.loading" />
            <small v-if="store.state.failure?.fieldErrors.identity" class="field-error">
              {{ store.state.failure.fieldErrors.identity }}
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
          <button :disabled="store.state.loading || !form.identity || form.password.length < 8">
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
