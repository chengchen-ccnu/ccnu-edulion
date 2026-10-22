<script setup lang="ts">
/** 账号权限区内切换账号操作与邀请面板；复用既有路由，不新增侧栏或持久化草稿。 */
import { computed } from "vue";
import { useRoute } from "vue-router";
import AccountPermissionsPage from "./AccountPermissionsPage.vue";
import AdminInvitationPanel from "./AdminInvitationPanel.vue";
const route = useRoute();
const invite = computed(() => route.query.panel === "invite");
</script>
<template>
  <nav class="container account-actions account-region-nav" aria-label="账号权限区域">
    <RouterLink to="/admin/users" :aria-current="!invite ? 'page' : undefined">账号权限操作</RouterLink>
    <RouterLink to="/admin/users?panel=invite" :aria-current="invite ? 'page' : undefined">管理员邀请</RouterLink>
  </nav>
  <AdminInvitationPanel v-if="invite" />
  <AccountPermissionsPage v-else />
</template>
