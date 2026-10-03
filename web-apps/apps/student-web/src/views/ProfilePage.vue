<script setup lang="ts">
/** 个人中心只展示当前用户、最新教师申请和安全入口；不提供头像/资料编辑。 */
import { computed } from "vue";
import { authStore } from "../auth";
import { applicationLabels, useTeacherApplicationStatus } from "../teacher-application-status";

const { application, loading, ready, error: errorMessage, refresh } = useTeacherApplicationStatus();
const user = computed(() => authStore.state.currentUser);
</script>

<template>
  <section class="content-page" aria-labelledby="profile-title"><span class="badge">账号中心</span><h1 id="profile-title">个人中心</h1><p class="page-lead">这里管理账号安全和教师能力申请。头像、完整资料和运营内容将在后续任务提供。</p>
    <div class="profile-grid"><article class="profile-card"><span class="profile-avatar" aria-hidden="true">{{ Array.from(user?.displayName ?? "账")[0] }}</span><div><h2>{{ user?.displayName ?? "当前用户" }}</h2><p>角色：{{ user?.roles.join("、") || "已登录" }}</p><p>能力：{{ user?.permissions.join("、") || "暂无" }}</p></div></article>
      <article v-if="!authStore.hasPermission('administration:access')" class="profile-card profile-card--action"><h2>教师申请</h2><p v-if="loading">正在读取申请状态…</p><p v-else-if="application">当前状态：<strong>{{ applicationLabels[application.status] }}</strong></p><p v-else-if="ready">尚未提交教师申请。</p><p v-else>申请状态暂不可用。</p><RouterLink class="button button--primary" to="/apply-teacher">查看教师申请</RouterLink></article>
      <article class="profile-card profile-card--action"><h2>账号安全</h2><p>管理密码、已验证身份和设备会话。</p><RouterLink class="button button--quiet" to="/profile/security">打开安全设置</RouterLink></article></div>
    <p v-if="errorMessage" class="panel-error" role="alert">{{ errorMessage }} <button @click="refresh">重新加载</button></p>
  </section>
</template>
