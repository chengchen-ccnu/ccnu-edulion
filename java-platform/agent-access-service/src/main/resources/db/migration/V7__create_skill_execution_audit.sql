-- T13.5.1：保存标准 Skill 的私有执行身份与安全状态；正文、输入输出、提示词和绝对路径禁止入表。
CREATE TABLE ai_skill_execution (
    execution_id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    skill_name VARCHAR(80) NOT NULL,
    skill_version VARCHAR(32) NOT NULL,
    selection_source VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    duration_ms BIGINT,
    reference_paths_json VARCHAR(2000) NOT NULL DEFAULT '[]',
    script_path VARCHAR(240),
    failure_code VARCHAR(80),
    CONSTRAINT fk_ai_skill_execution_run FOREIGN KEY (run_id) REFERENCES ai_run(id),
    CONSTRAINT uk_ai_skill_execution_run_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT chk_ai_skill_execution_source CHECK (selection_source IN ('EXPLICIT','RULE','LLM')),
    CONSTRAINT chk_ai_skill_execution_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','ABANDONED')),
    CONSTRAINT chk_ai_skill_execution_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);
CREATE INDEX idx_ai_skill_execution_running ON ai_skill_execution(status, started_at, execution_id);
