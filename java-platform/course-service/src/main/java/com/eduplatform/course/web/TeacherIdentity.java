package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseException;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 从 Gateway 可信头解析出的教师身份；只用于 course-service 的所有权约束。 */
record TeacherIdentity(String userId) {
    /**
     * 校验用户 ID 格式和 TEACHER 角色。
     * @throws CourseException 身份缺失返回未认证，角色缺失返回禁止访问
     */
    static TeacherIdentity require(String userId, String roles) {
        if (userId == null || userId.isBlank() || !isUuid(userId)) {
            throw new CourseException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        boolean teacher = roles != null && Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch("TEACHER"::equals);
        if (!teacher) {
            throw new CourseException(HttpStatus.FORBIDDEN, "IAM.FORBIDDEN", "无权访问该资源");
        }
        return new TeacherIdentity(userId);
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
