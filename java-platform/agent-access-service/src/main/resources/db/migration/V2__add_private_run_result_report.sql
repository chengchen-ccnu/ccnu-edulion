-- T14.2.1 只暂存 Python 私有运行结果报告，T14.2.2 再按额度/审计/记忆领域表规范化。
-- 该 JSON 永不进入浏览器消息或 SSE；report_id 唯一约束与正文精确比较支持严格幂等冲突检测。
ALTER TABLE ai_run ADD COLUMN result_report_id CHAR(36) NULL;
ALTER TABLE ai_run ADD COLUMN result_report_json LONGTEXT NULL;
ALTER TABLE ai_run ADD COLUMN result_reported_at TIMESTAMP(6) NULL;
ALTER TABLE ai_run ADD CONSTRAINT uk_ai_run_result_report_id UNIQUE (result_report_id);
