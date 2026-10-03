-- 派生发布协调行仅串行化短批次源刷新/Redis发布，不锁核心业务；重建失败保留游标/dirty。
CREATE TABLE hot_course_refresh_state (
    id INTEGER NOT NULL PRIMARY KEY,
    rebuild_after CHAR(36) NOT NULL DEFAULT '',
    last_completed_at TIMESTAMP(6) NULL,
    last_failure_at TIMESTAMP(6) NULL
);
INSERT INTO hot_course_refresh_state(id,rebuild_after) VALUES(1,'');
-- 只保存公开元数据和聚合人数，可完全经领域API重建；不存封面URL、学生或存储键。
CREATE TABLE hot_course_projection (
    course_id CHAR(36) NOT NULL PRIMARY KEY,
    card_json TEXT NOT NULL,
    learners_7d BIGINT NOT NULL,
    active_enrollments BIGINT NOT NULL,
    window_end DATE NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    refreshed_at TIMESTAMP(6) NOT NULL,
    KEY idx_hot_course_rank (window_end,learners_7d,active_enrollments,published_at)
);
-- Inbox与dirty同事务登记；重投不重复刷新，乱序只触发读取最新事实。
CREATE TABLE hot_course_consumed_event (
    event_id CHAR(36) NOT NULL PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE hot_course_dirty (
    course_id CHAR(36) NOT NULL PRIMARY KEY
);
