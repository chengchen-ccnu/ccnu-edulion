package com.eduplatform.learning.course;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.service.QuestionException;
import java.net.http.HttpClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** 通过 course-service JSON API 取得发布/owner 事实；超时与 5xx 统一为可安全重试的 503。 */
@Component
public class HttpCourseFactsClient implements CourseFactsClient {
    private final RestClient course;
    private final String serviceToken;

    public HttpCourseFactsClient(LearningProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.course().requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.course().requestTimeout());
        this.course = RestClient.builder().baseUrl(properties.course().baseUrl()).requestFactory(factory).build();
        this.serviceToken = properties.media().authorizationSecret();
    }

    @Override
    public CourseFact requirePublished(String courseId, String traceId) {
        // 系统暂停是即时授权事实；旧展示缓存不能成为暂停后读取内容的依据。
        return fetchPublished(courseId, traceId);
    }

    /** Learning 课程事务每次请求当前暂停事实；未知资源与依赖故障不伪造 ACTIVE。 */
    @Override
    public CourseAvailability availability(String courseId, long afterSequence, String traceId) {
        try {
            JsonNode value = course.get()
                    .uri("/internal/v1/courses/{courseId}/availability?afterSequence={after}", courseId, afterSequence)
                    .header("X-Trace-Id", traceId).header("X-Internal-Caller", "learning-service")
                    .header("X-Internal-Service-Token", serviceToken == null ? "" : serviceToken)
                    .retrieve().body(JsonNode.class);
            return availabilityFact(value, courseId, afterSequence);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) throw notFound();
            if (exception.getStatusCode().value() == 409) throw suspended(exception);
            throw unavailable();
        } catch (QuestionException exception) { throw exception; }
        catch (RuntimeException exception) { throw unavailable(); }
    }

    /** 必需标量必须保持契约类型；缺少 paused/hasMore 或区间字段时拒绝，不能默认成活动课程。 */
    private static CourseAvailability availabilityFact(JsonNode value, String courseId, long after) {
        if (value == null || !value.isObject() || !courseId.equals(strictText(value.path("courseId")))
                || isBlank(strictText(value.path("ownerTeacherId"))) || isBlank(strictText(value.path("supportEmail")))
                || !value.path("paused").isBoolean() || !value.path("hasMore").isBoolean()
                || !value.path("availabilityRevision").isIntegralNumber() || !value.path("availabilityRevision").canConvertToLong()
                || value.path("availabilityRevision").longValue() < 0 || !value.path("intervals").isArray()
                || value.path("intervals").size() > 100) throw unavailable();
        java.util.UUID.fromString(value.path("ownerTeacherId").asText());
        long revision = value.path("availabilityRevision").longValue();
        long previous = after;
        Instant previousEnd = null;
        boolean openIntervalSeen = false;
        List<CourseAvailability.Interval> intervals = new java.util.ArrayList<>();
        for (JsonNode interval : value.path("intervals")) {
            if (!interval.path("sequence").isIntegralNumber() || !interval.path("sequence").canConvertToLong()
                    || interval.path("sequence").longValue() <= previous || interval.path("sequence").longValue() > revision
                    || isBlank(strictText(interval.path("suspensionId"))) || isBlank(strictText(interval.path("suspendedAt")))
                    || !interval.has("resumedAt") || (!interval.path("resumedAt").isNull() && !interval.path("resumedAt").isTextual())) throw unavailable();
            java.util.UUID.fromString(interval.path("suspensionId").asText());
            Instant start = Instant.parse(interval.path("suspendedAt").asText());
            Instant end = interval.path("resumedAt").isNull() ? null : Instant.parse(interval.path("resumedAt").asText());
            if ((end != null && end.isBefore(start)) || openIntervalSeen
                    || (previousEnd != null && start.isBefore(previousEnd))
                    || (end == null && !value.path("paused").booleanValue())) throw unavailable();
            previousEnd = end; openIntervalSeen = end == null;
            previous = interval.path("sequence").longValue();
            intervals.add(new CourseAvailability.Interval(previous, interval.path("suspensionId").asText(), start, end));
        }
        boolean more = value.path("hasMore").booleanValue();
        if (more && (intervals.isEmpty() || intervals.get(intervals.size()-1).resumedAt() == null)) throw unavailable();
        return new CourseAvailability(courseId, value.path("ownerTeacherId").asText(), revision,
                value.path("paused").booleanValue(), value.path("supportEmail").asText(), List.copyOf(intervals), more);
    }

    /** 仅识别 Course 的安全暂停错误；其他 409 或畸形错误仍作依赖异常处理。 */
    private static QuestionException suspended(RestClientResponseException exception) {
        try {
            JsonNode body = new ObjectMapper().readTree(exception.getResponseBodyAsString());
            if ("COURSE.SUSPENDED".equals(strictText(body.path("code")))
                    && !isBlank(strictText(body.path("supportEmail")))) return new CoursePausedException(body.path("supportEmail").asText());
        } catch (RuntimeException ignored) { /* 不回显依赖的原始错误或 URL。 */ }
        return unavailable();
    }

    private CourseFact fetchPublished(String courseId, String traceId) {
        try {
            Published response = course.get().uri("/api/v1/courses/{courseId}", courseId)
                    .header("X-Trace-Id", traceId).retrieve().body(Published.class);
            if (response == null || response.publishedVersion() == null || response.teacher() == null) throw unavailable();
            return fact(response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) throw notFound();
            if (exception.getStatusCode().value() == 409) throw suspended(exception);
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public CourseFact requireOwnedPublished(String courseId, String teacherId, String traceId) {
        CourseFact fact = ownedPublishedOrEmpty(courseId, teacherId, traceId);
        if (fact == null) throw notFound();
        return fact;
    }

    /** 教师 owner 的无发布课程允许分析返回空态；Course 5xx/超时依旧 503，非 owner 依旧 404。 */
    @Override
    public CourseFact ownedPublishedOrEmpty(String courseId, String teacherId, String traceId) {
        try {
            // owner 请求只用于不可枚举的所有权验证；公开投影随后提供当前发布版本和完整目录。
            course.get().uri("/api/v1/teacher/courses/{courseId}", courseId)
                    .header("X-User-Id", teacherId).header("X-User-Roles", "TEACHER")
                    .header("X-User-Permissions", "teaching:access").header("X-Trace-Id", traceId)
                    .retrieve().toBodilessEntity();
            CourseFact fact;
            try {
                // 教师统计每次确认当前发布身份与可用性，实时读取不能由旧允许状态替代。
                fact = fetchPublished(courseId, traceId);
            } catch (QuestionException missing) {
                if (missing.status() == HttpStatus.NOT_FOUND) return null;
                throw missing;
            }
            if (!teacherId.equals(fact.ownerTeacherId())) throw notFound();
            return fact;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 403) throw notFound();
            if (exception.getStatusCode().value() == 409) throw suspended(exception);
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public LessonContentIdentity requireContentIdentity(String courseId, String lessonId,
            String contentRevisionId, String traceId) {
        try {
            Identity response = course.post()
                    .uri("/internal/v1/courses/{courseId}/lesson-content-identities:verify", courseId)
                    .header("X-Trace-Id", traceId)
                    .header("X-Internal-Caller", "learning-service")
                    .header("X-Internal-Service-Token", serviceToken == null ? "" : serviceToken)
                    .body(Map.of("lessonId", lessonId, "contentRevisionId", contentRevisionId))
                    .retrieve().body(Identity.class);
            if (response == null || isBlank(response.courseId()) || isBlank(response.lessonId())
                    || isBlank(response.contentRevisionId()) || !isSupportedLessonType(response.lessonType())
                    || !courseId.equals(response.courseId()) || !lessonId.equals(response.lessonId())
                    || !contentRevisionId.equals(response.contentRevisionId())) throw unavailable();
            return new LessonContentIdentity(response.courseId(), response.lessonId(), response.contentRevisionId(),
                    response.lessonType(), response.currentPublished());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) throw notFound();
            if (exception.getStatusCode().value() == 409) throw suspended(exception);
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 将 Course 发布详情裁剪为 Learning 所需的稳定事实，并拒绝缺失、空白或未知枚举值。
     * @param value Course HTTP 响应的 Learning 局部读取投影
     * @return 课程 ID、发布版本、访问类型、owner、课时 ID 与课时类型
     * @throws QuestionException 响应不完整或不符合 Learning 读取边界时返回安全 503
     */
    private static CourseFact fact(Published value) {
        if (value == null) throw unavailable();
        String courseId = strictText(value.id());
        String publishedVersion = strictText(value.publishedVersion());
        String accessType = strictText(value.accessType());
        String ownerTeacherId = value.teacher() == null ? null : strictText(value.teacher().id());
        if (isBlank(courseId) || isBlank(publishedVersion) || !isSupportedAccessType(accessType)
                || isBlank(ownerTeacherId) || value.chapters() == null) {
            throw unavailable();
        }
        Set<String> lessonIds = new LinkedHashSet<>();
        if (value.publicationRevision() == null || !value.publicationRevision().canConvertToInt()
                || value.publicationRevision().intValue() < 1) throw unavailable();
        Map<String, String> lessonTypes = new LinkedHashMap<>();
        Map<String, String> contentRevisionIds = new LinkedHashMap<>();
        for (Chapter chapter : value.chapters()) {
            if (chapter == null || chapter.lessons() == null) throw unavailable();
            for (Lesson lesson : chapter.lessons()) {
                String lessonId = lesson == null ? null : strictText(lesson.id());
                String lessonType = lesson == null ? null : strictText(lesson.type());
                String contentRevisionId = lesson == null ? null : strictText(lesson.contentRevisionId());
                if (isBlank(lessonId) || isBlank(contentRevisionId) || !isSupportedLessonType(lessonType)) {
                    throw unavailable();
                }
                lessonIds.add(lessonId);
                lessonTypes.put(lessonId, lessonType);
                contentRevisionIds.put(lessonId, contentRevisionId);
            }
        }
        return new CourseFact(courseId, publishedVersion, accessType, ownerTeacherId,
                value.publicationRevision().intValue(), java.util.Collections.unmodifiableSet(lessonIds),
                Map.copyOf(lessonTypes),
                Map.copyOf(contentRevisionIds));
    }

    /** 只接受 JSON 字符串，避免 Jackson 对数字等标量的默认字符串强制转换掩盖错误响应。 */
    private static String strictText(JsonNode value) {
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isSupportedAccessType(String value) {
        return "PUBLIC".equals(value) || "ENROLLMENT_REQUIRED".equals(value);
    }

    private static boolean isSupportedLessonType(String value) {
        return "VIDEO".equals(value) || "ARTICLE".equals(value) || "ASSESSMENT".equals(value);
    }

    private static QuestionException notFound() {
        return new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
    }

    private static QuestionException unavailable() {
        return new QuestionException(HttpStatus.SERVICE_UNAVAILABLE, "LEARNING.COURSE_DEPENDENCY_UNAVAILABLE", "课程服务暂时不可用，请稍后重试");
    }

    /**
     * Learning 只读取稳定事实；Course 的公开投影可新增展示字段而不破坏本适配器。
     * 必需字段仍由 fact(Published) 做完整性和枚举校验，异常响应统一转为安全 503。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Published(JsonNode id, JsonNode publishedVersion, JsonNode publicationRevision,
            JsonNode accessType, Teacher teacher,
            List<Chapter> chapters) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Teacher(JsonNode id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Chapter(List<Lesson> lessons) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Lesson(JsonNode id, JsonNode contentRevisionId, JsonNode type) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Identity(String courseId, String lessonId, String contentRevisionId,
            String lessonType, boolean currentPublished) {}
}
