package com.eduplatform.learning.service;

import com.eduplatform.learning.web.AgentAccessDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 Learning 自有表读取当前 owner 的四类安全事实。提交只认 participant.final_attempt_id，成绩只认
 * grade_publication_status=PUBLISHED；任何查询都不读取 assessment_question.snapshot_json，避免答案/解析泄漏。
 */
@Service
@ConditionalOnProperty(prefix = "platform.learning.agent-access", name = "enabled", havingValue = "true")
public class AgentAccessFactService {
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|signature|accesskey|authorization|x-oss-[a-z-]+)\\s*"
                    + "(?:[=:]\\s*|\\s+)(?:bearer\\s+)?[^\\s,;]+");
    private final JdbcTemplate jdbc;

    public AgentAccessFactService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public AgentAccessDtos.QueryResponse query(AgentAccessDtos.QueryRequest request) {
        List<AgentAccessDtos.SafeFact> facts = switch (request.toolName()) {
            case STUDENT_WRONG_QUESTIONS -> wrongQuestions(request);
            case STUDENT_SUBMISSIONS -> submissions(request);
            case STUDENT_PUBLISHED_GRADES -> publishedGrades(request);
            case STUDENT_PROGRESS -> progress(request);
        };
        return new AgentAccessDtos.QueryResponse(facts);
    }

    private List<AgentAccessDtos.SafeFact> wrongQuestions(AgentAccessDtos.QueryRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,course_id,assessment_id,question_version_id,wrong_count,mastery_status,revision,last_wrong_at
                FROM wrong_question WHERE learner_id=? ORDER BY last_wrong_at DESC,id LIMIT ?
                """, request.ownerUserId().toString(), 100);
        return select(rows, request, "错题", row -> "课程 " + row.get("course_id") + "，题目版本 "
                + row.get("question_version_id") + "，错误次数 " + row.get("wrong_count")
                + "，掌握状态 " + row.get("mastery_status"));
    }

    private List<AgentAccessDtos.SafeFact> submissions(AgentAccessDtos.QueryRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id,a.title,t.status,t.attempt_sequence,t.submitted_at,t.revision
                FROM assessment_participant p JOIN assessment_attempt t ON t.id=p.final_attempt_id
                JOIN assessment a ON a.id=p.assessment_id
                WHERE p.learner_id=? AND p.final_attempt_id=t.id
                ORDER BY t.updated_at DESC,t.id LIMIT ?
                """, request.ownerUserId().toString(), 100);
        return select(rows, request, "最终提交", row -> {
            List<String> answers = jdbc.query("SELECT answer_json FROM assessment_answer WHERE attempt_id=? "
                            + "ORDER BY assessment_question_id LIMIT 100",
                    (rs, ignored) -> sanitize(rs.getString(1)), row.get("id"));
            String joined = answers.stream().collect(Collectors.joining("；"));
            return "测评 " + row.get("title") + "，最终有效尝试序号 " + row.get("attempt_sequence")
                    + "，状态 " + row.get("status") + (joined.isBlank() ? "" : "，学生提交：" + joined);
        });
    }

    private List<AgentAccessDtos.SafeFact> publishedGrades(AgentAccessDtos.QueryRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id,a.title,t.score,t.total_points,t.revision,t.grade_updated_at
                FROM assessment_participant p JOIN assessment_attempt t ON t.id=p.final_attempt_id
                JOIN assessment a ON a.id=p.assessment_id
                WHERE p.learner_id=? AND p.final_attempt_id=t.id
                  AND a.grade_publication_status='PUBLISHED'
                ORDER BY t.grade_updated_at DESC,t.id LIMIT ?
                """, request.ownerUserId().toString(), 100);
        return select(rows, request, "已公布成绩", row -> "测评 " + row.get("title") + "，成绩 "
                + row.get("score") + "/" + row.get("total_points"));
    }

    /** Agent 学习事实也只暴露 Course 当前修订；投影缺失时宁可暂不返回，也不混入历史观看事实。 */
    private List<AgentAccessDtos.SafeFact> progress(AgentAccessDtos.QueryRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id,p.course_id,c.lesson_id,p.position_seconds,p.watched_seconds,p.completed,p.revision
                FROM lesson_progress p
                JOIN published_lesson_content_projection c
                  ON c.course_id=p.course_id AND c.content_revision_id=p.content_revision_id
                WHERE p.learner_id=? ORDER BY p.updated_at DESC,p.id LIMIT ?
                """, request.ownerUserId().toString(), 100);
        return select(rows, request, "学习进度", row -> "课程 " + row.get("course_id") + "，课时 "
                + row.get("lesson_id") + "，已观看 " + row.get("watched_seconds") + " 秒，完成="
                + row.get("completed"));
    }

    private List<AgentAccessDtos.SafeFact> select(List<Map<String, Object>> rows,
            AgentAccessDtos.QueryRequest request, String title,
            java.util.function.Function<Map<String, Object>, String> summary) {
        Set<UUID> requested = request.resourceIds() == null ? Set.of() : Set.copyOf(request.resourceIds());
        List<AgentAccessDtos.SafeFact> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID id = UUID.fromString(row.get("id").toString());
            if (!requested.isEmpty() && !requested.contains(id)) continue;
            long revision = row.get("revision") instanceof Number number ? number.longValue() : 0L;
            result.add(new AgentAccessDtos.SafeFact(id, title, sanitize(summary.apply(row)), revision));
            if (result.size() >= request.limit()) break;
        }
        return List.copyOf(result);
    }

    private static String sanitize(String value) {
        String safe = URL.matcher(value == null ? "" : value).replaceAll("[已移除地址]");
        safe = SECRET.matcher(safe).replaceAll("[已移除秘密]");
        return safe.substring(0, Math.min(4000, safe.length()));
    }
}
