package com.eduplatform.course.media;

/**
 * course-service 调用 media-service 绑定验证契约的端口。
 *
 * <p>输入只有稳定 mediaId、预期用途和可信教师身份；成功返回可持久化的安全快照，失败必须在课程写入前终止。</p>
 */
public interface MediaBindingClient {
    /**
     * 验证资产属于当前教师、用途精确匹配且已 READY。
     *
     * @param mediaId media-service 分配的稳定资产 ID
     * @param purpose 课程字段要求的精确媒体用途
     * @param teacherId 当前课程所有者的可信用户 ID
     * @param traceId 贯穿 HTTP、课程事务和 Outbox 的调用链标识
     * @throws com.eduplatform.course.service.CourseException 资产不可绑定或媒体服务不可用时抛出
     */
    BindingSnapshot requireBindable(String mediaId, String purpose, String teacherId, String traceId);

    /** 不包含 Bucket、Object Key、URL 或凭据的绑定时不可变快照。 */
    record BindingSnapshot(String mediaId, String purpose, String originalFileName, String mimeType,
            long size, java.util.Set<String> variants, String processingContractVersion) {}
}
