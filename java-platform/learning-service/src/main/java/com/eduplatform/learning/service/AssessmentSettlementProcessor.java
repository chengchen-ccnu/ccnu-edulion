package com.eduplatform.learning.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关闭测评后的草稿结算调度器。每轮最多领取 10 场、每场最多处理 50 份草稿，
 * 不使用无限循环；进程中断时数据库中的 PENDING 事实会在下轮继续推进。
 */
@Component
public class AssessmentSettlementProcessor {
    static final int ASSESSMENTS_PER_RUN = 10;
    static final int ATTEMPTS_PER_BATCH = 50;

    private final LearningAssessmentService learning;

    public AssessmentSettlementProcessor(LearningAssessmentService learning) {
        this.learning = learning;
    }

    /**
     * 先有界扫描最终截止节点，再有界处理草稿结算。单场失败不会阻塞其他场次，并由独立事务记录
     * 最多五次有限退避；日志/失败事实只保存异常类型，不保存学生答案或请求内容。
     */
    @Scheduled(fixedDelayString = "${platform.learning.assessment-settlement-poll-delay:2s}")
    public void settleDueAssessments() {
        for (String assessmentId : learning.dueAssessmentCloseIds(ASSESSMENTS_PER_RUN)) {
            try {
                learning.autoCloseAssessment(assessmentId);
            } catch (RuntimeException ignored) {
                // 下一轮继续扫描；关闭没有写入成功事实时，不得把场次当成已结算。
            }
        }
        for (String assessmentId : learning.dueSettlementAssessments(ASSESSMENTS_PER_RUN)) {
            try {
                learning.processSettlementBatch(assessmentId, ATTEMPTS_PER_BATCH);
            } catch (RuntimeException failure) {
                learning.recordSettlementFailure(assessmentId, failure);
            }
        }
    }
}
