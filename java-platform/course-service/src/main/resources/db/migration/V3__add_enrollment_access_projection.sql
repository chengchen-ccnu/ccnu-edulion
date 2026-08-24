-- T11 course-service 本地报名访问投影。只消费 learning-service 的版本化事件，不跨 Schema 查询或建外键。
CREATE TABLE course_enrollment_access (
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    enrollment_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (learner_id, course_id),
    CONSTRAINT chk_course_enrollment_status CHECK (status IN ('ACTIVE', 'CANCELED'))
);
CREATE INDEX idx_course_enrollment_course_status ON course_enrollment_access(course_id, status);

CREATE TABLE consumed_learning_event (
    event_id CHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL
);
