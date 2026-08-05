package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseCatalogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courses")
@Validated
/** 面向匿名学生和已登录用户的公开课程目录接口，只投影当前可见发布版本。 */
public class CourseController {
    static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final CourseCatalogService service;

    public CourseController(CourseCatalogService service) {
        this.service = service;
    }

    @GetMapping
    /** 按 OpenAPI 约定的筛选项和页码返回公开课程；空筛选表示全部。 */
    CourseDtos.CoursePage list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Pattern(regexp = UUID_PATTERN) String categoryId,
            @RequestParam(required = false) @Pattern(regexp = "BEGINNER|INTERMEDIATE|ADVANCED") String level,
            @RequestParam(required = false) @Pattern(regexp = "PUBLIC|ENROLLMENT_REQUIRED") String accessType) {
        return service.listPublished(keyword, categoryId, level, accessType, page, size);
    }

    @GetMapping("/{courseId}")
    /** 返回公开课程详情与章节课时，不暴露教师草稿。 */
    CourseDtos.CourseDetail detail(@PathVariable @Pattern(regexp = UUID_PATTERN) String courseId) {
        return service.getPublished(courseId);
    }

    @PostMapping("/{courseId}/lessons/{lessonId}/resources/{resourceId}/playback-grants")
    /**
     * 由服务端解析发布版本与资源绑定后申请视频授权；请求方不能提交 mediaId 或访问判断。
     */
    CourseDtos.CourseMediaPlaybackGrant playback(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String courseId,
            @PathVariable @Pattern(regexp = UUID_PATTERN) String lessonId,
            @PathVariable @Pattern(regexp = UUID_PATTERN) String resourceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            HttpServletRequest servletRequest) {
        CourseViewerIdentity viewer = CourseViewerIdentity.require(userId);
        return service.createPublishedPlaybackGrant(
                courseId, lessonId, resourceId, viewer.userId(), trace(servletRequest));
    }

    @GetMapping("/categories")
    /** 返回启用课程分类，供学生筛选器和教师编辑表单共用。 */
    List<CourseDtos.CategorySummary> categories() {
        return service.listActiveCategories();
    }

    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(com.eduplatform.course.config.TraceFilter.ATTRIBUTE);
    }
}
