package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseCatalogService;
import com.eduplatform.course.config.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师课程管理接口，消费 Gateway 注入的可信用户头并把所有读写限定到该教师。
 *
 * <p>创建支持客户端请求 ID 幂等；媒体写入只接受稳定 ID，绑定校验、引用 Outbox、目录替换和发布
 * 都由服务层事务与乐观版本控制保护。</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/courses")
@Validated
public class TeacherCourseController {
    private final CourseCatalogService service;

    public TeacherCourseController(CourseCatalogService service) {
        this.service = service;
    }

    @GetMapping
    /** 返回当前教师自己的课程分页，不接受请求参数指定其他所有者。 */
    CourseDtos.TeacherCoursePage list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.listTeacherCourses(teacher.userId(), page, size);
    }

    @PostMapping
    /** 创建课程草稿；重复客户端请求返回 200，首次创建返回 201 与资源地址。 */
    ResponseEntity<CourseDtos.TeacherCourse> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.CreateCourseRequest request,
            HttpServletRequest servletRequest) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        CourseCatalogService.CreateResult result = service.create(
                teacher.userId(), request, trace(servletRequest));
        if (!result.created()) {
            return ResponseEntity.ok(result.course());
        }
        return ResponseEntity.created(URI.create("/api/v1/teacher/courses/" + result.course().id()))
                .body(result.course());
    }

    @GetMapping("/{courseId}")
    /** 读取当前教师拥有的单门课程编辑快照。 */
    CourseDtos.TeacherCourse get(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.getTeacherCourse(courseId, teacher.userId());
    }

    @PutMapping("/{courseId}")
    /** 更新元数据；请求 revision 过期时返回冲突且不覆盖新版本。 */
    CourseDtos.TeacherCourse update(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.UpdateCourseRequest request,
            HttpServletRequest servletRequest) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.updateMetadata(courseId, teacher.userId(), request, trace(servletRequest));
    }

    @PutMapping("/{courseId}/curriculum")
    /** 原子替换草稿目录；重复内容视为幂等成功。 */
    CourseDtos.TeacherCourse replaceCurriculum(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.ReplaceCurriculumRequest request,
            HttpServletRequest servletRequest) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.replaceCurriculum(courseId, teacher.userId(), request, trace(servletRequest));
    }

    @PutMapping("/{courseId}/publication")
    /** 发布或下架课程，并返回转换后的发布状态。 */
    CourseDtos.CoursePublication updatePublication(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.UpdatePublicationRequest request) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.updatePublication(courseId, teacher.userId(), request);
    }

    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(TraceFilter.ATTRIBUTE);
    }
}
