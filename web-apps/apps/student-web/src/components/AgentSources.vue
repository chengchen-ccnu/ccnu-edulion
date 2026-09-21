<script setup lang="ts">
/** 授权来源的紧凑投影：前两条常显，其余在有界浮层中查看；不重新请求或推断来源权限。 */
import type { AgentAuthorizedReference } from "@edu/api-client";
import { computed, onBeforeUnmount, onMounted, ref, useId } from "vue";
import { RouterLink } from "vue-router";

const props = defineProps<{ references: AgentAuthorizedReference[] }>();
const root = ref<HTMLElement | null>(null); const trigger = ref<HTMLButtonElement | null>(null);
const open = ref(false); const below = ref(false); const panelId = useId();
const sources = computed(() => props.references.filter((item, index, all) =>
  all.findIndex(value => value.sourceType === item.sourceType && value.referenceId === item.referenceId) === index));
function label(type: AgentAuthorizedReference["sourceType"]) {
  return ({ QUESTION: "题目", KNOWLEDGE_POINT: "知识点", COURSE_TEXT: "课程正文",
    STUDENT_FACT: "本人学习事实", USER_HELP: "平台帮助" } as const)[type];
}
/** 触屏外部点击、焦点离开和 Escape 都关闭浮层；监听器随组件卸载移除。 */
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) open.value = false; }
function leaveFocus(event: FocusEvent) { if (!root.value?.contains(event.relatedTarget as Node)) open.value = false; }
function focus(event: FocusEvent) { if ((event.target as HTMLElement).matches(":focus-visible")) show(); }
/** 靠近聊天顶部时向下展开，避免浮层被消息滚动区裁掉；其余位置向上避开输入框。 */
function show() {
  const bounds = root.value?.getBoundingClientRect();
  const viewport = root.value?.closest('.ai-message-viewport,.practice-chat__messages')?.getBoundingClientRect();
  below.value = Boolean(bounds && viewport && bounds.top - viewport.top < Math.min(200, window.innerHeight * .24) + 64
    && viewport.bottom - bounds.bottom > bounds.top - viewport.top);
  open.value = true;
}
function escape() { trigger.value?.focus(); open.value = false; }
onMounted(() => document.addEventListener("pointerdown", outside));
onBeforeUnmount(() => document.removeEventListener("pointerdown", outside));
</script>

<template>
  <section v-if="sources.length" ref="root" class="agent-sources" aria-label="授权来源"
    @mouseleave="open = false" @focusout="leaveFocus" @keydown.esc.stop.prevent="escape">
    <span class="agent-sources__label">参考来源</span>
    <ul class="agent-sources__preview"><li v-for="(item, index) in sources.slice(0, 2)" :key="item.referenceId" :title="item.title">
      <span class="agent-sources__number" aria-hidden="true">{{ index + 1 }}</span>
      <RouterLink v-if="item.appRoute" :to="item.appRoute">{{ item.title }}</RouterLink><span v-else class="agent-sources__title">{{ item.title }}</span>
    </li></ul>
    <div v-if="sources.length > 2" class="agent-sources__more" @mouseenter="show">
      <button ref="trigger" type="button" :aria-expanded="open" :aria-controls="panelId"
        @focus="focus" @click="show"><span>还有 {{ sources.length - 2 }} 条</span><span aria-hidden="true">↗</span></button>
      <div v-if="open" :id="panelId" class="agent-sources__popover" :class="{ 'is-below': below }" role="region" aria-label="完整授权来源" tabindex="0">
        <header><strong>参考来源</strong><span>{{ sources.length }} 条</span><button type="button" aria-label="关闭来源列表" @click="escape">×</button></header>
        <ul><li v-for="(item, index) in sources" :key="item.referenceId"><span class="agent-sources__number" aria-hidden="true">{{ index + 1 }}</span><div><small>{{ label(item.sourceType) }}</small>
          <RouterLink v-if="item.appRoute" :to="item.appRoute">{{ item.title }}</RouterLink><span v-else>{{ item.title }}</span></div>
        </li></ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-sources { position: relative; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; width: min(100%,900px); margin: 12px auto; font-size: .75rem; color: #465571; }
.agent-sources__label { flex-basis: 100%; color: #69758b; font-size: .7rem; }
ul { list-style: none; padding: 0; margin: 0; }
.agent-sources__preview { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; max-width: 100%; }
.agent-sources__preview li { display: flex; align-items: center; gap: 7px; min-width: 0; max-width: min(240px,100%); padding: 6px 10px 6px 6px; background: #f1f4f8; border: 1px solid #e5eaf1; border-radius: 9px; }
.agent-sources__number { display: grid; place-items: center; flex: 0 0 22px; height: 22px; border-radius: 6px; color: #526384; background: white; font-size: .65rem; font-variant-numeric: tabular-nums; }
.agent-sources__preview a,.agent-sources__title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
a { color: inherit; text-decoration: none; } a:hover { color: #295fc7; text-decoration: underline; }
button { display: inline-flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 36px; border: 0; border-radius: 8px; background: transparent; color: #647189; cursor: pointer; padding: 6px 9px; font: inherit; }
button:hover { background: #eaf0f8; color: #284f91; }
button:focus-visible,a:focus-visible { outline: 2px solid #3569d4; outline-offset: 2px; }
.agent-sources__popover { position: absolute; z-index: 20; bottom: calc(100% - 4px); left: 0; width: min(380px,100%); box-sizing: border-box; padding: 8px; border: 1px solid #e1e7ef; border-radius: 16px; background: #fff; box-shadow: 0 16px 48px #182d4926,0 2px 6px #182d490d; }
.agent-sources__popover header { display: flex; align-items: center; gap: 8px; padding: 4px 6px 8px; border-bottom: 1px solid #edf0f5; }
.agent-sources__popover.is-below { bottom: auto; top: calc(100% - 4px); }
.agent-sources__popover header > span { color: #748098; font-size: .7rem; }
.agent-sources__popover header button { margin-left: auto; font-size: 1.25rem; }
.agent-sources__popover ul { max-height: min(200px,24dvh); overflow-y: auto; overscroll-behavior: contain; }
.agent-sources__popover li { display: flex; gap: 10px; padding: 10px 8px; overflow-wrap: anywhere; border-radius: 8px; }
.agent-sources__popover li:hover { background: #f5f7fb; }
.agent-sources__popover li > div { display: grid; gap: 3px; min-width: 0; }
.agent-sources__popover .agent-sources__number { background: #edf2fa; }
.agent-sources__popover small { color: #738098; font-size: .65rem; }
</style>
