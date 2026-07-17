<script setup lang="ts">
import type { AuthStore } from "./index";
import { availableWorkspaces } from "./index";
import { computed } from "vue";

const props = defineProps<{ store: AuthStore }>();
const workspaces = computed(() => availableWorkspaces(props.store.state.currentUser));
</script>

<template>
  <section class="card" aria-labelledby="forbidden-heading">
    <span class="badge">403</span>
    <h2 id="forbidden-heading">无权访问此应用</h2>
    <p>当前账号不具备此应用要求的角色和权限。你可以前往已有权限的工作台。</p>
    <nav v-if="workspaces.length" class="workspace-grid" aria-label="有权访问的工作台">
      <a v-for="workspace in workspaces" :key="workspace.id" class="workspace-link" :href="workspace.href">
        {{ workspace.label }}
      </a>
    </nav>
    <p v-else>当前账号没有可用工作台，请联系管理员。</p>
  </section>
</template>
