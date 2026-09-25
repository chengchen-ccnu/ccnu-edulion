-- T12C.1 只保存本次上线后的有效观看增量和首次开始/完成日；既有累计 lesson_progress 继续提供 LIFETIME。
-- 主键按内容修订隔离，旧修订日事实保留供内部审计，但教师查询必须再连接当前发布投影。
-- 同一课时同一自然日只有一行；Redis Worker 重试和强制检查点在 lesson_progress 行锁事务中只写实际新增秒数。
CREATE TABLE lesson_learning_day (
    course_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    content_revision_id CHAR(36) NOT NULL,
    activity_date DATE NOT NULL,
    watched_seconds BIGINT NOT NULL DEFAULT 0,
    first_started BOOLEAN NOT NULL DEFAULT FALSE,
    first_completed BOOLEAN NOT NULL DEFAULT FALSE,
    last_activity_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (course_id, learner_id, content_revision_id, activity_date),
    KEY idx_lesson_learning_day_course_date (course_id, activity_date, content_revision_id, learner_id),
    CONSTRAINT chk_lesson_learning_day_watched CHECK (watched_seconds >= 0)
);

-- 只记录上线后报名、草稿保存和错题重做的成功落库自然日；同日重复行为只标记活跃一次。
CREATE TABLE learning_activity_day (
    course_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    activity_date DATE NOT NULL,
    last_activity_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (course_id, learner_id, activity_date),
    KEY idx_learning_activity_course_date (course_id, activity_date, learner_id)
);

-- 用于严格区分主动正式提交与关闭后自动结算；已有提交只可用于 LIFETIME，绝不假造历史日趋势。
ALTER TABLE assessment_attempt ADD COLUMN auto_settled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE assessment_attempt ADD COLUMN submission_day DATE NULL;
CREATE INDEX idx_attempt_course_trend ON assessment_attempt(assessment_id, submitted_at, learner_id, status);
