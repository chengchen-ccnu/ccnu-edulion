-- T11B 只向前增加整场关闭、有限草稿结算和两类独立公布事实；不改写 V9/V10，也不跨 Schema 建表或外键。
-- answer_publication_policy 是 V9 兼容列，受旧 CHECK 约束保护；answer_release_policy 保存向前扩展后的真实策略。

ALTER TABLE assessment ADD COLUMN answer_release_policy VARCHAR(40) NOT NULL DEFAULT 'NEVER';
UPDATE assessment
SET answer_release_policy = CASE answer_publication_policy
    WHEN 'AFTER_SUBMISSION' THEN 'AFTER_SUBMISSION'
    WHEN 'AFTER_GRADED' THEN 'AFTER_ASSESSMENT_GRADED'
    ELSE 'NEVER'
END;

ALTER TABLE assessment ADD COLUMN grade_publication_policy VARCHAR(40) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE assessment ADD COLUMN close_status VARCHAR(20) NOT NULL DEFAULT 'OPEN';
ALTER TABLE assessment ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE assessment ADD COLUMN closed_at TIMESTAMP(6) NULL;
ALTER TABLE assessment ADD COLUMN close_affected_attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE assessment ADD COLUMN settlement_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE assessment ADD COLUMN settlement_next_attempt_at TIMESTAMP(6) NULL;
ALTER TABLE assessment ADD COLUMN settlement_last_error VARCHAR(500) NULL;
ALTER TABLE assessment ADD COLUMN grade_publication_status VARCHAR(20) NOT NULL DEFAULT 'UNPUBLISHED';
ALTER TABLE assessment ADD COLUMN answer_publication_status VARCHAR(20) NOT NULL DEFAULT 'UNPUBLISHED';
ALTER TABLE assessment ADD COLUMN grade_published_at TIMESTAMP(6) NULL;
ALTER TABLE assessment ADD COLUMN answer_published_at TIMESTAMP(6) NULL;

ALTER TABLE assessment
    ADD CONSTRAINT chk_answer_release_policy CHECK (
        answer_release_policy IN ('AFTER_SUBMISSION', 'AFTER_ASSESSMENT_GRADED', 'MANUAL_AFTER_CLOSE', 'NEVER')
    );
ALTER TABLE assessment
    ADD CONSTRAINT chk_grade_publication_policy CHECK (
        grade_publication_policy IN ('AUTOMATIC_AFTER_GRADING', 'MANUAL')
    );
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_close_status CHECK (close_status IN ('OPEN', 'CLOSED'));
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_settlement_status CHECK (
        settlement_status IN ('NOT_STARTED', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    );
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_publication_status CHECK (
        grade_publication_status IN ('UNPUBLISHED', 'PUBLISHED')
        AND answer_publication_status IN ('UNPUBLISHED', 'PUBLISHED')
    );
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_settlement_counts CHECK (
        close_affected_attempt_count >= 0 AND settlement_attempts BETWEEN 0 AND 5
    );

CREATE INDEX idx_assessment_settlement_due
    ON assessment(settlement_status, settlement_next_attempt_at, updated_at);

-- grade_updated_at 是学生已公布成绩的“最后更新时间”；重新批改只推进该时间，不撤回公布事实。
ALTER TABLE assessment_attempt ADD COLUMN grade_updated_at TIMESTAMP(6) NULL;
UPDATE assessment_attempt SET grade_updated_at = graded_at WHERE graded_at IS NOT NULL;
