package com.eduplatform.iam.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** IAM到Course/Learning的有界内部调用；只使用版本化协议，不跨服务查表，失败不返回假零影响。 */
@Component
public class AccountCourseClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final String courseUrl;
    private final String learningUrl;
    private final String secret;
    private final Duration timeout;
    public AccountCourseClient(ObjectMapper json,
            @Value("${platform.iam.account-permissions.course-url:http://127.0.0.1:8082}") String courseUrl,
            @Value("${platform.iam.account-permissions.learning-url:http://127.0.0.1:8084}") String learningUrl,
            @Value("${platform.iam.account-permissions.service-token:}") String secret,
            @Value("${platform.iam.account-permissions.request-timeout:3s}") Duration timeout) {
        this.json = json; this.courseUrl = courseUrl; this.learningUrl = learningUrl; this.secret = secret;
        this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("账号联动超时必须在0至10秒内");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 读取排序课程范围及去重学生人数；依赖认证/超时/无效响应统一503，不记录内部响应或秘密。 */
    public Impact impact(String userId, boolean paused, String suspensionId) {
        String path = "/internal/v1/teachers/" + UUID.fromString(userId) + "/availability-impact?paused=" + paused
                + (suspensionId == null ? "" : "&suspensionId=" + UUID.fromString(suspensionId));
        CourseImpact courses = call(courseUrl + path, "GET", null, CourseImpact.class, null);
        if (courses == null || courses.courseIds() == null || courses.courseIds().size() > 1000) unavailable();
        List<String> ids;
        try { ids = courses.courseIds().stream().map(UUID::fromString).map(UUID::toString).sorted().distinct().toList(); }
        catch (RuntimeException malformed) { unavailable(); return null; }
        if (ids.size() != courses.courseIds().size()) unavailable();
        StudentCount count = call(learningUrl + "/internal/v1/account-impact/active-students:count", "POST",
                new CourseImpact(ids), StudentCount.class, null);
        if (count == null || count.activeStudents() == null || count.activeStudents() < 0) unavailable();
        return new Impact(ids, count.activeStudents());
    }

    /** 重放同一持久化暂停周期及能力水位；返回Course提交事实，超时可能已提交，调用方仍按原ID有限补偿。 */
    public Applied apply(String userId, String suspensionId, long revision, boolean paused, String trace) {
        Applied value = call(courseUrl + "/internal/v1/teachers/" + UUID.fromString(userId) + "/course-availability",
                "PUT", new AvailabilityCommand(suspensionId, revision, paused), Applied.class, trace);
        if (value == null || !userId.equals(value.teacherId()) || !suspensionId.equals(value.suspensionId())
                || revision != value.accessRevision() || value.paused() == null || paused != value.paused()
                || value.affectedCourses() == null || value.affectedCourses() < 0) unavailable();
        return value;
    }

    /** 内部调用使用固定caller和独立秘密；没有配置时安全拒绝，响应正文不带入异常日志。 */
    private <T> T call(String url, String method, Object body, Class<T> type, String trace) {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) unavailable();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("X-Internal-Caller", "iam-service").header("X-Internal-Service-Token", secret);
            if (trace != null) request.header("X-Trace-Id", trace);
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 128_000) unavailable();
            return json.readValue(response.body(), type);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); unavailable(); return null;
        } catch (Exception failure) { unavailable(); return null; }
    }
    private static void unavailable() { throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
            "IAM.COORDINATION_DEPENDENCY_UNAVAILABLE", "课程或学习影响服务暂时不可用，请稍后重试", 2L); }
    public record Impact(List<String> courseIds, long activeStudents) {}
    private record CourseImpact(List<String> courseIds) {}
    private record StudentCount(Long activeStudents) {}
    private record AvailabilityCommand(String suspensionId, long accessRevision, boolean paused) {}
    public record Applied(String teacherId, String suspensionId, long accessRevision, Boolean paused, Integer affectedCourses) {}
}
