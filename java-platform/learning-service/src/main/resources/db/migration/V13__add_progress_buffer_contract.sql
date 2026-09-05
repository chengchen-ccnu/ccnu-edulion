-- T12G.1 只向前扩展进度事实：Redis 保存高频心跳，MySQL 保存可恢复聚合和强制检查点幂等记录。
-- buffer_version 使 Worker 在 MySQL 提交成功后重试时幂等；event_type 只标记持久化检查点，旧事件允许为空。
ALTER TABLE lesson_progress
    ADD COLUMN buffer_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE lesson_progress
    ADD CONSTRAINT chk_lesson_progress_buffer_version CHECK (buffer_version >= 0);

ALTER TABLE lesson_progress_event
    ADD COLUMN event_type VARCHAR(20) NULL;
ALTER TABLE lesson_progress_event
    ADD CONSTRAINT chk_progress_event_type CHECK (
        event_type IS NULL OR event_type IN ('HEARTBEAT', 'PAUSE', 'LESSON_SWITCH', 'LEAVE', 'ENDED')
    );
