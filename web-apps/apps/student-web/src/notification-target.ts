import type { NotificationTarget } from "@edu/api-client";
import type { RouteLocationRaw } from "vue-router";
import { invalidateNotificationCounts } from "@edu/auth";
import { nextTick } from "vue";
import { operationsApi } from "./operations-api";

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function isUuid(value: unknown): value is string { return typeof value === "string" && uuid.test(value); }

/** 服务端授权的四种固定目标映射；拒绝缺失稳定ID，绝不接受外部URL或推断课程归属。 */
export function notificationRoute(target: NotificationTarget, notificationId: string): RouteLocationRaw {
  if (!isUuid(notificationId) || !isUuid(target.targetId)) throw new Error("INVALID_NOTIFICATION_TARGET");
  const query = { notificationId };
  if (target.targetType === "TEACHER_REVIEW") return { path: "/apply-teacher", query };
  if (!isUuid(target.courseId)) throw new Error("INVALID_NOTIFICATION_TARGET");
  switch (target.targetType) {
    case "COURSE_ANNOUNCEMENT": return { path: `/courses/${target.courseId}`, query: { ...query, announcementId: target.targetId } };
    case "COURSE_TASK": return { path: `/learn/${target.courseId}/tasks`, query };
    case "COURSE_STATUS": return { path: `/courses/${target.courseId}`, query };
  }
}

/**
 * 普通消息目标成功渲染后才写已读，写入沿用打开前取得的精确消息revision。
 * 页面负责再次读取target并核对真实资源身份；失败/离页/公告均不会提交。
 */
export async function readAfterRender(notificationId: unknown, target: NotificationTarget, signal: AbortSignal) {
  if (!isUuid(notificationId) || target.targetType === "COURSE_ANNOUNCEMENT") return;
  await nextTick();
  if (signal.aborted) return;
  await operationsApi.read(notificationId, target.revision, signal);
  if (!signal.aborted) invalidateNotificationCounts();
}
