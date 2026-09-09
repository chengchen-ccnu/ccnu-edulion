package com.eduplatform.learning.service;

import com.eduplatform.learning.web.AgentIndexDtos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 从 Learning 自有表构造稳定分页索引源。
 *
 * <p>题目查询固定为根级 ACTIVE SYSTEM、JUNIOR、MATHEMATICS，SQL 和 DTO 均不读取答案/解析；
 * 知识点只返回启用的初中数学节点和直接前置边，避免一次导出完整无界图。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.learning.agent-index", name = "enabled", havingValue = "true")
public class AgentIndexProjectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentIndexProjectionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public AgentIndexDtos.QuestionPage questions(long afterRevision, String afterId, String entityId, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                WHERE qa.scope='SYSTEM' AND qa.status='ACTIVE' AND qa.parent_question_id IS NULL
                  AND qv.stage='JUNIOR' AND qv.subject='MATHEMATICS'
                """);
        if (entityId != null) {
            where.append(" AND qa.id=?"); args.add(entityId);
        } else {
            where.append(" AND (COALESCE(qir.revision,qa.revision+1)>? OR (COALESCE(qir.revision,qa.revision+1)=? AND qa.id>?))");
            args.add(afterRevision); args.add(afterRevision); args.add(afterId);
        }
        args.add(size + 1);
        List<AgentIndexDtos.QuestionProjection> rows = jdbc.query("""
                SELECT qa.id entity_id,qa.current_version_id question_version_id,
                       COALESCE(qir.revision,qa.revision+1) source_revision,
                       qv.grade,qv.question_type,qv.stem_md,qv.options_json
                FROM question_aggregate qa JOIN question_version qv ON qv.id=qa.current_version_id
                LEFT JOIN question_index_revision qir ON qir.question_id=qa.id
                """ + where + " ORDER BY COALESCE(qir.revision,qa.revision+1),qa.id LIMIT ?", (rs, ignored) -> {
            String versionId = rs.getString("question_version_id");
            return new AgentIndexDtos.QuestionProjection(rs.getString("entity_id"), versionId,
                    rs.getLong("source_revision"), "JUNIOR", grade(rs.getString("grade")), "MATH",
                    rs.getString("question_type"), rs.getString("stem_md"), options(rs.getString("options_json")),
                    jdbc.query("SELECT knowledge_point_id FROM question_version_knowledge_point WHERE question_version_id=? ORDER BY sequence_no,knowledge_point_id",
                            (points, row) -> points.getString(1), versionId));
        }, args.toArray());
        return questionPage(rows, size);
    }

    public AgentIndexDtos.KnowledgePage knowledge(long afterRevision, String afterId, String entityId, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE enabled=TRUE AND normalized_stage='JUNIOR'");
        if (entityId != null) {
            where.append(" AND id=?"); args.add(entityId);
        } else {
            where.append(" AND ((revision+1)>? OR ((revision+1)=? AND id>?))");
            args.add(afterRevision); args.add(afterRevision); args.add(afterId);
        }
        args.add(size + 1);
        List<AgentIndexDtos.KnowledgeProjection> rows = jdbc.query("SELECT id,code,name,(revision+1) revision FROM knowledge_point"
                + where + " ORDER BY revision,id LIMIT ?", (rs, ignored) -> {
            String id = rs.getString("id");
            return new AgentIndexDtos.KnowledgeProjection(id, rs.getString("code"), rs.getString("name"),
                    rs.getLong("revision"), "JUNIOR", "MATH",
                    jdbc.query("SELECT prerequisite_id FROM knowledge_point_prerequisite WHERE dependent_id=? ORDER BY prerequisite_id LIMIT 32",
                            (edges, row) -> edges.getString(1), id));
        }, args.toArray());
        boolean more = rows.size() > size;
        List<AgentIndexDtos.KnowledgeProjection> items = more ? rows.subList(0, size) : rows;
        AgentIndexDtos.Cursor cursor = items.isEmpty() ? null : new AgentIndexDtos.Cursor(
                items.get(items.size() - 1).sourceRevision(), items.get(items.size() - 1).entityId());
        return new AgentIndexDtos.KnowledgePage(List.copyOf(items), more, cursor);
    }

    private AgentIndexDtos.QuestionPage questionPage(List<AgentIndexDtos.QuestionProjection> rows, int size) {
        boolean more = rows.size() > size;
        List<AgentIndexDtos.QuestionProjection> items = more ? rows.subList(0, size) : rows;
        AgentIndexDtos.Cursor cursor = items.isEmpty() ? null : new AgentIndexDtos.Cursor(
                items.get(items.size() - 1).sourceRevision(), items.get(items.size() - 1).entityId());
        return new AgentIndexDtos.QuestionPage(List.copyOf(items), more, cursor);
    }

    private List<String> options(String json) {
        try {
            List<Option> values = mapper.readValue(json, new TypeReference<List<Option>>() {});
            return values.stream().map(value -> value.key() + ". " + value.content()).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("Stored question options are invalid", failure);
        }
    }

    private static String grade(String value) {
        return switch (value) { case "SEVEN" -> "GRADE_7"; case "EIGHT" -> "GRADE_8";
            case "NINE" -> "GRADE_9"; default -> throw new IllegalStateException("Unsupported junior grade"); };
    }

    private record Option(String key, String content) {}
}
