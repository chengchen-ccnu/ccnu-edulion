<script setup lang="ts">
/** 公共协议深链页面；每次进入都读取服务端当前不可变正文，与注册弹窗使用同一来源。 */
import type { ProtocolDocumentType } from "@edu/api-client";
import { computed, onMounted, ref } from "vue";
import { accountSecurityApi } from "../account-security-api";
import AccountFrame from "../components/AccountFrame.vue";

const props = defineProps<{ type: ProtocolDocumentType }>();
const document = ref<Awaited<ReturnType<typeof accountSecurityApi.protocols>>["documents"][number] | null>(null);
const errorMessage = ref("");
const loading = ref(true);
const title = computed(() => props.type === "TERMS" ? "用户协议" : "隐私政策");

onMounted(async () => {
  try { document.value = (await accountSecurityApi.protocols()).documents.find(item => item.documentType === props.type) ?? null;
    if (!document.value) errorMessage.value = "当前协议不可用。"; }
  catch { errorMessage.value = "当前协议加载失败，请稍后刷新重试。"; }
  finally { loading.value = false; }
});
</script>

<template>
  <AccountFrame badge="公共协议" :title="title" description="以下内容来自 Gateway 当前生效的不可变服务端文档。">
    <p v-if="loading" class="account-loading" role="status">正在加载正文…</p>
    <div v-else-if="errorMessage" class="account-error-block" role="alert">{{ errorMessage }}</div>
    <article v-else-if="document" class="protocol-document"><header><strong>版本 {{ document.version }}</strong><span>生效于 {{ new Date(document.effectiveAt).toLocaleString() }}</span></header><div class="protocol-body">{{ document.body }}</div></article>
    <nav class="account-links" aria-label="协议操作"><RouterLink to="/register">返回注册</RouterLink><RouterLink to="/login">返回登录</RouterLink></nav>
  </AccountFrame>
</template>
