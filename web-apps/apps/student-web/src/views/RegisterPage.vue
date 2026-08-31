<script setup lang="ts">
/**
 * 普通学习账号注册页，默认邮箱、按服务端能力切换手机；注册只能授予学习能力。
 * 协议正文和版本来自当前服务端文档，验证码挑战与一次性验证码均只在内存中短暂保存。
 */
import type { ArithmeticCaptcha, AuthenticationChannelCapabilities, CurrentProtocolDocuments, IdentityType, VerificationChallenge } from "@edu/api-client";
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { accountSecurityApi } from "../account-security-api";
import { accountError, randomRequestId } from "../account-errors";
import { authStore } from "../auth";
import AccountFrame from "../components/AccountFrame.vue";
import CaptchaField from "../components/CaptchaField.vue";

const router = useRouter();
const form = reactive({ identityType: "EMAIL" as IdentityType, target: "", nickname: "", password: "", confirmPassword: "", code: "", captchaAnswer: "" });
const captcha = ref<ArithmeticCaptcha | null>(null);
const challenge = ref<VerificationChallenge | null>(null);
const protocols = ref<CurrentProtocolDocuments | null>(null);
const capabilities = ref<AuthenticationChannelCapabilities | null>(null);
const agreed = reactive({ terms: false, privacy: false });
const activeProtocol = ref<CurrentProtocolDocuments["documents"][number] | null>(null);
const protocolDialog = ref<HTMLElement | null>(null);
const errorMessage = ref("");
const fieldErrors = ref<Record<string, string>>({});
const retryAfter = ref<number | null>(null);
const phoneAvailable = computed(() => capabilities.value?.channels
  .find(item => item.identityType === "PHONE")?.registrationAvailable ?? false);
const currentTerms = computed(() => protocols.value?.documents.find(item => item.documentType === "TERMS"));
const currentPrivacy = computed(() => protocols.value?.documents.find(item => item.documentType === "PRIVACY"));

onMounted(async () => {
  if (authStore.state.status === "unknown" && await authStore.restore()) { await router.replace({ name: "profile" }); return; }
  if (authStore.state.status === "authenticated") { await router.replace({ name: "profile" }); return; }
  try { protocols.value = await accountSecurityApi.protocols(); }
  catch (error) { setError(error, "当前协议加载失败，暂时无法注册。"); }
  try { capabilities.value = await accountSecurityApi.capabilities(); }
  catch { /* 邮箱是默认且唯一安全的可用入口，不把查询失败猜成手机可用。 */ }
  await loadCaptcha();
});

async function loadCaptcha() {
  try { captcha.value = await accountSecurityApi.createCaptcha(); form.captchaAnswer = ""; }
  catch (error) { setError(error, "算式验证码加载失败，请稍后重试。"); }
}

async function refreshCaptcha() {
  if (!captcha.value) return loadCaptcha();
  try { captcha.value = await accountSecurityApi.refreshCaptcha(captcha.value.captchaId); form.captchaAnswer = ""; }
  catch { await loadCaptcha(); }
}

async function sendCode() {
  clearError();
  if (form.identityType === "PHONE" && !phoneAvailable.value) { errorMessage.value = "当前环境未启用手机注册，请使用邮箱注册。"; return; }
  if (!captcha.value || !form.captchaAnswer) { errorMessage.value = "请先完成算式验证码。"; return; }
  try {
    challenge.value = await accountSecurityApi.registrationChallenge(form.identityType, form.target,
      { captchaId: captcha.value.captchaId, answer: form.captchaAnswer });
  } catch (error) {
    setError(error, "验证码发送失败，请稍后重试。");
    if (retryAfter.value === null) { captcha.value = null; await loadCaptcha(); }
  }
}

async function submit() {
  clearError();
  if (!protocols.value || !currentTerms.value || !currentPrivacy.value) { errorMessage.value = "当前协议不可用，请稍后重试。"; return; }
  if (!challenge.value) { errorMessage.value = "请先发送并填写验证码。"; return; }
  if (form.password !== form.confirmPassword) { fieldErrors.value.confirmPassword = "两次输入的密码不一致"; return; }
  if (!agreed.terms || !agreed.privacy) { errorMessage.value = "请阅读并同意用户协议和隐私政策。"; return; }
  try {
    await accountSecurityApi.register({
      clientRequestId: randomRequestId(), challengeId: challenge.value.challengeId,
      identityType: form.identityType, target: form.target, verificationCode: form.code,
      nickname: form.nickname, password: form.password,
      agreements: [
        { documentType: currentTerms.value.documentType, version: currentTerms.value.version },
        { documentType: currentPrivacy.value.documentType, version: currentPrivacy.value.version }
      ]
    });
    if (await authStore.login(form.target, form.password)) return router.replace({ name: "courses" });
    return router.replace({ name: "login", query: { registered: "1" } });
  } catch (error) { setError(error, "注册未完成，请检查输入后重试。"); }
}

function chooseIdentityType(type: IdentityType) {
  if (type === "PHONE" && !phoneAvailable.value) return;
  form.identityType = type; form.target = ""; form.code = ""; challenge.value = null;
}

async function showProtocol(type: "TERMS" | "PRIVACY") {
  activeProtocol.value = protocols.value?.documents.find(item => item.documentType === type) ?? null;
  await nextTick();
  protocolDialog.value?.focus();
}

function clearError() { errorMessage.value = ""; fieldErrors.value = {}; retryAfter.value = null; }
function setError(error: unknown, fallback: string) {
  const failure = accountError(error, fallback);
  errorMessage.value = failure.message; fieldErrors.value = failure.fieldErrors; retryAfter.value = failure.retryAfter;
}
</script>

<template>
  <AccountFrame badge="创建账号" title="注册学习账号" description="默认使用邮箱注册。注册后获得学习权限；教师能力需通过申请和后续审核添加到同一账号。">
    <div class="account-tabs" role="tablist" aria-label="注册身份类型">
      <button type="button" :aria-selected="form.identityType === 'EMAIL'" @click="chooseIdentityType('EMAIL')">邮箱注册</button>
      <button type="button" :aria-selected="form.identityType === 'PHONE'" :disabled="!phoneAvailable"
        :title="phoneAvailable ? '使用中国大陆手机号注册' : '当前环境未启用手机注册'" @click="chooseIdentityType('PHONE')">手机注册<span v-if="!phoneAvailable">（未启用）</span></button>
    </div>
    <p v-if="!protocols" class="account-loading" role="status">正在加载当前协议…</p>
    <form v-else class="account-form" @submit.prevent="submit">
      <label><span>昵称</span><input v-model.trim="form.nickname" autocomplete="nickname" maxlength="30"
        placeholder="1～30 个可见字符，可使用中文" required><small v-if="fieldErrors.nickname" class="field-error">{{ fieldErrors.nickname }}</small></label>
      <label><span>{{ form.identityType === "EMAIL" ? "邮箱" : "中国大陆手机号" }}</span><input v-model.trim="form.target"
        :type="form.identityType === 'EMAIL' ? 'email' : 'tel'" :autocomplete="form.identityType === 'EMAIL' ? 'email' : 'tel'" placeholder="请输入注册身份" required>
        <small v-if="fieldErrors.target" class="field-error">{{ fieldErrors.target }}</small></label>
      <CaptchaField :captcha="captcha" :answer="form.captchaAnswer" :disabled="authStore.state.loading"
        @update:answer="form.captchaAnswer = $event" @refresh="refreshCaptcha" />
      <button class="button button--quiet" type="button" :disabled="authStore.state.loading || !form.target || !form.captchaAnswer"
        @click="sendCode">{{ challenge ? "重新发送验证码" : "发送验证码" }}</button>
      <label><span>一次性验证码</span><input v-model.trim="form.code" inputmode="numeric" autocomplete="one-time-code"
        maxlength="6" placeholder="请输入 6 位验证码" required><small v-if="fieldErrors.verificationCode" class="field-error">{{ fieldErrors.verificationCode }}</small></label>
      <label><span>设置密码</span><input v-model="form.password" type="password" autocomplete="new-password" minlength="12"
        placeholder="至少 12 位" required><small v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</small></label>
      <label><span>确认密码</span><input v-model="form.confirmPassword" type="password" autocomplete="new-password" minlength="12"
        placeholder="再次输入密码" required><small v-if="fieldErrors.confirmPassword" class="field-error">{{ fieldErrors.confirmPassword }}</small></label>
      <label class="agreement-check"><input v-model="agreed.terms" type="checkbox"><span>我已阅读并同意 <button type="button" @click="showProtocol('TERMS')">用户协议</button></span></label>
      <label class="agreement-check"><input v-model="agreed.privacy" type="checkbox"><span>我已阅读并同意 <button type="button" @click="showProtocol('PRIVACY')">隐私政策</button></span></label>
      <button class="button button--primary account-submit" type="submit" :disabled="authStore.state.loading || !challenge">{{ authStore.state.loading ? "正在创建…" : "创建学习账号" }}</button>
    </form>
    <div v-if="errorMessage" class="auth-error" role="alert"><strong>注册未完成</strong><span>{{ errorMessage }}</span>
      <small v-if="retryAfter !== null">请在 {{ retryAfter }} 秒后重试</small></div>
    <p v-if="challenge" class="account-hint">验证码已发送至 {{ challenge.deliveryHint }}，约 {{ challenge.expiresIn }} 秒有效。</p>
    <nav class="account-links" aria-label="账号操作"><RouterLink to="/login">已有账号，去登录</RouterLink></nav>
    <div v-if="activeProtocol" class="account-modal-backdrop" role="presentation" @click.self="activeProtocol = null" @keydown.esc="activeProtocol = null">
      <section ref="protocolDialog" class="account-modal" role="dialog" aria-modal="true" tabindex="-1" :aria-labelledby="`protocol-${activeProtocol.documentType}`">
        <header><h2 :id="`protocol-${activeProtocol.documentType}`">{{ activeProtocol.documentType === 'TERMS' ? '用户协议' : '隐私政策' }}</h2><button type="button" aria-label="关闭协议" @click="activeProtocol = null">×</button></header>
        <p class="protocol-meta">版本 {{ activeProtocol.version }} · 生效于 {{ new Date(activeProtocol.effectiveAt).toLocaleString() }}</p>
        <div class="protocol-body">{{ activeProtocol.body }}</div>
        <button class="button button--primary" type="button" @click="activeProtocol = null">我已阅读</button>
      </section>
    </div>
  </AccountFrame>
</template>
