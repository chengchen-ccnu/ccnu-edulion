package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时重试历史私有报告并让到期记忆产生 tombstone；每轮有界，失败留待下一轮。 */
@Component
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class MemoryMaintenanceJob {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryMaintenanceJob.class);
    private final MemoryProfileRepository repository;
    private final MemoryFactService facts;

    public MemoryMaintenanceJob(MemoryProfileRepository repository, MemoryFactService facts) {
        this.repository = repository;
        this.facts = facts;
    }

    /** 逐 run 开启独立事务；单条失败不会阻塞其他报告，日志不包含报告正文或候选。 */
    @Scheduled(fixedDelayString = "${platform.agent.memory-normalization-poll-delay:5s}")
    public void normalizePending() {
        for (var runId : repository.pendingReportIds(50)) {
            try {
                facts.normalize(runId);
            } catch (RuntimeException failure) {
                facts.recordNormalizationFailure(runId, failure);
                LOG.warn("agent report normalization failed runId={} errorType={}",
                        runId, failure.getClass().getSimpleName());
            }
        }
    }

    /** 到期转 EXPIRED 并写 DELETE 事件，保留原证据供历史审计。 */
    @Scheduled(fixedDelayString = "${platform.agent.memory-expiry-poll-delay:1m}")
    public void expire() {
        int count = facts.expireDue(Instant.now(), 100);
        int decayed = facts.decayStaleTopicConfidence(Instant.now());
        if (count > 0) LOG.info("agent memories expired count={}", count);
        if (decayed > 0) LOG.info("agent profile topic confidence decayed count={}", decayed);
    }
}
