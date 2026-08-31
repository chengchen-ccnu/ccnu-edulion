<script setup lang="ts">
/**
 * 账号安全页消费 IAM 的身份 revision、设备会话和密码/身份变更命令。
 * 变更成功后的重新认证、副作用和 409 保留输入均由页面明确处理；此页不展示账号注销。
 */
import type { AccountSession, ArithmeticCaptcha, AuthenticationChannelCapabilities, IdentityMutationResult, IdentityType, VerifiedIdentityCollection, VerificationChallenge } from "@edu/api-client";
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { accountSecurityApi } from "../account-security-api";
import { accountError, randomRequestId } from "../account-errors";
import { authStore } from "../auth";

const router = useRouter();
const identities = ref<VerifiedIdentityCollection | null>(null);
const sessions = ref<AccountSession[]>([]);
const capabilities = ref<AuthenticationChannelCapabilities | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const sessionError = ref("");
const identityError = ref("");
const passwordForm = reactive({ currentPassword: "", newPassword: "", confirmPassword: "" });
const passwordMessage = ref("");
const identityForm = reactive({ operation: "BIND" as "BIND" | "REPLACE", identityType: "PHONE" as IdentityType,
  target: "", currentPassword: "", code: "" });
const identityChallenge = ref<VerificationChallenge | null>(null);
const identityPanelOpen = ref(false);
const identityMessage = ref("");
const phoneDeliveryAvailable = computed(() => capabilities.value?.channels.find(item => item.identityType === "PHONE")?.verificationDeliveryAvailable ?? false);
const missingType = computed<IdentityType | null>(() => {
  const values = identities.value?.identities.map(item => item.identityType) ?? [];
  return values.includes("EMAIL") ? values.includes("PHONE") ? null : "PHONE" : "EMAIL";
});

onMounted(() => { void loadAll(); });

async function loadAll() {
  loading.value = true; errorMessage.value = "";
  const results = await Promise.allSettled([accountSecurityApi.identities(), accountSecurityApi.sessions(), accountSecurityApi.capabilities()]);
  const identityResult = results[0]; const sessionResult = results[1]; const capabilityResult = results[2];
  if (identityResult.status === "fulfilled") identities.value = identityResult.value; else identityError.value = accountError(identityResult.reason, "身份信息加载失败。").message;
  if (sessionResult.status === "fulfilled") sessions.value = sessionResult.value; else sessionError.value = accountError(sessionResult.reason, "设备会话加载失败。").message;
  if (capabilityResult.status === "fulfilled") capabilities.value = capabilityResult.value;
  if (identityResult.status === "rejected" && sessionResult.status === "rejected") errorMessage.value = "账号安全信息暂时无法加载，请稍后重试。";
  loading.value = false;
}

async function changePassword() {
  passwordMessage.value = ""; errorMessage.value = "";
  if (passwordForm.newPassword !== passwordForm.confirmPassword) { passwordMessage.value = "两次输入的新密码不一致。"; return; }
  try { await accountSecurityApi.changePassword({ currentPassword: passwordForm.currentPassword, newPassword: passwordForm.newPassword });
    await authStore.logout(); await router.replace({ name: "login", query: { passwordChanged: "1" } }); }
  catch (error) { passwordMessage.value = accountError(error, "修改密码未完成，请重试。").message; }
}

async function logoutAll() {
  if (!window.confirm("全部设备将退出登录，是否继续？")) return;
  try { await accountSecurityApi.logoutAll(); await authStore.logout(); await router.replace({ name: "login" }); }
  catch (error) { sessionError.value = accountError(error, "全部设备退出失败，请稍后重试。").message; }
}

function openIdentity(type: IdentityType, operation: "BIND" | "REPLACE") {
  if (type === "PHONE" && !phoneDeliveryAvailable.value) { identityError.value = "当前环境未启用手机验证码。"; return; }
  identityForm.operation = operation; identityForm.identityType = type; identityForm.target = ""; identityForm.currentPassword = ""; identityForm.code = ""; identityChallenge.value = null; identityMessage.value = ""; identityPanelOpen.value = true;
}

async function sendIdentityCode() {
  identityMessage.value = "";
  if (!identities.value) return;
  try { identityChallenge.value = await accountSecurityApi.identityChallenge({ operation: identityForm.operation,
      identityType: identityForm.identityType, target: identityForm.target, currentPassword: identityForm.currentPassword,
      expectedRevision: identities.value.revision }); }
  catch (error) { identityMessage.value = accountError(error, "身份验证码发送失败，请重试。").message; }
}

async function submitIdentity() {
  if (!identities.value || !identityChallenge.value) { identityMessage.value = "请先发送验证码。"; return; }
  try {
    const body = { clientRequestId: randomRequestId(), expectedRevision: identities.value.revision,
      challengeId: identityChallenge.value.challengeId, identityType: identityForm.identityType, target: identityForm.target,
      verificationCode: identityForm.code, currentPassword: identityForm.currentPassword };
    const result = identityForm.operation === "BIND" ? await accountSecurityApi.bindIdentity(body) : await accountSecurityApi.replaceIdentity(identityForm.identityType, body);
    await finishIdentityMutation(result);
  } catch (error) { identityMessage.value = accountError(error, "身份变更未完成，请刷新后重试。").message; }
}

async function unbind(type: IdentityType) {
  if (!identities.value || !window.confirm("解绑后需要重新登录，是否继续？")) return;
  const currentPassword = window.prompt("请输入当前密码") ?? "";
  if (!currentPassword) return;
  try { const result = await accountSecurityApi.unbindIdentity(type, { clientRequestId: randomRequestId(), expectedRevision: identities.value.revision, currentPassword }); await finishIdentityMutation(result); }
  catch (error) { identityError.value = accountError(error, "解绑未完成，请刷新后重试。").message; }
}

async function finishIdentityMutation(result: IdentityMutationResult) {
  if (result.session) { await authStore.acceptLoginResponse(result.session); identities.value = { identities: result.identities, revision: result.revision }; identityPanelOpen.value = false; identityMessage.value = "身份已绑定，其他设备已退出。"; return loadSessions(); }
  await authStore.logout(); await router.replace({ name: "login", query: { reauthenticated: "1" } });
}

async function loadSessions() { try { sessions.value = await accountSecurityApi.sessions(); } catch (error) { sessionError.value = accountError(error, "设备会话刷新失败。").message; } }
</script>

<template>
  <section class="content-page security-page" aria-labelledby="security-title"><span class="badge">账号安全</span><h1 id="security-title">密码与设备安全</h1><p class="page-lead">敏感变更需要当前密码。身份只显示脱敏值，完整身份不会从服务端返回。</p>
    <p v-if="loading" class="account-loading" role="status">正在加载安全信息…</p>
    <p v-if="errorMessage" class="panel-error" role="alert">{{ errorMessage }} <button type="button" @click="loadAll">重试</button></p>
    <div v-if="identities" class="security-section"><header><div><h2>已验证身份</h2><p>当前 revision：{{ identities.revision }}</p></div><span>最多一个邮箱和一个手机号</span></header><div class="identity-grid"><article v-for="item in identities.identities" :key="item.identityType" class="identity-card"><strong>{{ item.identityType === 'EMAIL' ? '邮箱' : '手机号' }}</strong><span>{{ item.maskedValue }}</span><small>验证于 {{ new Date(item.verifiedAt).toLocaleString() }}</small><div><button class="button button--quiet" type="button" :disabled="item.identityType === 'PHONE' && !phoneDeliveryAvailable" @click="openIdentity(item.identityType, 'REPLACE')">换绑</button><button class="button button--danger" type="button" @click="unbind(item.identityType)">解绑</button></div></article></div><button v-if="missingType && !(missingType === 'PHONE' && !phoneDeliveryAvailable)" class="button button--primary" type="button" @click="openIdentity(missingType, 'BIND')">绑定{{ missingType === 'EMAIL' ? '邮箱' : '手机号' }}</button><p v-if="missingType === 'PHONE' && !phoneDeliveryAvailable" class="account-hint">当前环境未启用手机验证码，暂不能绑定手机号。</p></div>
    <section v-if="identityPanelOpen" class="security-section"><header><div><h2>{{ identityForm.operation === 'BIND' ? '绑定' : '换绑' }}{{ identityForm.identityType === 'EMAIL' ? '邮箱' : '手机号' }}</h2><p>先验证新身份，再执行原子变更。</p></div><button type="button" class="button button--quiet" @click="identityPanelOpen = false">取消</button></header><form class="account-form" @submit.prevent="submitIdentity"><label><span>新{{ identityForm.identityType === 'EMAIL' ? '邮箱' : '手机号' }}</span><input v-model.trim="identityForm.target" :type="identityForm.identityType === 'EMAIL' ? 'email' : 'tel'" required></label><label><span>当前密码</span><input v-model="identityForm.currentPassword" type="password" autocomplete="current-password" minlength="8" required></label><button class="button button--quiet" type="button" :disabled="!identityForm.target || !identityForm.currentPassword" @click="sendIdentityCode">{{ identityChallenge ? '重新发送验证码' : '发送验证码' }}</button><label><span>一次性验证码</span><input v-model.trim="identityForm.code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required></label><button class="button button--primary" type="submit" :disabled="!identityChallenge">确认{{ identityForm.operation === 'BIND' ? '绑定' : '换绑' }}</button></form><p v-if="identityChallenge" class="account-hint">验证码已发送至 {{ identityChallenge.deliveryHint }}。</p><p v-if="identityMessage" class="panel-error" role="alert">{{ identityMessage }}</p></section>
    <section class="security-section"><header><div><h2>修改密码</h2><p>成功后所有设备都必须重新登录。</p></div></header><form class="account-form security-form" @submit.prevent="changePassword"><label><span>当前密码</span><input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" minlength="8" required></label><label><span>新密码</span><input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" minlength="12" required></label><label><span>确认新密码</span><input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" minlength="12" required></label><button class="button button--primary" type="submit">修改密码</button></form><p v-if="passwordMessage" class="panel-error" role="alert">{{ passwordMessage }}</p></section>
    <section class="security-section"><header><div><h2>设备会话</h2><p>只显示设备名称和时间，不展示刷新令牌。</p></div><button class="button button--danger" type="button" @click="logoutAll">退出全部设备</button></header><p v-if="sessionError" class="panel-error" role="alert">{{ sessionError }} <button type="button" @click="loadSessions">重试</button></p><ul class="session-list"><li v-for="session in sessions" :key="session.id"><div><strong>{{ session.deviceName }}</strong><span>{{ session.current ? '当前设备' : '其他设备' }}</span></div><small>最近使用：{{ new Date(session.lastUsedAt).toLocaleString() }} · 到期：{{ new Date(session.expiresAt).toLocaleString() }}</small></li><li v-if="!sessions.length && !sessionError" class="empty-inline">当前没有可展示的设备会话。</li></ul></section>
    <p class="security-boundary">账号注销、人工申诉和正式重新同意暂不在本页提供。</p>
  </section>
</template>
