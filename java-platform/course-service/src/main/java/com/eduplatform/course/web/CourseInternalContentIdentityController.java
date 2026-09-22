package com.eduplatform.course.web;

import com.eduplatform.course.config.CourseMediaProperties;
import com.eduplatform.course.service.CourseCatalogService;
import com.eduplatform.course.service.CourseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learning 专用的精确课时内容身份校验；路径不经过浏览器 Gateway，也不能枚举历史目录。 */
@RestController
@RequestMapping("/internal/v1/courses")
@Validated
public class CourseInternalContentIdentityController {
    private final CourseCatalogService service;
    private final CourseMediaProperties properties;

    public CourseInternalContentIdentityController(CourseCatalogService service, CourseMediaProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * @param courseId Learning 已从可信请求路径取得的课程 ID
     * @param request 精确 lessonId/contentRevisionId 三元组
     * @return 该身份是否为当前发布内容；历史已发布播放器也可得到 false
     */
    @PostMapping("/{courseId}/lesson-content-identities:verify")
    CourseDtos.LessonContentIdentity verify(
            @RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @Valid @RequestBody CourseDtos.VerifyLessonContentIdentityRequest request) {
        authorize(caller, token);
        return service.verifyLessonContentIdentity(courseId, request.lessonId().toString(),
                request.contentRevisionId().toString());
    }

    /** 复用已由 DPAPI/部署 Secret 注入 Course 与 Learning 的服务密钥，不接受浏览器或其他调用方。 */
    private void authorize(String caller, String token) {
        String expected = properties.serviceAuthorization().secret();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "learning-service".equals(caller) && token != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new CourseException(HttpStatus.UNAUTHORIZED, "COURSE.INTERNAL_UNAUTHORIZED", "内部调用身份无效");
        }
    }
}
