-- T12F.1 Media V5：仅新上传 LESSON_VIDEO 写入处理契约版本，历史视频保持 NULL 且不触发批处理。
ALTER TABLE media_asset
    ADD COLUMN processing_contract_version VARCHAR(40) NULL AFTER processing_status;
