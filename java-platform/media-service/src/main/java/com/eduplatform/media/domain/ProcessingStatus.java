package com.eduplatform.media.domain;

/** 可替换 Worker 的处理任务状态，包含明确的成功、失败与取消终态。 */
public enum ProcessingStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
