-- T16.1：Learning 本域课程锁、完成区间水位及补时审计；不是第二套测评或进度事实。
-- 全部访问与后台结算按课程锁→assessment→attempt 顺序，恢复补时在同一事务中完成。
CREATE TABLE learning_course_clock (
    course_id CHAR(36) PRIMARY KEY,
    applied_sequence BIGINT NOT NULL DEFAULT 0,
    availability_revision BIGINT NOT NULL DEFAULT 0,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    last_resumed_at TIMESTAMP(6) NULL
);
CREATE TABLE learning_course_clock_interval (
    course_id CHAR(36) NOT NULL,
    sequence BIGINT NOT NULL,
    suspension_id CHAR(36) NOT NULL,
    suspended_at TIMESTAMP(6) NOT NULL,
    resumed_at TIMESTAMP(6) NOT NULL,
    affected_assessments INT NOT NULL,
    PRIMARY KEY (course_id, sequence),
    UNIQUE KEY uk_learning_clock_cycle (course_id, suspension_id)
);
CREATE TABLE course_availability_consumed_event (
    event_id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    availability_revision BIGINT NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL
);
