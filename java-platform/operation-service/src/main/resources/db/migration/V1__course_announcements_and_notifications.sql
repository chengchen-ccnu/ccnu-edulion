-- Operation首个业务迁移；仅本域事实，外域稳定ID不建跨服务外键，不设统一消息TTL。
CREATE TABLE operation_actor_lock (actor_id CHAR(36) PRIMARY KEY);
CREATE TABLE course_announcement (
 id CHAR(36) PRIMARY KEY, course_id CHAR(36) NOT NULL, revision BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, title VARCHAR(120) NOT NULL, body TEXT NOT NULL,
 published_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 KEY idx_announcement_course(course_id,status,updated_at,id),
 CONSTRAINT chk_announcement_status CHECK(status IN ('ACTIVE','WITHDRAWN'))
);
-- 不可变审计版本只供owner读取，正文不进入消息副本或领域事件。
CREATE TABLE course_announcement_revision (
 announcement_id CHAR(36) NOT NULL, revision BIGINT NOT NULL, actor_id CHAR(36) NOT NULL,
 status VARCHAR(16) NOT NULL, title VARCHAR(120) NOT NULL, body TEXT NOT NULL, changed_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(announcement_id,revision),
 CONSTRAINT fk_announcement_revision FOREIGN KEY(announcement_id) REFERENCES course_announcement(id)
);
-- 确认独立于消息；必须显式确认精确revision，旧请求不自动推进新版。
CREATE TABLE course_announcement_confirmation (
 announcement_id CHAR(36) NOT NULL, learner_id CHAR(36) NOT NULL, revision BIGINT NOT NULL, confirmed_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(announcement_id,learner_id),
 CONSTRAINT fk_announcement_confirmation FOREIGN KEY(announcement_id) REFERENCES course_announcement(id)
);
CREATE TABLE operation_command (
 actor_id CHAR(36) NOT NULL, request_id CHAR(36) NOT NULL, request_json TEXT NOT NULL, result_json TEXT NOT NULL,
 PRIMARY KEY(actor_id,request_id)
);
CREATE TABLE notification_message (
 id CHAR(36) PRIMARY KEY, recipient_id CHAR(36) NOT NULL, source_key VARCHAR(100) NOT NULL,
 kind VARCHAR(32) NOT NULL, action VARCHAR(24) NOT NULL, title VARCHAR(240) NOT NULL,
 target_id CHAR(36) NOT NULL, course_id CHAR(36) NULL, revision BIGINT NOT NULL,
 unread BOOLEAN NOT NULL DEFAULT TRUE, occurred_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_notification_source(recipient_id,source_key),
 KEY idx_notification_recipient(recipient_id,occurred_at,id),
 CONSTRAINT chk_notification_kind CHECK(kind IN ('COURSE_ANNOUNCEMENT','COURSE_TASK','TEACHER_REVIEW','COURSE_AVAILABILITY'))
);
-- 公告事务Outbox；mandatory/confirm有限20次，FAILED留痕不自动重放。
CREATE TABLE operation_outbox_event (
 event_id CHAR(36) PRIMARY KEY, aggregate_id CHAR(36) NOT NULL, payload_json TEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP(6) NOT NULL, published_at TIMESTAMP(6) NULL, last_error VARCHAR(120) NULL,
 KEY idx_operation_outbox_due(status,next_attempt_at,event_id),
 CONSTRAINT chk_operation_outbox_status CHECK(status IN ('PENDING','PUBLISHED','FAILED'))
);
-- 原信封用于同eventId异载荷冲突检测；不引入hash。
CREATE TABLE operation_consumed_event (
 event_id CHAR(36) PRIMARY KEY, payload_json TEXT NOT NULL, consumed_at TIMESTAMP(6) NOT NULL
);
-- 每次最多100人；游标与消息同事务，行锁跨实例互斥，失败有限退避，重启恢复PENDING。
CREATE TABLE notification_delivery_work (
 event_id CHAR(36) PRIMARY KEY, kind VARCHAR(32) NOT NULL, action VARCHAR(24) NOT NULL,
 target_id CHAR(36) NOT NULL, course_id CHAR(36) NULL, recipient_id CHAR(36) NULL,
 revision BIGINT NOT NULL, triggered_at TIMESTAMP(6) NOT NULL, cursor_id VARCHAR(36) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP(6) NOT NULL, last_error VARCHAR(120) NULL,
 KEY idx_delivery_due(status,next_attempt_at,event_id),
 CONSTRAINT chk_delivery_status CHECK(status IN ('PENDING','COMPLETE','SUPPRESSED','FAILED'))
);
