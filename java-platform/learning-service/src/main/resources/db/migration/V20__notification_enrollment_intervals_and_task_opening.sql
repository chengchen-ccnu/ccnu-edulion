-- T16.4 报名半开有效区间用于触发时收件人；只保存本域事实，不跨Schema外键。
CREATE TABLE notification_enrollment_interval (
    id CHAR(36) PRIMARY KEY,
    enrollment_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NULL,
    KEY idx_notification_recipients (course_id, learner_id, starts_at, ends_at),
    CONSTRAINT fk_notification_interval_enrollment FOREIGN KEY(enrollment_id) REFERENCES enrollment(id) ON DELETE CASCADE
);
-- 旧记录仅能恢复最后一次区间，不伪造更早历史，不用于重放旧事件。
INSERT INTO notification_enrollment_interval(id,enrollment_id,course_id,learner_id,starts_at,ends_at)
SELECT UUID(),id,course_id,learner_id,enrolled_at,canceled_at FROM enrollment;

-- 真正开放持久工作；PENDING可重启恢复，五次依赖失败后FAILED须运维明确补偿。
CREATE TABLE assessment_notification_opening (
    assessment_id CHAR(36) PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    opened_at TIMESTAMP(6) NULL,
    last_error VARCHAR(120) NULL,
    KEY idx_notification_opening_due(status,next_attempt_at),
    CONSTRAINT fk_notification_opening_assessment FOREIGN KEY(assessment_id) REFERENCES assessment(id) ON DELETE CASCADE,
    CONSTRAINT chk_notification_opening_status CHECK(status IN ('PENDING','OPENED','SUPPRESSED','FAILED'))
);
-- 不给旧已开放任务补提醒；原未来任务在真实开放时间进入新的触发链。
INSERT INTO assessment_notification_opening(assessment_id,status,next_attempt_at)
SELECT id,CASE WHEN COALESCE(opens_at,published_at)<=CURRENT_TIMESTAMP THEN 'SUPPRESSED' ELSE 'PENDING' END,
       COALESCE(opens_at,published_at)
FROM assessment WHERE status='PUBLISHED' AND assessment_type IN ('HOMEWORK','EXAM') AND course_id IS NOT NULL;
