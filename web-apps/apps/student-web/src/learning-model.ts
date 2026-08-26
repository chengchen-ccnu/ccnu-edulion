import type { CourseDetail, CourseProgress, Enrollment, LessonProgress } from "@edu/api-client";

import { courseLearningEntries } from "./course-learning";

/** 页面展示使用的报名、课程和进度组合模型；字段仍引用生成类型，不复制外部契约。 */
export interface MyLearningItem {
  enrollment: Enrollment;
  course: CourseDetail | null;
  progress: CourseProgress | null;
  courseError: string;
  progressError: string;
}

/** 返回最近产生进度事件的课时；没有记录时返回 null。 */
export function latestLessonProgress(progress: CourseProgress | null): LessonProgress | null {
  if (!progress?.lessons.length) return null;
  return [...progress.lessons].sort((left, right) =>
    Date.parse(right.lastEventAt) - Date.parse(left.lastEventAt))[0] ?? null;
}

/**
 * 为“继续学习”选择稳定课时。优先定位最近学习的有效视频课；课程版本变化导致旧课时消失时退回首课。
 */
export function resumeLessonId(course: CourseDetail, progress: CourseProgress | null,
    enrolled: boolean): string | null {
  const entries = courseLearningEntries(course, enrolled);
  const latest = latestLessonProgress(progress);
  if (latest && entries.some(item => item.lesson.id === latest.lessonId)) return latest.lessonId;
  return entries.find(item => item.accessible)?.lesson.id ?? null;
}

/** 将服务端返回的单课进度并入当前课程投影，防止重复或乱序响应让页面数值倒退。 */
export function mergeCourseProgress(current: CourseProgress, incoming: LessonProgress): CourseProgress {
  const existing = current.lessons.find(item => item.lessonId === incoming.lessonId);
  const merged: LessonProgress = existing ? {
    ...incoming,
    positionSeconds: Math.max(existing.positionSeconds, incoming.positionSeconds),
    watchedSeconds: Math.max(existing.watchedSeconds, incoming.watchedSeconds),
    completed: existing.completed || incoming.completed,
    lastEventAt: Date.parse(existing.lastEventAt) > Date.parse(incoming.lastEventAt)
      ? existing.lastEventAt : incoming.lastEventAt,
    revision: Math.max(existing.revision, incoming.revision)
  } : incoming;
  const lessons = existing
    ? current.lessons.map(item => item.lessonId === merged.lessonId ? merged : item)
    : [...current.lessons, merged];
  const completedLessonCount = lessons.filter(item => item.completed).length;
  return {
    ...current,
    lessons,
    completedLessonCount,
    completionPercent: current.lessonCount === 0 ? 0
      : Math.round(completedLessonCount * 10_000 / current.lessonCount) / 100
  };
}

export function isCompleted(item: MyLearningItem): boolean {
  return (item.progress?.completionPercent ?? 0) >= 100;
}

/** 将当前视频位置换算为课时内播放百分比；媒体时长未知时返回 0，已完成课时稳定显示 100。 */
export function videoProgressPercent(positionSeconds: number, durationSeconds: number, completed: boolean): number {
  if (completed) return 100;
  if (!Number.isFinite(positionSeconds) || !Number.isFinite(durationSeconds) || durationSeconds <= 0) return 0;
  return Math.min(100, Math.max(0, Math.round(positionSeconds * 1_000 / durationSeconds) / 10));
}
