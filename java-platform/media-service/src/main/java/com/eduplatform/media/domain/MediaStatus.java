package com.eduplatform.media.domain;

/** 稳定媒体资产状态，与具体处理任务状态分开建模。 */
public enum MediaStatus {
    UPLOADED,
    VERIFYING,
    PROCESSING,
    READY,
    REJECTED,
    FAILED,
    EXPIRED,
    DELETING,
    DELETED
}
