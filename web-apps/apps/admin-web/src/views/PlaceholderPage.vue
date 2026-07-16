<script setup lang="ts">defineProps<{ heading: string; note: string }>();</script>
<template><section class="card"><span class="badge">路由壳</span><h2>{{ heading }}</h2><p>{{ note }}</p></section></template>
