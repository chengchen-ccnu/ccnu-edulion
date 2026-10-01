package com.eduplatform.iam.service;

import com.eduplatform.iam.persistence.TeacherReviewRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 到期只可靠释放 IAM 引用；实际 OSS 删除由 Media 在无有效引用且宽限期结束后执行。 */
@Component
@ConditionalOnProperty(prefix = "platform.iam.review", name = "retention-enabled", havingValue = "true")
public class TeacherQualificationRetentionWorker {
    private final TeacherReviewRepository reviews;
    private final ReleaseTransaction release;

    public TeacherQualificationRetentionWorker(TeacherReviewRepository reviews, ReleaseTransaction release) {
        this.reviews = reviews; this.release = release;
    }

    /** 每次最多扫描 50 条到期轮次，失败留下未释放行供下轮重试。 */
    @Scheduled(fixedDelayString = "${IAM_REVIEW_RETENTION_POLL_DELAY:1m}")
    public void releaseDue() {
        // V2 既有待审/已通过资质先补建引用，再处理本轮到期释放。
        for (TeacherReviewRepository.Round round : reviews.missingBoundEvents(Instant.now())) release.bindLegacy(round.id());
        for (TeacherReviewRepository.Round round : reviews.dueReleases(Instant.now())) release.one(round.id());
    }

    /** 单轮次更新与释放事件写入同一 IAM 事务，防止标记已清理却丢失引用释放。 */
    @Component
    public static class ReleaseTransaction {
        private final TeacherReviewRepository reviews;
        public ReleaseTransaction(TeacherReviewRepository reviews) { this.reviews = reviews; }

        /** 将向前迁移回填的旧有效轮次转为同事务 Outbox，重复调度不会重复写事件。 */
        @Transactional
        public void bindLegacy(String roundId) {
            TeacherReviewRepository.Round round = reviews.round(roundId);
            Instant now = Instant.now();
            if (round == null || (round.retentionUntil() != null && !round.retentionUntil().isAfter(now))
                    || reviews.markBoundEnqueued(roundId, now) != 1) return;
            reviews.outbox("MediaReferenceBound", "edu.media.events.v1", "media.reference.bound.v1",
                    round.mediaId(), Map.of("mediaId", round.mediaId(), "ownerService", "iam-service",
                            "referenceType", "TEACHER_APPLICATION_ROUND", "referenceId", round.id()),
                    "legacy-ref-" + round.id(), now);
        }

        @Transactional
        public void one(String roundId) {
            TeacherReviewRepository.Round round = reviews.round(roundId);
            Instant now = Instant.now();
            if (round == null || round.retentionUntil() == null || round.retentionUntil().isAfter(now)
                    || reviews.markReleased(roundId, now) != 1) return;
            // 迁移前已到期且从未建立过引用的旧轮次没有可释放的 Media 台账。
            if (round.boundEnqueuedAt() == null) return;
            reviews.outbox("MediaReferenceReleased", "edu.media.events.v1", "media.reference.released.v1",
                    round.mediaId(), Map.of("mediaId", round.mediaId(), "ownerService", "iam-service",
                            "referenceType", "TEACHER_APPLICATION_ROUND", "referenceId", round.id()),
                    "retention-" + round.id(), now);
        }
    }
}
