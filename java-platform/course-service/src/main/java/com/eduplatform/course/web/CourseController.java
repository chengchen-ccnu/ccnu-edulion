package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseCatalogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courses")
@Validated
public class CourseController {
    static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final CourseCatalogService service;

    public CourseController(CourseCatalogService service) {
        this.service = service;
    }

    @GetMapping
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
    CourseDtos.CourseDetail detail(@PathVariable @Pattern(regexp = UUID_PATTERN) String courseId) {
        return service.getPublished(courseId);
    }

    @GetMapping("/categories")
    List<CourseDtos.CategorySummary> categories() {
        return service.listActiveCategories();
    }
}
