<script setup lang="ts">
/** student-web 唯一身份区域的视觉壳；页面内容通过插槽提供，组件不判断角色或业务权限。 */
defineProps<{ badge: string; title: string; description: string }>();
</script>

<template>
  <section class="account-page" :aria-labelledby="`account-title-${badge}`">
    <aside class="account-page__intro" aria-label="Edu Lion 平台介绍">
      <div class="account-page__orb account-page__orb--one" aria-hidden="true" />
      <div class="account-page__orb account-page__orb--two" aria-hidden="true" />
      <div class="account-page__brand"><span aria-hidden="true">E</span><strong>EDU PLATFORM</strong></div>
      <div class="account-page__intro-copy">
        <span class="account-page__kicker">EDU LION ONLINE EDUCATION</span>
        <h2>Edu Lion 在线教育平台</h2>
        <p>学生、教师和管理员使用同一账号，安全进入各自有权访问的空间。</p>
      </div>
    </aside>
    <div class="account-page__panel">
      <span class="badge">{{ badge }}</span>
      <h1 :id="`account-title-${badge}`">{{ title }}</h1>
      <p class="account-page__description">{{ description }}</p>
      <slot />
    </div>
  </section>
</template>
