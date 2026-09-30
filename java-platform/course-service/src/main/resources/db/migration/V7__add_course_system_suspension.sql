-- T16.1：独立保存系统暂停，不改变课程原发布状态、版本、媒体绑定或领域历史。
-- owner 行锁协调暂停命令与发布；区间保留供 Learning 精确、幂等顺延任务时钟。
ALTER TABLE course ADD COLUMN system_suspended BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE course ADD COLUMN availability_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE teacher_course_availability (
    teacher_id CHAR(36) PRIMARY KEY,
    access_revision BIGINT NOT NULL DEFAULT 0,
    suspension_id CHAR(36) NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    affected_courses INT NOT NULL DEFAULT 0
);
CREATE TABLE course_suspension_interval (
    course_id CHAR(36) NOT NULL,
    sequence BIGINT NOT NULL,
    suspension_id CHAR(36) NOT NULL,
    suspended_at TIMESTAMP(6) NOT NULL,
    resumed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (course_id, sequence),
    UNIQUE KEY uk_course_suspension (course_id, suspension_id),
    CONSTRAINT fk_suspension_course FOREIGN KEY (course_id) REFERENCES course(id),
    CONSTRAINT chk_suspension_time CHECK (resumed_at IS NULL OR resumed_at >= suspended_at)
);
