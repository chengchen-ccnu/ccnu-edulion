import { createOperationsApi } from "@edu/api-client";
import type { NotificationCounts } from "@edu/api-client";
import type { AuthStore } from "./index";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

/** 成功确认/已读后通知当前应用的角标重读；只发失效信号，不缓存用户消息或计数。 */
export function invalidateNotificationCounts() {
  window.dispatchEvent(new Event("edu-notifications-changed"));
}

/**
 * 三端按身份变化、消息打开、页面恢复可见刷新本人计数。
 * 请求合并；退出/换号/离页中止并清除旧身份结果；故障显示未知而非零，无轮询或无限重试。
 */
export function useNotificationCounts(store: AuthStore) {
  const counts = ref<NotificationCounts | null>(null);
  const unavailable = ref(false);
  const api = createOperationsApi(store.authorizedRequest);
  let controller: AbortController | null = null;
  let inFlight: Promise<void> | null = null;
  let retryAt = 0;
  async function refresh() {
    if (store.state.status !== "authenticated" || document.hidden || Date.now() < retryAt) return;
    if (inFlight) return inFlight;
    const active = new AbortController();
    controller = active;
    inFlight = (async () => {
      try {
        const value = await api.counts(active.signal);
        if (!active.signal.aborted) { counts.value = value; unavailable.value = false; }
      } catch (error) {
        if (!active.signal.aborted) {
          counts.value = null; unavailable.value = true;
          const seconds = (error as { retryAfterSeconds?: number }).retryAfterSeconds;
          retryAt = Date.now() + Math.max(1, seconds ?? 5) * 1000;
        }
      } finally { if (controller === active) { controller = null; inFlight = null; } }
    })();
    return inFlight;
  }
  function reset() { controller?.abort(); controller = null; inFlight = null; counts.value = null; unavailable.value = false; retryAt = 0; }
  watch([() => store.state.status, () => store.state.currentUser?.id, () => store.state.currentUser?.permissions.join(",")],
    () => { reset(); void refresh(); }, { immediate: true });
  function visible() { if (!document.hidden) void refresh(); }
  onMounted(() => {
    window.addEventListener("edu-notifications-changed", refresh);
    document.addEventListener("visibilitychange", visible);
  });
  onBeforeUnmount(() => {
    reset(); window.removeEventListener("edu-notifications-changed", refresh);
    document.removeEventListener("visibilitychange", visible);
  });
  return { counts, unavailable, refresh };
}
