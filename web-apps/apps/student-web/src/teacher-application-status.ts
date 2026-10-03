import type { TeacherApplication } from "@edu/api-client";
import { availableWorkspaces } from "@edu/auth";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { accountSecurityApi } from "./account-security-api";
import { authStore } from "./auth";
import { isUuid, readAfterRender } from "./notification-target";
import { operationsApi } from "./operations-api";
import { usePortalFailure } from "./portal-state";

export const applicationLabels = { SUBMITTED: "已提交", UNDER_REVIEW: "审核中", APPROVED: "审核通过", REJECTED: "审核未通过" };

/** 最新申请和真实账号能力同时读取；离页中止，失败不把未知状态解释为未申请或已获权限。 */
export function useTeacherApplicationStatus() {
  const route = useRoute();
  const application = ref<TeacherApplication | null>(null), loading = ref(false), ready = ref(false);
  const failure = usePortalFailure();
  let controller: AbortController | null = null;
  const teaching = computed(() => ready.value ? availableWorkspaces(authStore.state.currentUser).find(item => item.id === "teacher") : undefined);
  /** 读取本人申请及当前能力；只有两者成功才开放入口，消息目标身份匹配后发送普通已读。 */
  async function refresh() {
    if (!failure.allowed()) return;
    controller?.abort(); const active = new AbortController(); controller = active;
    loading.value = true; ready.value = false; failure.error.value = ""; application.value = null;
    try {
      const value = await accountSecurityApi.teacherApplication(active.signal);
      const refreshed = await authStore.ensureCurrentUser(active.signal);
      if (active.signal.aborted) return;
      if (!refreshed) throw new Error("ACCOUNT_REFRESH_FAILED");
      application.value = value; ready.value = true;
      if (isUuid(route.query.notificationId)) {
        const target = await operationsApi.target(route.query.notificationId, active.signal);
        if (target.targetType !== "TEACHER_REVIEW" || !value || target.targetId !== value.id) throw new Error("INVALID_NOTIFICATION_TARGET");
        await readAfterRender(route.query.notificationId, target, active.signal);
      }
    } catch (error) { if (!active.signal.aborted) failure.fail(error); }
    finally { if (!active.signal.aborted) loading.value = false; }
  }
  function visible() { if (!document.hidden) void refresh(); }
  watch([() => route.query.notificationId, () => authStore.state.currentUser?.id], () => {
    controller?.abort(); application.value = null; ready.value = false;
    if (authStore.state.status === "authenticated") void refresh();
  }, { immediate: true });
  onMounted(() => document.addEventListener("visibilitychange", visible));
  onBeforeUnmount(() => { controller?.abort(); document.removeEventListener("visibilitychange", visible); });
  return { application, loading, ready, teaching, error: failure.error, refresh };
}
