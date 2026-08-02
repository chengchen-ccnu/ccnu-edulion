<script setup lang="ts">
/**
 * 三端共用的无角色判断页面骨架。title/audience 仅控制标题文案，导航与主体由具名/默认 slot 提供；
 * 权限守卫仍由各应用负责，避免共享组件承载角色业务逻辑。
 */
defineProps<{
  title: string;
  audience: string;
}>();
</script>

<template>
  <div class="app-shell">
    <header class="app-shell__header">
      <div>
        <span class="app-shell__eyebrow">EDU PLATFORM · {{ audience }}</span>
        <h1>{{ title }}</h1>
      </div>
      <nav aria-label="主导航">
        <slot name="navigation" />
      </nav>
    </header>
    <main class="app-shell__content">
      <slot />
    </main>
  </div>
</template>
