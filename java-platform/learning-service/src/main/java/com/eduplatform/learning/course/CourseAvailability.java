package com.eduplatform.learning.course;

import java.time.Instant;
import java.util.List;

/** Course 内部 API 的 Learning 局部事实投影；不得携带原因或课程内容，区间 sequence 为开始水位。 */
public record CourseAvailability(String courseId, String ownerTeacherId, long availabilityRevision, boolean paused,
        String supportEmail, List<Interval> intervals, boolean hasMore) {
    public record Interval(long sequence, String suspensionId, Instant suspendedAt, Instant resumedAt) {}
}
