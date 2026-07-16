<script setup lang="ts">
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
