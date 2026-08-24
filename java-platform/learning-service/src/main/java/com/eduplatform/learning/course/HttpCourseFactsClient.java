package com.eduplatform.learning.course;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.service.QuestionException;
import java.net.http.HttpClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 通过 course-service JSON API 取得发布/owner 事实；超时与 5xx 统一为可安全重试的 503。 */
@Component
public class HttpCourseFactsClient implements CourseFactsClient {
    private final RestClient course;

    public HttpCourseFactsClient(LearningProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.course().requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.course().requestTimeout());
        this.course = RestClient.builder().baseUrl(properties.course().baseUrl()).requestFactory(factory).build();
    }

    @Override
    public CourseFact requirePublished(String courseId, String traceId) {
        try {
            Published response = course.get().uri("/api/v1/courses/{courseId}", courseId)
                    .header("X-Trace-Id", traceId).retrieve().body(Published.class);
            if (response == null || response.publishedVersion() == null || response.teacher() == null) throw unavailable();
            return fact(response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) throw notFound();
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public CourseFact requireOwnedPublished(String courseId, String teacherId, String traceId) {
        try {
            // owner 请求只用于不可枚举的所有权验证；公开投影随后提供当前发布版本和完整目录。
            course.get().uri("/api/v1/teacher/courses/{courseId}", courseId)
                    .header("X-User-Id", teacherId).header("X-User-Roles", "TEACHER")
                    .header("X-User-Permissions", "teaching:access").header("X-Trace-Id", traceId)
                    .retrieve().toBodilessEntity();
            CourseFact fact = requirePublished(courseId, traceId);
            if (!teacherId.equals(fact.ownerTeacherId())) throw notFound();
            return fact;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 403) throw notFound();
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static CourseFact fact(Published value) {
        Set<String> lessonIds = new LinkedHashSet<>();
        if (value.chapters() != null) for (Chapter chapter : value.chapters()) {
            if (chapter.lessons() != null) for (Lesson lesson : chapter.lessons()) lessonIds.add(lesson.id());
        }
        return new CourseFact(value.id(), value.publishedVersion(), value.accessType(), value.teacher().id(), Set.copyOf(lessonIds));
    }

    private static QuestionException notFound() {
        return new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
    }

    private static QuestionException unavailable() {
        return new QuestionException(HttpStatus.SERVICE_UNAVAILABLE, "LEARNING.COURSE_DEPENDENCY_UNAVAILABLE", "课程服务暂时不可用，请稍后重试");
    }

    private record Published(String id, String publishedVersion, String accessType, Teacher teacher, List<Chapter> chapters) {}
    private record Teacher(String id) {}
    private record Chapter(List<Lesson> lessons) {}
    private record Lesson(String id) {}
}
