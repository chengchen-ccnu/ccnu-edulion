package com.eduplatform.learning.web;

import com.eduplatform.learning.service.QuestionException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** IAM 影响预览的内部聚合边界；只读 Learning 报名，不泄露学生名单，不跨服务查表。 */
@RestController
public class AccountImpactController {
    private final JdbcTemplate jdbc;
    private final String secret;
    public AccountImpactController(JdbcTemplate jdbc,
            @Value("${platform.learning.account-impact.iam-token:}") String secret) {
        this.jdbc = jdbc; this.secret = secret;
    }

    /** 输入最多1000个稳定课程ID；校验独立IAM服务凭据，返回去重ACTIVE学生数，数据库错误不转成零。 */
    @PostMapping("/internal/v1/account-impact/active-students:count")
    public Count count(@RequestHeader(value="X-Internal-Caller", defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token", defaultValue="") String token,
            @Valid @RequestBody Request request) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32 || !"iam-service".equals(caller)
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            throw new QuestionException(HttpStatus.UNAUTHORIZED, "LEARNING.INTERNAL_UNAUTHORIZED", "内部调用身份无效");
        List<String> ids = request.courseIds().stream().map(UUID::toString).distinct().toList();
        if (ids.isEmpty()) return new Count(0);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return new Count(jdbc.queryForObject("SELECT COUNT(DISTINCT learner_id) FROM enrollment WHERE status='ACTIVE' AND course_id IN ("
                + placeholders + ")", Long.class, ids.toArray()));
    }
    public record Request(@NotNull @Size(max=1000) List<@NotNull UUID> courseIds) {}
    public record Count(long activeStudents) {}
}
