package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.web.AgentDtos;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * 经 Learning/Course HTTP API 复核题目、课程和图片权限。
 *
 * <p>依赖失败会终止创建，不会把浏览器 ID 或公共检索结果提升为授权事实；短期图片 URL 仅存在于
 * 返回给运行时的内存对象中，稳定上下文不含 URL。</p>
 */
@Component
public class HttpAuthorizedContextResolver implements AuthorizedContextResolver {
    private static final Set<String> IMAGE_MIME = Set.of("image/jpeg", "image/png", "image/webp");
    private final RestClient learning;
    private final RestClient course;

    public HttpAuthorizedContextResolver(AgentProperties properties) {
        this.learning = client(properties.learningBaseUrl(), properties);
        this.course = client(properties.courseBaseUrl(), properties);
    }

    @Override
    public AgentDtos.AuthorizedContext resolve(UUID userId, AgentDtos.EntryPoint entryPoint,
            AgentDtos.MessageContextRequest input, String traceId) {
        AgentDtos.MessageContextRequest context = input == null
                ? new AgentDtos.MessageContextRequest(null, null, null, List.of()) : input;
        if ((context.courseId() == null) != (context.lessonId() == null)) {
            throw invalid("context", "courseId 与 lessonId 必须同时提供");
        }
        if (entryPoint == AgentDtos.EntryPoint.PRACTICE_FLOATING && context.questionId() == null) {
            throw invalid("context.questionId", "普通练习悬浮入口必须提供当前题目");
        }
        Question question = context.questionId() == null ? null : question(userId, context.questionId(), traceId);
        CourseLesson lesson = context.courseId() == null ? null
                : lesson(userId, context.courseId(), context.lessonId(), traceId);
        List<UUID> selectedMedia = context.questionMediaIds() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(context.questionMediaIds()));
        if (question == null && !selectedMedia.isEmpty()) {
            throw invalid("context.questionMediaIds", "题目图片必须与 questionId 一起提供");
        }
        List<MediaGrant> media = authorizeMedia(userId, question, selectedMedia, traceId);

        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("userId", userId.toString());
        trusted.put("educationStage", "JUNIOR");
        if (question != null) trusted.put("grade", trustedGrade(question.grade()));
        trusted.put("entryPoint", entryPoint.name());
        trusted.put("authorizedAt", Instant.now().toString());
        if (question != null) trusted.put("question", authorizedQuestion(question));
        if (lesson != null) trusted.put("course", authorizedCourse(lesson));
        if (!media.isEmpty()) trusted.put("media", media.stream().map(this::authorizedMedia).toList());
        trusted.put("toolGrants", toolGrants(question, lesson, media));

        AgentDtos.MessageContext stable = new AgentDtos.MessageContext(
                question == null ? null : UUID.fromString(question.id()),
                question == null ? null : UUID.fromString(question.versionId()),
                lesson == null ? null : UUID.fromString(lesson.courseId()),
                lesson == null ? null : lesson.courseVersionId(),
                lesson == null ? null : UUID.fromString(lesson.lessonId()), selectedMedia);
        return new AgentDtos.AuthorizedContext(stable, trusted);
    }

    private Question question(UUID userId, UUID questionId, String traceId) {
        try {
            Question value = learning.get().uri("/api/v1/learning/system-questions/{id}", questionId)
                    .headers(headers -> identity(headers, userId, traceId)).retrieve().body(Question.class);
            if (value == null || !questionId.toString().equals(value.id()) || value.versionId() == null
                    || !"SYSTEM".equals(value.scope()) || !"JUNIOR".equals(value.stage())) throw unavailable();
            return value;
        } catch (RestClientResponseException failure) {
            throw mapDependency(failure, "题目不存在或不可见");
        } catch (AgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private CourseLesson lesson(UUID userId, UUID courseId, UUID lessonId, String traceId) {
        try {
            CourseLesson value = course.get().uri("/api/v1/courses/{courseId}/lessons/{lessonId}/content",
                            courseId, lessonId).headers(headers -> identity(headers, userId, traceId))
                    .retrieve().body(CourseLesson.class);
            if (value == null || !courseId.toString().equals(value.courseId())
                    || !lessonId.toString().equals(value.lessonId()) || value.courseVersionId() == null) {
                throw unavailable();
            }
            return value;
        } catch (RestClientResponseException failure) {
            throw mapDependency(failure, "课程或课时不存在或不可见");
        } catch (AgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private List<MediaGrant> authorizeMedia(UUID userId, Question question, List<UUID> selected, String traceId) {
        if (selected.isEmpty()) return List.of();
        Set<String> bound = new LinkedHashSet<>();
        question.media().stream().filter(item -> "QUESTION_IMAGE".equals(item.purpose()))
                .forEach(item -> bound.add(item.mediaId()));
        question.children().forEach(child -> child.media().stream()
                .filter(item -> "QUESTION_IMAGE".equals(item.purpose()))
                .forEach(item -> bound.add(item.mediaId())));
        List<MediaGrant> grants = new ArrayList<>();
        for (UUID mediaId : selected) {
            if (!bound.contains(mediaId.toString())) {
                throw new AgentException(HttpStatus.NOT_FOUND, "AGENT.CONTEXT_NOT_FOUND", "题目图片不存在或不可见");
            }
            try {
                MediaGrant grant = learning.post()
                        .uri("/api/v1/learning/system-questions/{questionId}/media/{mediaId}/access-grants",
                                question.id(), mediaId)
                        .headers(headers -> identity(headers, userId, traceId))
                        .body(Map.of("variant", "ORIGINAL", "disposition", "INLINE"))
                        .retrieve().body(MediaGrant.class);
                if (grant == null || !mediaId.toString().equals(grant.mediaId())
                        || !IMAGE_MIME.contains(grant.mimeType()) || grant.sizeBytes() < 1
                        || grant.sizeBytes() > 10_485_760 || grant.url() == null || grant.expiresAt() == null) {
                    throw unavailable();
                }
                grants.add(grant);
            } catch (RestClientResponseException failure) {
                throw mapDependency(failure, "题目图片不存在或不可见");
            }
        }
        return List.copyOf(grants);
    }

    private Map<String, Object> authorizedQuestion(Question question) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("questionType", question.type());
        projection.put("stem", question.stem());
        projection.put("options", optionTexts(question.options()));
        if (question.children() != null && !question.children().isEmpty()) {
            projection.put("children", question.children().stream().map(child -> Map.of(
                    "questionVersionId", child.versionId(), "questionType", child.type(), "stem", child.stem(),
                    "options", optionTexts(child.options()))).toList());
        }
        return Map.of("questionId", question.id(), "questionVersionId", question.versionId(),
                "scope", "ACTIVE_SYSTEM", "safeProjection", projection);
    }

    private static Map<String, Object> authorizedCourse(CourseLesson lesson) {
        return Map.of("courseId", lesson.courseId(), "courseVersionId", lesson.courseVersionId(),
                "lessonId", lesson.lessonId(), "accessDecision", "AUTHORIZED_BY_JAVA",
                "textResourceRefs", List.of(lesson.lessonId()));
    }

    private Map<String, Object> authorizedMedia(MediaGrant grant) {
        return Map.of("mediaId", grant.mediaId(), "purpose", "QUESTION_IMAGE", "mimeType", grant.mimeType(),
                "sizeBytes", grant.sizeBytes(), "accessDecision", "AUTHORIZED_BY_JAVA",
                "temporaryAccess", Map.of("url", grant.url(), "expiresAt", grant.expiresAt().toString(),
                        "confidentiality", "SHORT_LIVED_SECRET"));
    }

    private static List<Map<String, Object>> toolGrants(Question question, CourseLesson lesson, List<MediaGrant> media) {
        List<Map<String, Object>> grants = new ArrayList<>();
        Map<String, Object> publicScope = Map.of("scopeType", "PUBLIC_JUNIOR_MATH");
        grants.add(grant("QUESTION_SEARCH", publicScope));
        grants.add(grant("KNOWLEDGE_SEARCH", publicScope));
        grants.add(grant("PREREQUISITE_GRAPH", publicScope));
        Map<String, Object> selfScope = Map.of("scopeType", "USER_SELF");
        grants.add(grant("STUDENT_WRONG_QUESTIONS", selfScope));
        grants.add(grant("STUDENT_SUBMISSIONS", selfScope));
        grants.add(grant("STUDENT_PUBLISHED_GRADES", selfScope));
        grants.add(grant("STUDENT_PROGRESS", selfScope));
        if (question != null) grants.add(grant("QUESTION_SEARCH",
                Map.of("scopeType", "QUESTION_VERSION", "resourceIds", List.of(question.versionId()))));
        if (lesson != null) grants.add(grant("COURSE_TEXT_SEARCH",
                Map.of("scopeType", "COURSE", "resourceIds", List.of(lesson.courseId()))));
        if (!media.isEmpty()) grants.add(grant("AUTHORIZED_IMAGE_UNDERSTANDING",
                Map.of("scopeType", "MEDIA", "resourceIds", media.stream().map(MediaGrant::mediaId).toList())));
        return List.copyOf(grants);
    }

    private static Map<String, Object> grant(String name, Map<String, Object> scope) {
        return Map.of("toolName", name, "resourceScopes", List.of(scope));
    }

    private static List<String> optionTexts(List<Option> options) {
        if (options == null) return List.of();
        return options.stream().map(option -> option.key() + ". " + option.content()).toList();
    }

    private static String trustedGrade(String grade) {
        return switch (grade) {
            case "SEVEN" -> "GRADE_7";
            case "EIGHT" -> "GRADE_8";
            case "NINE" -> "GRADE_9";
            default -> throw unavailable();
        };
    }

    private static RestClient client(String baseUrl, AgentProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.domainRequestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.domainRequestTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static void identity(org.springframework.http.HttpHeaders headers, UUID userId, String traceId) {
        headers.set("X-User-Id", userId.toString());
        headers.set("X-User-Roles", "STUDENT");
        headers.set("X-User-Permissions", "learning:access");
        headers.set("X-Trace-Id", traceId);
    }

    private static AgentException mapDependency(RestClientResponseException failure, String message) {
        if (failure.getStatusCode().value() == 403 || failure.getStatusCode().value() == 404) {
            return new AgentException(HttpStatus.NOT_FOUND, "AGENT.CONTEXT_NOT_FOUND", message);
        }
        if (failure.getStatusCode().value() == 422) return invalid("context", "上下文参数不合法");
        return unavailable();
    }

    private static AgentException invalid(String field, String reason) {
        return new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "COMMON.VALIDATION_FAILED", "请求参数不合法",
                List.of(new com.eduplatform.shared.api.FieldError(field, reason)));
    }

    private static AgentException unavailable() {
        return new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT.DEPENDENCY_UNAVAILABLE",
                "AI 服务暂时不可用，请稍后重试");
    }

    private record Option(String key, String content) {}
    private record Media(String mediaId, String purpose) {}
    private record Child(String questionId, String versionId, int sequence, String type, String difficulty,
            String stage, String grade, String subject, String stem, List<Option> options,
            List<JsonNode> knowledgePoints, List<Media> media, Instant createdAt) {}
    private record Question(String id, String versionId, String scope, String type, String difficulty,
            String stage, String grade, String subject, String stem, List<Option> options,
            List<JsonNode> knowledgePoints, List<Media> media, List<Child> children,
            Instant createdAt, Instant updatedAt) {}
    private record CourseLesson(String courseId, String courseVersionId, String lessonId, String title,
            String type, boolean previewable, String articleMarkdown, String playbackResourceId,
            JsonNode poster, List<JsonNode> inlineImages, List<JsonNode> resources) {}
    private record MediaGrant(String mediaId, String mimeType, long sizeBytes, String method, String url,
            Map<String, String> headers, Instant expiresAt) {}
}
