-- T14.3 向前增加本人自主练习归属。旧 COURSE 行保持非空教师/课程字段；AUTONOMOUS 行只绑定全局 learnerUserId。
ALTER TABLE assessment MODIFY owner_teacher_id CHAR(36) NULL;
ALTER TABLE assessment MODIFY course_id CHAR(36) NULL;
ALTER TABLE assessment ADD COLUMN ownership_type VARCHAR(20) NOT NULL DEFAULT 'COURSE';
ALTER TABLE assessment ADD CONSTRAINT chk_assessment_ownership CHECK (
    (ownership_type = 'COURSE' AND owner_teacher_id IS NOT NULL AND course_id IS NOT NULL)
    OR (ownership_type = 'AUTONOMOUS' AND owner_teacher_id IS NULL AND course_id IS NULL AND lesson_id IS NULL
        AND assessment_type = 'PRACTICE' AND time_limit_seconds IS NULL)
);
CREATE INDEX idx_assessment_ownership ON assessment(ownership_type, status, published_at);

-- source_action_id 是跨服务稳定业务幂等键；一个确认动作最多生成一个 assessment。
CREATE TABLE autonomous_practice (
    id CHAR(36) PRIMARY KEY,
    source_action_id CHAR(36) NOT NULL,
    learner_user_id CHAR(36) NOT NULL,
    assessment_id CHAR(36) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    requested_count INT NOT NULL,
    actual_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_autonomous_practice_action UNIQUE (source_action_id),
    CONSTRAINT uk_autonomous_practice_assessment UNIQUE (assessment_id),
    CONSTRAINT fk_autonomous_practice_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT chk_autonomous_practice_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT chk_autonomous_practice_counts CHECK (
        requested_count BETWEEN 1 AND 20 AND actual_count BETWEEN 1 AND requested_count
    )
);
CREATE INDEX idx_autonomous_practice_owner ON autonomous_practice(learner_user_id, created_at, id);
