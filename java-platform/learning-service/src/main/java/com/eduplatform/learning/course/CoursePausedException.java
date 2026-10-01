package com.eduplatform.learning.course;

import com.eduplatform.learning.service.QuestionException;
import org.springframework.http.HttpStatus;

/** 学习暂停只向调用者说明通用状态和支持邮箱；未落库进度逻辑舍弃，不泄露撤权原因。 */
public class CoursePausedException extends QuestionException {
    private final String supportEmail;
    public CoursePausedException(String supportEmail) {
        super(HttpStatus.CONFLICT, "LEARNING.COURSE_SUSPENDED", "课程暂不可用");
        this.supportEmail = supportEmail;
    }
    public String supportEmail() { return supportEmail; }
}
