package com.eduplatform.learning.progress;

import com.eduplatform.learning.persistence.LearningAssessmentRepository.ProgressRow;
import com.eduplatform.learning.web.LearningDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 学习进度高频缓冲的技术边界。普通心跳只能通过此接口进入 Redis，Worker 以外的代码不能把 Redis 当作事实源。
 */
public interface ProgressBufferStore {
    /** 将一条显式 HEARTBEAT 与已持久化基线原子合并；Redis 不可用时必须抛出可映射的依赖异常。 */
    BufferResult buffer(String learnerId, String courseId, String lessonId, String contentRevisionId,
            ProgressRow persisted, LearningDtos.ReportProgressRequest input, Instant now);

    /** 新暂停水位使旧暂存整体失效；默认实现只用于禁用缓冲的既有适配器。 */
    default BufferResult buffer(String learnerId, String courseId, String lessonId, String contentRevisionId,
            ProgressRow persisted, LearningDtos.ReportProgressRequest input, Instant now, long availabilityRevision) {
        return buffer(learnerId, courseId, lessonId, contentRevisionId, persisted, input, now);
    }

    /** 读取一个学习者课时的缓冲投影；available=false 只表示 Redis 读失败，不表示没有缓冲记录。 */
    ReadResult read(String learnerId, String courseId, String contentRevisionId);

    /** 当前缓冲积压是否已经达到保护阈值；检查失败不能静默放行新的普通心跳。 */
    boolean backlogExceedsLimit();

    record BufferResult(BufferedProgress value, boolean replayed) {}
    record ReadResult(boolean available, Optional<BufferedProgress> value) {}

    /** Redis Hash 中保存的完整聚合快照，watchedSeconds 已是该课时的累计值而非本次增量。 */
    record BufferedProgress(String learnerId, String courseId, String lessonId, String contentRevisionId,
            long positionSeconds,
            long watchedSeconds, boolean completed, Instant lastEventAt, long version,
            Map<LocalDate, Long> watchedByDay, LocalDate firstStartedDay, long availabilityRevision) {
        /** 旧测试快照的初始代际为零；正式 Redis 读取总是带 Course 水位。 */
        public BufferedProgress(String learnerId, String courseId, String lessonId, String contentRevisionId,
                long positionSeconds, long watchedSeconds, boolean completed, Instant lastEventAt, long version,
                Map<LocalDate, Long> watchedByDay, LocalDate firstStartedDay) {
            this(learnerId, courseId, lessonId, contentRevisionId, positionSeconds, watchedSeconds, completed,
                    lastEventAt, version, watchedByDay, firstStartedDay, 0);
        }
        /** 兼容既有缓冲测试构造；旧快照没有可靠的每日明细，不伪造回填。 */
        public BufferedProgress(String learnerId, String courseId, String lessonId, String contentRevisionId,
                long positionSeconds, long watchedSeconds, boolean completed, Instant lastEventAt, long version) {
            this(learnerId, courseId, lessonId, contentRevisionId, positionSeconds, watchedSeconds,
                    completed, lastEventAt, version, Map.of(), null);
        }
    }
}
