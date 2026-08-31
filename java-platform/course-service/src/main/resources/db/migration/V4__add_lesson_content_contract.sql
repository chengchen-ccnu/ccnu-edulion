-- T12F.1 Course V4：受控 Markdown 与资源展示元数据属于课程版本事实；媒体对象及 URL 仍由 media-service 拥有。
ALTER TABLE lesson
    ADD COLUMN article_markdown MEDIUMTEXT NULL;

ALTER TABLE lesson_media_resource
    ADD COLUMN display_name VARCHAR(120) NOT NULL DEFAULT '历史资源';
ALTER TABLE lesson_media_resource
    ADD COLUMN presentation VARCHAR(16) NOT NULL DEFAULT 'DOWNLOAD';
ALTER TABLE lesson_media_resource
    ADD COLUMN original_file_name VARCHAR(255) NOT NULL DEFAULT '历史资源';
ALTER TABLE lesson_media_resource
    ADD COLUMN mime_type VARCHAR(127) NOT NULL DEFAULT 'application/octet-stream';
ALTER TABLE lesson_media_resource
    ADD COLUMN size_bytes BIGINT NOT NULL DEFAULT 1;
-- 只保存安全变体名称与处理契约版本，用于发布校验和历史视频兼容；不保存任何存储定位信息。
ALTER TABLE lesson_media_resource
    ADD COLUMN variant_names VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE lesson_media_resource
    ADD COLUMN processing_contract_version VARCHAR(40) NULL;

ALTER TABLE lesson_media_resource
    DROP CONSTRAINT chk_lesson_media_purpose;

ALTER TABLE lesson_media_resource
    ADD CONSTRAINT chk_lesson_media_purpose CHECK (purpose IN ('LESSON_VIDEO', 'VIDEO_POSTER', 'SUBTITLE', 'COURSEWARE', 'ARTICLE_ATTACHMENT', 'ARTICLE_IMAGE'));
ALTER TABLE lesson_media_resource
    ADD CONSTRAINT chk_lesson_media_presentation CHECK (presentation IN ('INLINE', 'DOWNLOAD'));
ALTER TABLE lesson_media_resource
    ADD CONSTRAINT chk_lesson_media_size CHECK (size_bytes > 0);
