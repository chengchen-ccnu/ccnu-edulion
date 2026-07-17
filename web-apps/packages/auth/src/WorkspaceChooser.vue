<script setup lang="ts">
import type { AuthStore } from "./index";
import { availableWorkspaces } from "./index";
import { computed } from "vue";

const props = defineProps<{ store: AuthStore }>();
const workspaces = computed(() => availableWorkspaces(props.store.state.currentUser));
</script>

<template>
  <section class="card" aria-labelledby="workspace-heading">
    <span class="badge">身份已验证</span>
    <h2 id="workspace-heading">选择工作台</h2>
    <p v-if="workspaces.length > 1">当前账号具有多个平台角色，请选择本次要进入的工作台。</p>
    <p v-else>请选择要进入的工作台。</p>
    <nav class="workspace-grid" aria-label="可用工作台">
      <a v-for="workspace in workspaces" :key="workspace.id" class="workspace-link" :href="workspace.href">
        {{ workspace.label }}
      </a>
    </nav>
  </section>
</template>
