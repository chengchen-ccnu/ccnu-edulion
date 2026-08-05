package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 从 Gateway 可信头解析出的已登录课程访问者；不接受请求体声明的用户身份。 */
record CourseViewerIdentity(String userId) {
    /**
     * 要求存在格式正确的 Gateway 用户 ID。
     * @throws CourseException 身份缺失或格式无效时返回 401
     */
    static CourseViewerIdentity require(String userId) {
        if (userId == null || userId.isBlank() || !isUuid(userId)) {
            throw new CourseException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        return new CourseViewerIdentity(userId);
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
