package com.eduplatform.course.media;

import com.eduplatform.course.web.CourseDtos;

/** course-service 完成课程可见性与动作判断后申请非播放媒体授权的内部端口。 */
public interface MediaAccessClient {
    /**
     * 申请精确用途、变体和处置方式的短期授权；所有参数均由课程绑定解析，不能来自浏览器 mediaId。
     */
    CourseDtos.CourseMediaAccessGrant issue(String mediaId, String purpose, String visitorId,
            String accessType, String variant, String disposition, String traceId);
}
