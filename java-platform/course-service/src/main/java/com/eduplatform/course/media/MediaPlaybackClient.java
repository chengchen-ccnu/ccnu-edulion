package com.eduplatform.course.media;

import com.eduplatform.course.web.CourseDtos;

/** course-service 在完成领域可见性判断后申请媒体短期访问授权的服务端端口。 */
public interface MediaPlaybackClient {
    /**
     * 优先申请 hls-fmp4-v2 播放授权；旧资产没有 v2 时由实现有限回退 hls-v1。
     *
     * @param mediaId 课程绑定中解析出的稳定媒体 ID
     * @param purpose 课程绑定中解析出的精确用途，当前必须为 LESSON_VIDEO
     * @param visitorId Gateway 验证后的实际访问者 ID
     * @param traceId 当前调用链 ID
     * @return media-service 签发的短期 HLS 清单授权
     */
    CourseDtos.CourseMediaPlaybackGrant issuePlayback(
            String mediaId, String purpose, String visitorId, String traceId);
}
