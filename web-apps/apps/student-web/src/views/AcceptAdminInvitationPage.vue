<script setup lang="ts">
/**
 * 唯一身份壳中的受邀开户页。邀请ID/邮箱/凭证手动填写，仅发送POST正文，不读取URL或持久存储。
 * 201只代表账号已建立，清秘密后交统一登录；无幂等键的一次性请求遇到未知结果锁止再次消费。
 * 已登录不覆盖身份；隐藏/离线/身份变化/卸载取消等待，取消不能证明服务端未创建账号。
 */
import { createRequestScope, GatewayRequestError } from "@edu/api-client";
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { acceptAdminInvitation } from "../admin-invitation-api";
import { authStore } from "../auth";
import AccountFrame from "../components/AccountFrame.vue";

const form = reactive({ invitationId: "", email: "", secret: "", displayName: "", password: "", confirmPassword: "" });
const fields = ref<Record<string, string>>({}); const message = ref(""); const busy = ref(false);
const outcome = ref<"editing" | "unknown" | "success">("editing"); const offline = ref(!navigator.onLine);
const hidden = ref(document.hidden); const coolingUntil = ref(0); const now = ref(Date.now());
const loggedIn = computed(() => authStore.state.status === "authenticated");
const scope = createRequestScope(); let disposed = false; let identityEpoch = 0;
const cooling = computed(() => now.value < coolingUntil.value);
const blocked = computed(() => loggedIn.value || busy.value || outcome.value !== "editing" || offline.value || hidden.value || cooling.value);
const clockTimer = setInterval(() => { now.value = Date.now(); }, 1000);

function clearSecrets() { form.secret = ""; form.password = ""; form.confirmPassword = ""; }
/** 使用契约兼容子集：昵称1～30可见字符，凭证恰好43字符，密码至少12字符且UTF-8不超过72字节。 */
function validate() {
  fields.value = {};
  if (!/^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(form.invitationId.trim())) fields.value.invitationId = "请输入完整邀请UUID。";
  const email = form.email.trim().toLowerCase();
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) fields.value.email = "请输入有效邮箱。";
  if (!/^[A-Za-z0-9_-]{43}$/.test(form.secret)) fields.value.secret = "请输入邮件中43字符的一次性凭证。";
  const nickname = form.displayName.trim();
  if (!nickname || [...nickname].length > 30 || /[\p{Cc}\p{Cf}]/u.test(nickname)) fields.value.displayName = "昵称须为1～30个可见字符，不能含控制字符。";
  if ([...form.password].length < 12 || new TextEncoder().encode(form.password).length > 72) fields.value.password = "密码至少12字符，UTF-8编码不超过72字节。";
  if (form.password !== form.confirmPassword) fields.value.confirmPassword = "两次密码不一致。";
  return Object.keys(fields.value).length === 0;
}
/** 只发送一次开户请求；网络/5xx/超时/取消均保持未确认，不以再次422反推第一次失败。 */
async function submit() {
  if (blocked.value || !validate()) return;
  busy.value = true; message.value = ""; const epoch = identityEpoch;
  try {
    await scope.run(45000, signal => acceptAdminInvitation({ invitationId: form.invitationId.trim(), email: form.email.trim().toLowerCase(),
      secret: form.secret, displayName: form.displayName.trim(), password: form.password }, signal));
    if (disposed || epoch !== identityEpoch) return;
    outcome.value = "success"; clearSecrets(); form.email = ""; form.invitationId = ""; form.displayName = "";
    fields.value = {}; message.value = "已开通，请登录。管理员身份以正常登录后的服务端能力为准。";
  } catch (failure) {
    if (disposed || epoch !== identityEpoch) return;
    if (!(failure instanceof GatewayRequestError) || failure.status >= 500) {
      outcome.value = "unknown"; clearSecrets();
      message.value = "开户结果未确认，可能已成功。请尝试正常登录，或联系邀请人查询接受状态；本页不会再次提交开户。";
    } else if (failure.status === 429) {
      coolingUntil.value = Date.now() + Math.max(1, failure.retryAfterSeconds ?? 30) * 1000;
      message.value = "请求过于频繁，请等待冷却结束再操作。";
    } else if (failure.status === 422) {
      // 邮箱、凭证、过期与已消费保持统一表达，不透出服务端任意字段原因。
      if (failure.fieldErrors.displayName) fields.value.displayName = "昵称不符合要求，请使用1～30个可见字符。";
      if (failure.fieldErrors.password) fields.value.password = "密码不符合要求，请核对字符数及UTF-8字节上限。";
      message.value = "邀请无法接受，请核对邮件信息或联系邀请人。凭证无效、过期、未发送或已使用均可能导致此结果。";
    } else if (failure.status === 409) {
      message.value = "邮箱已绑定账号，不能转换为管理员。请联系邀请人核对。";
    } else {
      message.value = "当前无法接受邀请，请联系邀请人核对或稍后操作。";
    }
  } finally { if (!disposed) busy.value = false; }
}
/** 身份改变后清除秘密及错误，不重放旧请求，不静默登出当前账号。 */
watch(() => `${authStore.state.status}:${authStore.state.currentUser?.id}`, () => {
  identityEpoch++; scope.cancel(); clearSecrets(); fields.value = {}; message.value = "";
  if (busy.value) outcome.value = "unknown";
  busy.value = false;
});
function interrupt() { scope.cancel(); }
function visibilityChanged() { hidden.value = document.hidden; if (hidden.value) interrupt(); }
function connectionChanged() { offline.value = !navigator.onLine; if (offline.value) interrupt(); }
function beforeUnload(event: BeforeUnloadEvent) { if (busy.value) { event.preventDefault(); event.returnValue = ""; } }
onBeforeRouteLeave(() => !busy.value || window.confirm("开户请求仍在处理中，离开后结果可能未知。确认离开？请勿重复开户，可正常登录或联系邀请人核对。"));
document.addEventListener("visibilitychange", visibilityChanged); window.addEventListener("online", connectionChanged);
window.addEventListener("offline", connectionChanged); window.addEventListener("beforeunload", beforeUnload);
onBeforeUnmount(() => {
  disposed = true; scope.cancel(); clearSecrets(); form.email = ""; form.invitationId = ""; form.displayName = ""; clearInterval(clockTimer);
  document.removeEventListener("visibilitychange", visibilityChanged); window.removeEventListener("online", connectionChanged);
  window.removeEventListener("offline", connectionChanged); window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <AccountFrame badge="受邀开户" title="接受管理员邀请" description="仅持有有效邮件邀请的收件人可开通独立管理员账号。请手动填写邮件信息，勿将凭证放入地址栏。">
    <p v-if="loggedIn" role="status">当前已有账号登录。请通过账号菜单退出后再开通，或使用独立浏览器上下文；本页不会替换现有身份。</p>
    <p v-else class="account-hint">若其他标签页已有账号，请先退出或使用独立浏览器上下文。本页不自动恢复或切换会话。</p>
    <p v-if="offline" role="status">当前离线，已停止请求。</p>
    <p v-if="cooling" role="status">还需等待 {{ Math.max(1, Math.ceil((coolingUntil - now) / 1000)) }} 秒。</p>
    <form v-if="!loggedIn && outcome === 'editing'" class="account-form" autocomplete="off" @submit.prevent="submit">
      <label><span>邀请ID</span><input v-model="form.invitationId" name="invitationId" autocomplete="off" :disabled="blocked" required><small v-if="fields.invitationId" class="field-error">{{ fields.invitationId }}</small></label>
      <label><span>收件邮箱</span><input v-model="form.email" name="email" type="email" maxlength="254" autocomplete="off" :disabled="blocked" required><small v-if="fields.email" class="field-error">{{ fields.email }}</small></label>
      <label><span>邮件一次性凭证</span><input v-model="form.secret" name="secret" type="password" autocomplete="off" minlength="43" maxlength="43" :disabled="blocked" required><small v-if="fields.secret" class="field-error">{{ fields.secret }}</small></label>
      <label><span>昵称</span><input v-model="form.displayName" name="displayName" autocomplete="off" :disabled="blocked" required><small v-if="fields.displayName" class="field-error">{{ fields.displayName }}</small></label>
      <label><span>新密码</span><input v-model="form.password" name="password" type="password" autocomplete="new-password" :disabled="blocked" required><small>至少12字符，UTF-8最多72字节</small><small v-if="fields.password" class="field-error">{{ fields.password }}</small></label>
      <label><span>确认密码</span><input v-model="form.confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" :disabled="blocked" required><small v-if="fields.confirmPassword" class="field-error">{{ fields.confirmPassword }}</small></label>
      <button class="button button--primary" :disabled="blocked">{{ busy ? '正在开通，请等待…' : '开通管理员账号' }}</button>
    </form>
    <div v-if="message" :role="outcome === 'success' ? 'status' : 'alert'" :class="outcome === 'success' ? 'account-hint' : 'auth-error'">{{ message }}</div>
    <nav class="account-links" aria-label="受邀开户后续操作"><RouterLink to="/login">{{ outcome === 'unknown' ? '尝试正常登录' : '前往统一登录' }}</RouterLink></nav>
    <p v-if="outcome === 'unknown'">不要重复提交或据再次校验失败判断首次开户失败。请邀请人按邮件中的邀请ID查询状态。</p>
  </AccountFrame>
</template>
