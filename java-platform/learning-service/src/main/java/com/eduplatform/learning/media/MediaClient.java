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
}
