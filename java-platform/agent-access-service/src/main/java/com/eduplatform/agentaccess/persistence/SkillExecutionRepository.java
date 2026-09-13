package com.eduplatform.agentaccess.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只持久化 Agent Access 自有的 Skill 执行审计；不保存指令正文、脚本输入输出或绝对路径。
 */
@Repository
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class SkillExecutionRepository {
    private final JdbcTemplate jdbc;

    public SkillExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID executionId, UUID runId, int sequence, String skillName, String skillVersion,
            String selectionSource, String status, Instant startedAt, Instant completedAt, Long durationMs,
            String referencePathsJson, String scriptPath, String failureCode) {}

    /** 按不可伪造 executionId 读取当前状态；不存在时返回空，不按 run 或 Skill 名模糊匹配。 */
    public Optional<Row> find(UUID executionId) {
        return jdbc.query("SELECT * FROM ai_skill_execution WHERE execution_id=?", this::row,
                executionId.toString()).stream().findFirst();
    }

    /** 插入 RUNNING 事实；run/sequence 唯一约束阻止一次子请求登记两个 Skill。 */
    public void insert(Row row) {
        jdbc.update("""
                INSERT INTO ai_skill_execution(execution_id,run_id,sequence_no,skill_name,skill_version,
                    selection_source,status,started_at,reference_paths_json)
                VALUES(?,?,?,?,?,?,'RUNNING',?,'[]')
                """, row.executionId().toString(), row.runId().toString(), row.sequence(), row.skillName(),
                row.skillVersion(), row.selectionSource(), Timestamp.from(row.startedAt()));
    }

    /** 仅允许从 RUNNING 前进到一个终态；并发完成和 run 收口不会相互覆盖。 */
    public boolean finish(UUID executionId, UUID runId, String status, Instant completedAt, long durationMs,
            String referencesJson, String scriptPath, String failureCode) {
        return jdbc.update("""
                UPDATE ai_skill_execution SET status=?,completed_at=?,duration_ms=?,reference_paths_json=?,
                    script_path=?,failure_code=? WHERE execution_id=? AND run_id=? AND status='RUNNING'
                """, status, Timestamp.from(completedAt), durationMs, referencesJson, scriptPath, failureCode,
                executionId.toString(), runId.toString()) == 1;
    }

    private Row row(ResultSet rs, int ignored) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        long duration = rs.getLong("duration_ms");
        boolean durationWasNull = rs.wasNull();
        return new Row(UUID.fromString(rs.getString("execution_id")), UUID.fromString(rs.getString("run_id")),
                rs.getInt("sequence_no"), rs.getString("skill_name"), rs.getString("skill_version"),
                rs.getString("selection_source"), rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                completed == null ? null : completed.toInstant(), durationWasNull ? null : duration,
                rs.getString("reference_paths_json"), rs.getString("script_path"), rs.getString("failure_code"));
    }
}
