package com.eduplatform.media.domain;

/** 上传会话状态机；终态不会被普通重放重新激活。 */
public enum UploadStatus {
    CREATED,
    UPLOADING,
    UPLOADED,
    VERIFYING,
    PROCESSING,
    READY,
    REJECTED,
    FAILED,
    EXPIRED,
    CANCELED
}
