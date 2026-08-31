<script setup lang="ts">
/** 个人中心首期只展示当前用户摘要、教师申请入口和账号安全入口；完整资料由 T17A 负责。 */
import { computed, onMounted, ref } from "vue";
import { authStore } from "../auth";
import { accountSecurityApi } from "../account-security-api";
import { accountError } from "../account-errors";

const application = ref<Awaited<ReturnType<typeof accountSecurityApi.teacherApplication>>>(null);
const errorMessage = ref("");
const user = computed(() => authStore.state.currentUser);

onMounted(async () => {
  try { application.value = await accountSecurityApi.teacherApplication(); }
  catch (error) { errorMessage.value = accountError(error, "教师申请状态暂时无法加载。").message; }
});
</script>

<template>
  <section class="content-page" aria-labelledby="profile-title"><span class="badge">账号中心</span><h1 id="profile-title">个人中心</h1><p class="page-lead">这里管理账号安全和教师能力申请。头像、完整资料和运营内容将在后续任务提供。</p>
    <div class="profile-grid"><article class="profile-card"><span class="profile-avatar" aria-hidden="true">{{ Array.from(user?.displayName ?? "账")[0] }}</span><div><h2>{{ user?.displayName ?? "当前用户" }}</h2><p>角色：{{ user?.roles.join("、") || "已登录" }}</p><p>能力：{{ user?.permissions.join("、") || "暂无" }}</p></div></article>
      <article class="profile-card profile-card--action"><h2>教师申请</h2><p v-if="application">当前状态：<strong>{{ application.status }}</strong></p><p v-else>尚未提交教师申请。</p><RouterLink class="button button--primary" to="/apply-teacher">{{ application ? "查看申请状态" : "申请教师能力" }}</RouterLink></article>
      <article class="profile-card profile-card--action"><h2>账号安全</h2><p>管理密码、已验证身份和设备会话。</p><RouterLink class="button button--quiet" to="/profile/security">打开安全设置</RouterLink></article></div>
    <p v-if="errorMessage" class="panel-error" role="alert">{{ errorMessage }}</p>
  </section>
</template>
