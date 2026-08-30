package com.eduplatform.iam.media;

/** IAM 调用 media-service 的稳定绑定验证端口，不读取媒体 Schema 或 OSS。 */
public interface TeacherQualificationMediaClient {
    /** 验证 mediaId 属于申请人、用途精确为 TEACHER_QUALIFICATION 且状态 READY。 */
    void requireBindable(String mediaId, String userId, String traceId);
}
