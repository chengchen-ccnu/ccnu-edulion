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
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** 通过 course-service JSON API 取得发布/owner 事实；超时与 5xx 统一为可安全重试的 503。 */
@Component
public class HttpCourseFactsClient implements CourseFactsClient {
    private static final int MAX_CACHED_COURSES = 256;
    private final RestClient course;
    private final Duration factCacheTtl;
    private final String serviceToken;
    private final ConcurrentHashMap<String, CachedFact> publishedFacts = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();

    public HttpCourseFactsClient(LearningProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.course().requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.course().requestTimeout());
        this.course = RestClient.builder().baseUrl(properties.course().baseUrl()).requestFactory(factory).build();
        this.factCacheTtl = properties.course().factCacheTtl();
        this.serviceToken = properties.media().authorizationSecret();
    }

    @Override
    public CourseFact requirePublished(String courseId, String traceId) {
        CachedFact cached = publishedFacts.get(courseId);
        if (cached != null && cached.expiresAt().isAfter(java.time.Instant.now())) return cached.value();
        if (!factCacheTtl.isZero() && !factCacheTtl.isNegative()) {
            synchronized (cacheLock) {
                cached = publishedFacts.get(courseId);
                if (cached != null && cached.expiresAt().isAfter(java.time.Instant.now())) return cached.value();
                CourseFact fact = fetchPublished(courseId, traceId);
                if (publishedFacts.size() >= MAX_CACHED_COURSES) {
                    java.time.Instant now = java.time.Instant.now();
                    publishedFacts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
                    if (publishedFacts.size() >= MAX_CACHED_COURSES) {
                        publishedFacts.keySet().stream().findFirst().ifPresent(publishedFacts::remove);
                    }
                }
                publishedFacts.put(courseId, new CachedFact(fact,
                        java.time.Instant.now().plus(factCacheTtl)));
                return fact;
            }
        }
        return fetchPublished(courseId, traceId);
    }

    private CourseFact fetchPublished(String courseId, String traceId) {
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
                // 教师统计每次必须确认当下发布身份；短 TTL 学生读取缓存不能掩盖 Course 故障。
                fact = fetchPublished(courseId, traceId);
            } catch (QuestionException missing) {
                if (missing.status() == HttpStatus.NOT_FOUND) return null;
                throw missing;
            }
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
    private record CachedFact(CourseFact value, java.time.Instant expiresAt) {}
}
