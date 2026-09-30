package com.eduplatform.course.service;

import org.springframework.http.HttpStatus;

/** 暂停课程只携带安全公开邮箱；不把内部原因写入统一错误信封。 */
public class CourseSuspendedException extends CourseException {
    private final String supportEmail;
    public CourseSuspendedException(String supportEmail) {
        super(HttpStatus.CONFLICT, "COURSE.SUSPENDED", "课程暂不可用");
        this.supportEmail = supportEmail;
    }
    public String supportEmail() { return supportEmail; }
}
