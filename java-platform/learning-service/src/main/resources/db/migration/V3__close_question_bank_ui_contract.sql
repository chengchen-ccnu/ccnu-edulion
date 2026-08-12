-- T09B 向前扩展：冻结组合题子版本、可恢复历史 JSON 复核项和 revision 幂等解决记录。
-- V1/V2 保持不变；所有内容仍在 learning Schema，媒体只保存稳定 mediaId。

ALTER TABLE question_aggregate ADD COLUMN parent_question_id CHAR(36) NULL;

ALTER TABLE question_version_child ADD COLUMN child_question_version_id CHAR(36) NULL;
UPDATE question_version_child c
SET child_question_version_id = (
    SELECT q.current_version_id FROM question_aggregate q WHERE q.id = c.child_question_id
);
ALTER TABLE question_version_child MODIFY COLUMN child_question_version_id CHAR(36) NOT NULL;
ALTER TABLE question_version_child
    ADD CONSTRAINT fk_question_child_version FOREIGN KEY (child_question_version_id) REFERENCES question_version(id);
CREATE UNIQUE INDEX uk_question_version_child_snapshot
    ON question_version_child(parent_question_version_id, child_question_version_id);

-- V2 组合题子聚合回填父题归属，后续教师列表只展示根题目。
UPDATE question_aggregate q
SET parent_question_id = (
    SELECT MIN(parent_version.question_id)
    FROM question_version_child relation
    JOIN question_version parent_version ON parent_version.id = relation.parent_question_version_id
    WHERE relation.child_question_id = q.id
)
WHERE EXISTS (
    SELECT 1 FROM question_version_child relation WHERE relation.child_question_id = q.id
);
ALTER TABLE question_aggregate
    ADD CONSTRAINT fk_question_parent_aggregate FOREIGN KEY (parent_question_id) REFERENCES question_aggregate(id);
CREATE INDEX idx_question_parent ON question_aggregate(parent_question_id, id);

-- 每个提交项都有独立可分页投影；不保存原始 JSON、题干、答案、路径明文或外部 URL。
CREATE TABLE question_legacy_review_item (
    id CHAR(36) PRIMARY KEY,
    import_task_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    source_ref_id CHAR(36) NULL,
    external_id VARCHAR(160) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    path_edition VARCHAR(100),
    json_edition VARCHAR(100),
    path_grade VARCHAR(100),
    json_grade VARCHAR(100),
    original_difficulty VARCHAR(40),
    outcome VARCHAR(24) NOT NULL,
    question_id CHAR(36) NULL,
    issue_codes_json VARCHAR(1000) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    resolution_action VARCHAR(30) NULL,
    resolved_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_legacy_review_batch_item UNIQUE (import_task_id, batch_id, external_id),
    CONSTRAINT fk_legacy_review_task FOREIGN KEY (import_task_id) REFERENCES question_import_task(id),
    CONSTRAINT fk_legacy_review_source FOREIGN KEY (source_ref_id) REFERENCES question_legacy_source_ref(id),
    CONSTRAINT fk_legacy_review_question FOREIGN KEY (question_id) REFERENCES question_aggregate(id)
);
CREATE INDEX idx_legacy_review_page
    ON question_legacy_review_item(import_task_id, outcome, created_at, id);

-- 为已合并 T09A 事实生成初始刷新投影，ID 复用来源引用 ID 便于精确追踪。
INSERT INTO question_legacy_review_item(
    id,import_task_id,batch_id,owner_id,source_ref_id,external_id,content_sha256,
    path_edition,json_edition,path_grade,json_grade,original_difficulty,outcome,question_id,
    issue_codes_json,revision,resolution_action,resolved_at,created_at,updated_at
)
SELECT id,import_task_id,batch_id,owner_id,id,external_id,content_sha256,
       path_edition,json_edition,path_grade,json_grade,original_difficulty,outcome,question_id,
       issue_codes_json,0,NULL,NULL,created_at,updated_at
FROM question_legacy_source_ref;

-- clientRequestId/请求指纹提供解决重放；每个项只允许一次终态人工决定。
CREATE TABLE question_legacy_item_resolution (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    import_task_id CHAR(36) NOT NULL,
    review_item_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    action VARCHAR(30) NOT NULL,
    resulting_question_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_legacy_resolution_request UNIQUE (owner_id, client_request_id),
    CONSTRAINT uk_legacy_resolution_item UNIQUE (review_item_id),
    CONSTRAINT fk_legacy_resolution_task FOREIGN KEY (import_task_id) REFERENCES question_import_task(id),
    CONSTRAINT fk_legacy_resolution_item FOREIGN KEY (review_item_id) REFERENCES question_legacy_review_item(id),
    CONSTRAINT fk_legacy_resolution_question FOREIGN KEY (resulting_question_id) REFERENCES question_aggregate(id)
);
