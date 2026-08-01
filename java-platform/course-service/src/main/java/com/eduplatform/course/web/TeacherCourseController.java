package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseCatalogService;
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

@RestController
@RequestMapping("/api/v1/teacher/courses")
@Validated
public class TeacherCourseController {
    private final CourseCatalogService service;

    public TeacherCourseController(CourseCatalogService service) {
        this.service = service;
    }

    @GetMapping
    CourseDtos.TeacherCoursePage list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.listTeacherCourses(teacher.userId(), page, size);
    }

    @PostMapping
    ResponseEntity<CourseDtos.TeacherCourse> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.CreateCourseRequest request) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        CourseCatalogService.CreateResult result = service.create(teacher.userId(), request);
        if (!result.created()) {
            return ResponseEntity.ok(result.course());
        }
        return ResponseEntity.created(URI.create("/api/v1/teacher/courses/" + result.course().id()))
                .body(result.course());
    }

    @GetMapping("/{courseId}")
    CourseDtos.TeacherCourse get(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.getTeacherCourse(courseId, teacher.userId());
    }

    @PutMapping("/{courseId}")
    CourseDtos.TeacherCourse update(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.UpdateCourseRequest request) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.updateMetadata(courseId, teacher.userId(), request);
    }

    @PutMapping("/{courseId}/curriculum")
    CourseDtos.TeacherCourse replaceCurriculum(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.ReplaceCurriculumRequest request) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.replaceCurriculum(courseId, teacher.userId(), request);
    }

    @PutMapping("/{courseId}/publication")
    CourseDtos.CoursePublication updatePublication(
            @PathVariable @Pattern(regexp = CourseController.UUID_PATTERN) String courseId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody CourseDtos.UpdatePublicationRequest request) {
        TeacherIdentity teacher = TeacherIdentity.require(userId, roles);
        return service.updatePublication(courseId, teacher.userId(), request);
    }
}
