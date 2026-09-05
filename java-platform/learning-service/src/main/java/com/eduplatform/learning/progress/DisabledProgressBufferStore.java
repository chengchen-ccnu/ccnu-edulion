package com.eduplatform.learning.progress;

import com.eduplatform.learning.persistence.LearningAssessmentRepository.ProgressRow;
import com.eduplatform.learning.web.LearningDtos;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 非 local/test 或 Redis 自动配置未启用时的明确失败实现。
 * 旧客户端和强制检查点仍可使用 MySQL；显式 HEARTBEAT 不得因为缺少 Redis 而伪造成功。
 */
@Component
@ConditionalOnMissingBean(RedisProgressBufferStore.class)
public class DisabledProgressBufferStore implements ProgressBufferStore {
    @Override
    public BufferResult buffer(String learnerId, String courseId, String lessonId, ProgressRow persisted,
            LearningDtos.ReportProgressRequest input, Instant now) {
        throw new ProgressBufferUnavailableException("进度缓冲暂时不可用");
    }

    @Override
    public ReadResult read(String learnerId, String courseId, String lessonId) {
        return new ReadResult(false, Optional.empty());
    }

    @Override
    public boolean backlogExceedsLimit() {
        throw new ProgressBufferUnavailableException("进度缓冲暂时不可用");
    }
}
