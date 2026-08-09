<script setup lang="ts">
/**
 * 共享导航列表只按传入顺序渲染链接或禁用槽位；不读取身份，也不为禁用项生成 href 和键盘焦点。
 */
import { RouterLink } from "vue-router";

import type { ShellNavigationItem } from "./shell-model";

defineProps<{ items: readonly ShellNavigationItem[] }>();
</script>

<template>
  <ol class="shell-navigation__list">
    <li v-for="item in items" :key="item.id">
      <RouterLink
        v-if="item.state === 'enabled' && item.kind === 'router' && item.href"
        :to="item.href"
        :aria-current="item.current ? 'page' : undefined"
      >
        {{ item.label }}
      </RouterLink>
      <a
        v-else-if="item.state === 'enabled' && item.kind === 'external' && item.href"
        :href="item.href"
        :aria-current="item.current ? 'page' : undefined"
      >
        {{ item.label }}
      </a>
      <span
        v-else
        class="shell-navigation__disabled"
        aria-disabled="true"
        :aria-current="item.current ? 'page' : undefined"
      >
        <span>{{ item.label }}</span>
        <small>{{ item.statusLabel ?? (item.state === 'coming-soon' ? '建设中' : '需要权限') }}</small>
      </span>
    </li>
  </ol>
</template>
