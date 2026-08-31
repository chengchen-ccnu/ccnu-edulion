<script setup lang="ts">
/** 消费当前标签页内的找回挑战并重置密码；challengeId 与身份不进入 URL 或浏览器历史。 */
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { accountSecurityApi } from "../account-security-api";
import { accountError } from "../account-errors";
import { clearResetChallenge, readResetChallenge } from "../reset-challenge";
import AccountFrame from "../components/AccountFrame.vue";

const router = useRouter();
const pending = ref(readResetChallenge());
const form = reactive({ code: "", password: "", confirmPassword: "" });
const errorMessage = ref("");
const fieldErrors = ref<Record<string, string>>({});

onMounted(() => { pending.value = readResetChallenge(); });

async function submit() {
  errorMessage.value = ""; fieldErrors.value = {};
  if (!pending.value) { errorMessage.value = "找回挑战已失效，请返回找回密码页重新开始。"; return; }
  if (form.password !== form.confirmPassword) { fieldErrors.value.confirmPassword = "两次输入的密码不一致"; return; }
  try {
    await accountSecurityApi.resetPassword({ challengeId: pending.value.challenge.challengeId,
      identityType: pending.value.identityType, target: pending.value.target,
      verificationCode: form.code, newPassword: form.password });
    clearResetChallenge();
    await router.replace({ name: "login", query: { reset: "1" } });
  } catch (error) { const failure = accountError(error, "密码重置未完成，请检查验证码和输入后重试。"); errorMessage.value = failure.message; fieldErrors.value = failure.fieldErrors; }
}
</script>

<template>
  <AccountFrame badge="重置密码" title="设置新密码" description="验证码只在当前标签页受控消费，重置成功后旧设备会全部退出。">
    <div v-if="pending" class="account-hint">验证码已发送至 {{ pending.challenge.deliveryHint }}，约 {{ pending.challenge.expiresIn }} 秒有效。</div>
    <div v-else class="account-error-block" role="alert">找回挑战不存在或页面已刷新，请从找回密码页重新开始。</div>
    <form v-if="pending" class="account-form" @submit.prevent="submit">
      <label><span>一次性验证码</span><input v-model.trim="form.code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="请输入 6 位验证码" required></label>
      <label><span>新密码</span><input v-model="form.password" type="password" autocomplete="new-password" minlength="12" placeholder="至少 12 位" required><small v-if="fieldErrors.newPassword" class="field-error">{{ fieldErrors.newPassword }}</small></label>
      <label><span>确认新密码</span><input v-model="form.confirmPassword" type="password" autocomplete="new-password" minlength="12" placeholder="再次输入新密码" required><small v-if="fieldErrors.confirmPassword" class="field-error">{{ fieldErrors.confirmPassword }}</small></label>
      <button class="button button--primary account-submit" type="submit">重置密码</button>
    </form>
    <div v-if="errorMessage" class="auth-error" role="alert"><strong>操作未完成</strong><span>{{ errorMessage }}</span></div>
    <nav class="account-links" aria-label="账号操作"><RouterLink to="/forgot-password">返回找回密码</RouterLink><RouterLink to="/login">返回登录</RouterLink></nav>
  </AccountFrame>
</template>
