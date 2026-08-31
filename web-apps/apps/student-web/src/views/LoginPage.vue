<script setup lang="ts">
/**
 * 统一身份登录页：密码与一次性验证码共用同一张身份卡片。
 * CAPTCHA、验证码挑战和访问令牌只保存在本页/认证闭包内存，成功后交给共享 AuthStore 路由。
 */
import type { ArithmeticCaptcha, AuthenticationChannelCapabilities, IdentityType, VerificationChallenge } from "@edu/api-client";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { selectPostLoginDestination } from "@edu/auth";

import { accountSecurityApi } from "../account-security-api";
import { accountError } from "../account-errors";
import { authStore } from "../auth";
import AccountFrame from "../components/AccountFrame.vue";
import CaptchaField from "../components/CaptchaField.vue";

const route = useRoute();
const router = useRouter();
const mode = ref<"password" | "verification">("password");
const form = reactive({ identity: "", password: "", verificationCode: "", captchaAnswer: "" });
const captcha = ref<ArithmeticCaptcha | null>(null);
const challenge = ref<VerificationChallenge | null>(null);
const capabilities = ref<AuthenticationChannelCapabilities | null>(null);
const localError = ref("");
const retryAfter = ref<number | null>(null);
const phoneCodeAvailable = computed(() => capabilities.value?.channels
  .find(item => item.identityType === "PHONE")?.verificationLoginAvailable ?? false);
const identityType = computed<IdentityType>(() => form.identity.includes("@") ? "EMAIL" : "PHONE");
const passwordCaptchaVisible = computed(() => ["IAM.CAPTCHA_REQUIRED", "IAM.CAPTCHA_INVALID"]
  .includes(authStore.state.failure?.code ?? "") || Boolean(authStore.state.failure?.fieldErrors.captcha));
const statusNotice = computed(() => {
  if (route.query.registered === "1") return "账号已创建。请输入刚才注册的邮箱或手机号完成登录。";
  if (route.query.passwordChanged === "1") return "密码已修改，所有设备均需使用新密码重新登录。";
  if (route.query.reauthenticated === "1") return "身份已变更，请使用当前已验证身份重新登录。";
  if (route.query.reset === "1") return "密码已重置，请使用新密码登录。";
  if (route.query.logout === "unconfirmed") return "本地登录状态已清理，但服务端退出请求未确认。联网后请重新登录并在账号安全页退出全部设备。";
  return "";
});

onMounted(async () => {
  try { capabilities.value = await accountSecurityApi.capabilities(); } catch { /* 失败时保持邮箱默认入口，不猜测手机可用。 */ }
});

watch(mode, next => {
  localError.value = "";
  challenge.value = null;
  form.verificationCode = "";
  form.captchaAnswer = "";
  if (next === "verification") void loadCaptcha();
});

async function loadCaptcha() {
  try {
    captcha.value = await accountSecurityApi.createCaptcha();
    form.captchaAnswer = "";
  } catch (error) {
    const failure = accountError(error, "算式验证码加载失败，请稍后重试。");
    localError.value = failure.message;
    retryAfter.value = failure.retryAfter;
  }
}

async function refreshCaptcha() {
  if (!captcha.value) return loadCaptcha();
  try {
    captcha.value = await accountSecurityApi.refreshCaptcha(captcha.value.captchaId);
    form.captchaAnswer = "";
  } catch { await loadCaptcha(); }
}

async function submitPassword() {
  localError.value = "";
  if (passwordCaptchaVisible.value && !captcha.value) await loadCaptcha();
  const success = await authStore.login(form.identity, form.password,
    passwordCaptchaVisible.value && captcha.value ? { captchaId: captcha.value.captchaId, answer: form.captchaAnswer } : undefined);
  if (success) return completeLogin();
  if (authStore.state.failure?.code === "IAM.CAPTCHA_INVALID") await loadCaptcha();
  else if (passwordCaptchaVisible.value && !captcha.value) await loadCaptcha();
}

async function sendVerificationCode() {
  localError.value = "";
  retryAfter.value = null;
  if (identityType.value === "PHONE" && !phoneCodeAvailable.value) {
    localError.value = "当前环境未启用手机验证码，请使用邮箱或密码登录。";
    return;
  }
  if (!captcha.value) await loadCaptcha();
  if (!captcha.value || !form.captchaAnswer) {
    localError.value = "请先完成算式验证码。";
    return;
  }
  try {
    challenge.value = await accountSecurityApi.createVerificationLoginChallenge(identityType.value, form.identity,
      { captchaId: captcha.value.captchaId, answer: form.captchaAnswer });
  } catch (error) {
    const failure = accountError(error, "验证码发送失败，请稍后重试。");
    localError.value = failure.message;
    retryAfter.value = failure.retryAfter;
    if (failure.status === 422) { captcha.value = null; await loadCaptcha(); }
  }
}

async function submitVerificationLogin() {
  localError.value = "";
  if (!challenge.value) { localError.value = "请先发送验证码。"; return; }
  if (await authStore.loginWithVerificationCode(challenge.value.challengeId, identityType.value,
    form.identity, form.verificationCode)) return completeLogin();
}

async function completeLogin() {
  const user = authStore.state.currentUser;
  if (!user) return;
  const redirect = typeof route.query.redirect === "string" ? route.query.redirect : undefined;
  const destination = selectPostLoginDestination(user, redirect);
  if (destination.kind === "choose") return router.replace({ name: "workspaces" });
  if (destination.kind === "forbidden") return router.replace({ name: "forbidden" });
  const targetUrl = new URL(destination.target.href);
  if (targetUrl.origin === window.location.origin) return router.replace(destination.target.path);
  window.location.assign(destination.target.href);
}
</script>

<template>
  <AccountFrame badge="安全登录" title="统一登录门户"
    description="使用已验证邮箱或中国大陆手机号登录，系统会把你带到有权访问的学习空间或工作台。">
    <p v-if="statusNotice" class="account-notice" role="status">{{ statusNotice }}</p>
    <div class="account-tabs" role="tablist" aria-label="登录方式">
      <button type="button" :aria-selected="mode === 'password'" @click="mode = 'password'">密码登录</button>
      <button type="button" :aria-selected="mode === 'verification'" @click="mode = 'verification'">验证码登录</button>
    </div>
    <form v-if="mode === 'password'" class="account-form" @submit.prevent="submitPassword">
      <label><span>邮箱或手机号</span><input v-model.trim="form.identity" autocomplete="username"
        placeholder="请输入已验证邮箱或手机号" required></label>
      <label><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password"
        placeholder="请输入密码" minlength="8" required></label>
      <CaptchaField v-if="passwordCaptchaVisible" :captcha="captcha" :answer="form.captchaAnswer"
        :disabled="authStore.state.loading" @update:answer="form.captchaAnswer = $event" @refresh="refreshCaptcha" />
      <button class="button button--primary account-submit" type="submit" :disabled="authStore.state.loading">
        {{ authStore.state.loading ? "正在验证身份…" : "登录平台" }}
      </button>
    </form>
    <form v-else class="account-form" @submit.prevent="submitVerificationLogin">
      <label><span>邮箱或手机号</span><input v-model.trim="form.identity" autocomplete="username"
        placeholder="请输入已验证邮箱或手机号" required></label>
      <CaptchaField :captcha="captcha" :answer="form.captchaAnswer" :disabled="authStore.state.loading"
        @update:answer="form.captchaAnswer = $event" @refresh="refreshCaptcha" />
      <button class="button button--quiet" type="button" :disabled="authStore.state.loading || !form.identity || !form.captchaAnswer"
        @click="sendVerificationCode">{{ challenge ? "重新发送验证码" : "发送验证码" }}</button>
      <label><span>一次性验证码</span><input v-model.trim="form.verificationCode" inputmode="numeric"
        autocomplete="one-time-code" maxlength="6" placeholder="请输入 6 位验证码" required></label>
      <button class="button button--primary account-submit" type="submit"
        :disabled="authStore.state.loading || !challenge">{{ authStore.state.loading ? "正在登录…" : "验证码登录" }}</button>
      <small v-if="challenge" class="account-hint">验证码已发送至 {{ challenge.deliveryHint }}，约 {{ challenge.expiresIn }} 秒有效。</small>
    </form>
    <div v-if="authStore.state.failure || localError" class="auth-error" role="alert">
      <strong>登录未完成</strong><span>{{ localError || authStore.state.failure?.message }}</span>
      <small v-if="retryAfter ?? authStore.state.failure?.retryAfter">请在 {{ retryAfter ?? authStore.state.failure?.retryAfter }} 秒后重试</small>
    </div>
    <nav class="account-links" aria-label="账号操作"><RouterLink to="/register">注册普通学习账号</RouterLink>
      <RouterLink to="/forgot-password">忘记密码？</RouterLink></nav>
    <p class="account-assurance"><span aria-hidden="true">●</span> Access Token 仅在当前页面会话内存中使用，不提供“记住我”</p>
  </AccountFrame>
</template>
