package com.eduplatform.learning.media;

import java.io.InputStream;

/**
 * learning-service 使用 media-service 稳定契约的端口。
 *
 * <p>绑定校验只传稳定 ID；导入读取返回一次性的流，调用方必须关闭。端口不暴露 Bucket、Object Key
 * 或 OSS SDK，也不允许调用方持久化授权 URL。</p>
 */
public interface MediaClient {
    /** 校验指定稳定媒体 ID 是否属于教师、用途一致且已 READY；失败时抛出可映射的业务异常。 */
    void requireBindable(String mediaId, String purpose, String teacherId, String traceId);

    /** 为教师本人导入源申请一次性原件读取并返回流；调用者必须关闭，短期 URL 不得持久化。 */
    InputStream openOriginal(String mediaId, String teacherId, String traceId);

    /**
     * 请求 media-service 将一张允许主机的 HTTPS 历史图片转存为 READY QUESTION_IMAGE。
     * @param clientRequestId 来源题目和图片序号派生的稳定 UUID，重试不得重复建资产
     * @param sourceUrl 仅用于本次调用的外部地址；实现不得记录或持久化
     * @return media-service 生成的稳定 mediaId
     */
    String transferExternalImage(String clientRequestId, String sourceUrl, String ownerId,
            String scope, String traceId);

    /** 既有教师私有导入兼容入口；管理员系统导入必须显式调用带 scope 的方法。 */
    default String transferExternalImage(String clientRequestId, String sourceUrl, String teacherId,
            String traceId) {
        return transferExternalImage(clientRequestId, sourceUrl, teacherId, "TEACHER", traceId);
    }
}
