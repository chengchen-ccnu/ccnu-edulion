<script setup lang="ts">
/** 找回密码页：无论身份是否存在都使用一致的挑战回执，避免把账号存在性泄露给页面。 */
import type { ArithmeticCaptcha, AuthenticationChannelCapabilities, IdentityType, VerificationChallenge } from "@edu/api-client";
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { accountSecurityApi } from "../account-security-api";
import { accountError } from "../account-errors";
import AccountFrame from "../components/AccountFrame.vue";
import CaptchaField from "../components/CaptchaField.vue";
import { saveResetChallenge } from "../reset-challenge";

const router = useRouter();
const form = reactive({ identityType: "EMAIL" as IdentityType, target: "", captchaAnswer: "" });
const captcha = ref<ArithmeticCaptcha | null>(null);
const challenge = ref<VerificationChallenge | null>(null);
const capabilities = ref<AuthenticationChannelCapabilities | null>(null);
const errorMessage = ref("");
const fieldErrors = ref<Record<string, string>>({});
const retryAfter = ref<number | null>(null);
const phoneAvailable = computed(() => capabilities.value?.channels
  .find(item => item.identityType === "PHONE")?.verificationDeliveryAvailable ?? false);

onMounted(async () => {
  try { capabilities.value = await accountSecurityApi.capabilities(); } catch { /* 邮箱默认入口保持可见。 */ }
  await refreshCaptcha();
});

async function refreshCaptcha() {
  try { captcha.value = captcha.value
    ? await accountSecurityApi.refreshCaptcha(captcha.value.captchaId)
    : await accountSecurityApi.createCaptcha(); form.captchaAnswer = ""; }
  catch (error) { setError(error, "算式验证码加载失败，请稍后重试。"); }
}

async function sendCode() {
  clearError();
  if (form.identityType === "PHONE" && !phoneAvailable.value) { errorMessage.value = "当前环境未启用手机找回，请使用邮箱找回。"; return; }
  if (!captcha.value || !form.captchaAnswer) { errorMessage.value = "请先完成算式验证码。"; return; }
  try {
    challenge.value = await accountSecurityApi.passwordResetChallenge(form.identityType, form.target,
      { captchaId: captcha.value.captchaId, answer: form.captchaAnswer });
    saveResetChallenge(challenge.value, form.identityType, form.target);
    await router.push({ name: "reset-password" });
  }
  catch (error) { setError(error, "请求未完成，请稍后重试。"); if (retryAfter.value === null) { captcha.value = null; await refreshCaptcha(); } }
}

function choose(type: IdentityType) { if (type === "PHONE" && !phoneAvailable.value) return; form.identityType = type; challenge.value = null; }
function clearError() { errorMessage.value = ""; fieldErrors.value = {}; retryAfter.value = null; }
function setError(error: unknown, fallback: string) { const failure = accountError(error, fallback); errorMessage.value = failure.message; fieldErrors.value = failure.fieldErrors; retryAfter.value = failure.retryAfter; }
</script>

<template>
  <AccountFrame badge="找回密码" title="重置账号密码" description="输入已验证身份接收一次性验证码。无论身份是否存在，系统都返回一致的外部结果。">
    <div class="account-tabs" role="tablist" aria-label="找回身份类型"><button type="button" :aria-selected="form.identityType === 'EMAIL'" @click="choose('EMAIL')">邮箱找回</button><button type="button" :aria-selected="form.identityType === 'PHONE'" :disabled="!phoneAvailable" @click="choose('PHONE')">手机找回<span v-if="!phoneAvailable">（未启用）</span></button></div>
    <div class="account-form">
      <label><span>{{ form.identityType === 'EMAIL' ? '邮箱' : '中国大陆手机号' }}</span><input v-model.trim="form.target" :type="form.identityType === 'EMAIL' ? 'email' : 'tel'" autocomplete="username" placeholder="请输入已验证身份" required><small v-if="fieldErrors.target" class="field-error">{{ fieldErrors.target }}</small></label>
      <CaptchaField :captcha="captcha" :answer="form.captchaAnswer" @update:answer="form.captchaAnswer = $event" @refresh="refreshCaptcha" />
      <button class="button button--quiet" type="button" :disabled="!form.target || !form.captchaAnswer" @click="sendCode">{{ challenge ? '重新发送验证码' : '发送验证码' }}</button>
      <p class="account-hint">验证码发送后将进入安全的重置页面。</p>
    </div>
    <div v-if="errorMessage" class="auth-error" role="alert"><strong>操作未完成</strong><span>{{ errorMessage }}</span><small v-if="retryAfter !== null">请在 {{ retryAfter }} 秒后重试</small></div>
    <nav class="account-links" aria-label="账号操作"><RouterLink to="/login">返回登录</RouterLink></nav>
  </AccountFrame>
</template>
