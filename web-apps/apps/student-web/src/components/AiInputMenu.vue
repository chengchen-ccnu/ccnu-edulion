<script setup lang="ts">
/**
 * 两个 AI 输入区共用的有限“＋”菜单。它只公开题图和一项学生能力枚举；方向键、Enter/Space、
 * Escape 与 Tab 均保持原生可访问行为，关闭后把焦点归还触发按钮。
 */
import type { AgentSelectableCapability } from "@edu/api-client";
import { nextTick, ref } from "vue";

const props = defineProps<{ disabled?: boolean; uploading?: boolean; selectedCapability?: AgentSelectableCapability | null }>();
const emit = defineEmits<{ file: [File]; capability: [AgentSelectableCapability | null] }>();
const open = ref(false); const trigger = ref<HTMLButtonElement | null>(null); const fileInput = ref<HTMLInputElement | null>(null);
const menu = ref<HTMLElement | null>(null);

async function toggle() { open.value ? await close() : await show(); }
async function show() { if (props.disabled) return; open.value = true; await nextTick(); menu.value?.querySelector<HTMLButtonElement>("button")?.focus(); }
async function close(restore = true) { open.value = false; if (restore) { await nextTick(); trigger.value?.focus(); } }
function fileChanged(event: Event) { const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ""; if (file) emit("file", file); void close(); }
function chooseCapability() { emit("capability", "GENERATE_AUTONOMOUS_PRACTICE"); void close(); }
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") { event.preventDefault(); void close(); return; }
  if (event.key === "Tab") { open.value = false; return; }
  if (!['ArrowDown','ArrowUp','Home','End'].includes(event.key) || !menu.value) return;
  event.preventDefault(); const items = [...menu.value.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
  const current = Math.max(0, items.indexOf(document.activeElement as HTMLButtonElement));
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
  items[index]?.focus();
}
</script>

<template>
  <div class="ai-input-menu">
    <button ref="trigger" class="button button--quiet ai-input-menu__trigger" type="button" aria-label="添加内容"
      aria-haspopup="menu" :aria-expanded="open" :disabled="disabled" @click="toggle">＋</button>
    <div v-if="open" ref="menu" class="ai-input-menu__popup" role="menu" @keydown="keydown">
      <button type="button" role="menuitem" :disabled="uploading" @click="fileInput?.click()">{{ uploading ? "正在上传…" : "添加题图" }}</button>
      <button type="button" role="menuitem" @click="chooseCapability">生成自主练习</button>
    </div>
    <input ref="fileInput" class="sr-only" type="file" accept="image/jpeg,image/png,image/webp" @change="fileChanged">
    <span v-if="selectedCapability" class="ai-input-menu__selection">下一条：生成自主练习
      <button type="button" aria-label="取消下一条消息的生成自主练习选择" @click="emit('capability', null)">取消</button></span>
  </div>
</template>
