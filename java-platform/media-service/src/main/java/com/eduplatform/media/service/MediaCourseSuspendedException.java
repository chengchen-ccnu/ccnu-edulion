package com.eduplatform.media.service;

import org.springframework.http.HttpStatus;

/** 保留 Course 安全邮箱的媒体授权错误，不携带领域原因或资产定位信息。 */
public class MediaCourseSuspendedException extends MediaException {
    private final String supportEmail;
    public MediaCourseSuspendedException(String supportEmail) {
        super(HttpStatus.CONFLICT, "MEDIA.COURSE_SUSPENDED", "课程暂不可用");
        this.supportEmail = supportEmail;
    }
    public String supportEmail() { return supportEmail; }
}
