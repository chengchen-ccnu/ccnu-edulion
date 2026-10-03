<script setup lang="ts">
/**
 * 三端共用的无角色判断页面骨架。
 * 输入是应用提供的固定导航和账号工具模型，输出是品牌、导航、工具区与页面内容结构；组件不判断身份权限。
 */
import ShellNavigation from "./ShellNavigation.vue";
import type { ShellAccountTool, ShellNavigationItem } from "./shell-model";
import { onBeforeUnmount, onMounted } from "vue";
import { useRoute } from "vue-router";

withDefaults(defineProps<{
  workspaceLabel: string;
  navigationItems: readonly ShellNavigationItem[];
  accountTools: readonly ShellAccountTool[];
  navigationPlacement?: "top" | "side";
}>(), { navigationPlacement: "top" });

const emit = defineEmits<{ toolActivate: [action: "logout"] }>();
const route = useRoute();

/** 菜单关闭后按需回到触发头像；Escape/点击外部/选择链接均收口，不留下隐藏焦点。 */
function closeMenu(menu: HTMLDetailsElement, restore = false) {
  menu.open = false;
  if (restore) menu.querySelector("summary")?.focus();
}
function outside(event: MouseEvent) {
  document.querySelectorAll<HTMLDetailsElement>(".app-shell__account-menu[open]").forEach(menu => {
    if (!menu.contains(event.target as Node)) closeMenu(menu);
  });
}
onMounted(() => document.addEventListener("click", outside));
onBeforeUnmount(() => document.removeEventListener("click", outside));

/** 账号名称仅用于生成无外部资源依赖的占位头像，不读取或持久化用户资料。 */
function accountInitial(label: string) {
  return Array.from(label.trim())[0] ?? "账";
}
</script>

<template>
  <div class="app-shell" :class="`app-shell--nav-${navigationPlacement}`">
    <header class="app-shell__header">
      <div class="app-shell__brand">
        <span class="app-shell__mark" aria-hidden="true">EL</span>
        <div>
          <h1>Edu Lion</h1>
          <span>{{ workspaceLabel }}</span>
        </div>
      </div>
      <nav v-if="navigationPlacement === 'top'" class="app-shell__main-navigation" aria-label="主导航">
        <ShellNavigation :items="navigationItems" />
      </nav>
      <nav class="app-shell__account-tools" aria-label="账号工具">
        <ol>
          <li v-for="tool in accountTools" :key="tool.id">
            <a v-if="tool.kind === 'link' && tool.href" :href="tool.href">
              <span>{{ tool.label }}</span><small>{{ tool.statusLabel }}</small>
            </a>
            <details v-else-if="tool.kind === 'menu' && tool.menuItems" :key="route.fullPath" class="app-shell__account-menu"
              @keydown.esc.prevent="closeMenu($event.currentTarget as HTMLDetailsElement, true)">
              <summary aria-label="打开账号菜单">
                <span class="app-shell__account-avatar" aria-hidden="true">{{ accountInitial(tool.statusLabel) }}</span>
                <small v-if="tool.menuItems.find(item => item.id === 'notifications')?.badge && tool.menuItems.find(item => item.id === 'notifications')?.badge !== '0'"
                  class="account-badge" aria-label="消息未读状态">{{ tool.menuItems.find(item => item.id === 'notifications')?.badge }}</small>
                <span class="app-shell__account-summary">
                  <span>{{ tool.label }}</span><small>{{ tool.statusLabel }}</small>
                </span>
                <span class="app-shell__account-chevron" aria-hidden="true"></span>
              </summary>
              <div class="app-shell__account-menu-panel">
                <header>
                  <span class="app-shell__account-avatar app-shell__account-avatar--large" aria-hidden="true">
                    {{ accountInitial(tool.statusLabel) }}
                  </span>
                  <span><strong>{{ tool.statusLabel }}</strong><small>账号与偏好</small></span>
                </header>
                <ul>
                  <li v-for="item in tool.menuItems" :key="item.id">
                    <button v-if="item.kind === 'action' && item.action" type="button"
                      class="app-shell__account-menu-action" @click="emit('toolActivate', item.action)">
                      {{ item.label }}
                    </button>
                    <a v-else-if="item.kind === 'link' && item.href" :href="item.href"
                      class="app-shell__account-menu-link">{{ item.label }} <small v-if="item.badge" class="account-badge">{{ item.badge }}</small></a>
                    <button v-else type="button" class="app-shell__account-menu-disabled" disabled
                      aria-disabled="true">
                      <span>{{ item.label }}</span><small>{{ item.statusLabel }}</small>
                    </button>
                  </li>
                </ul>
              </div>
            </details>
            <span v-else :aria-disabled="tool.kind === 'disabled' ? 'true' : undefined">
              <span>{{ tool.label }}</span><small>{{ tool.statusLabel }}</small>
            </span>
          </li>
        </ol>
      </nav>
    </header>
    <div class="app-shell__body">
      <nav v-if="navigationPlacement === 'side'" class="app-shell__side-navigation" aria-label="主导航">
        <ShellNavigation :items="navigationItems" />
      </nav>
      <main class="app-shell__content">
        <slot />
      </main>
    </div>
  </div>
</template>
