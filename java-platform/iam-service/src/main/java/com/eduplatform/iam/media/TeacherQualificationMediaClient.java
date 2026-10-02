package com.eduplatform.iam.media;

/** IAM 调用 media-service 的稳定绑定验证端口，不读取媒体 Schema 或 OSS。 */
public interface TeacherQualificationMediaClient {
    /** 验证 mediaId 属于申请人、用途精确为 TEACHER_QUALIFICATION 且状态 READY。 */
    void requireBindable(String mediaId, String userId, String traceId);

    /** IAM 审核详情授权后，为指定管理员签发某轮材料的短期原件读取投影。 */
    Grant grant(String mediaId, String administratorId, boolean download, String traceId);

    /** 确认真实对象可读取；服务故障时抛出异常并使批准事务回滚。 */
    void requireReadable(Grant grant);

    record Grant(String mediaId, String mimeType, long sizeBytes, String method, String url,
            java.util.Map<String, String> headers, java.time.Instant expiresAt) {}
}
