-- T10B 知识图谱导入。V4 保留给 T11；本迁移只在 learning Schema 内向前扩展。
-- source_parent_key 是不可解析的来源分组键，绝不能写入 parent_id/taxonomy_parent_id 外键。
ALTER TABLE knowledge_point MODIFY COLUMN stage VARCHAR(20) NULL;
ALTER TABLE knowledge_point MODIFY COLUMN grade VARCHAR(20) NULL;
ALTER TABLE knowledge_point ADD COLUMN source_system VARCHAR(80) NULL;
ALTER TABLE knowledge_point ADD COLUMN source_know_id VARCHAR(80) NULL;
ALTER TABLE knowledge_point ADD COLUMN discipline_code VARCHAR(30) NULL;
ALTER TABLE knowledge_point ADD COLUMN education_stage_code VARCHAR(20) NULL;
ALTER TABLE knowledge_point ADD COLUMN normalized_stage VARCHAR(20) NULL;
ALTER TABLE knowledge_point ADD COLUMN source_parent_key VARCHAR(80) NULL;
ALTER TABLE knowledge_point ADD COLUMN taxonomy_parent_id VARCHAR(36) NULL;
ALTER TABLE knowledge_point ADD COLUMN knowledge_type_id VARCHAR(80) NULL;
ALTER TABLE knowledge_point ADD COLUMN knowledge_type_name VARCHAR(100) NULL;
ALTER TABLE knowledge_point ADD COLUMN knowledge_type_colour VARCHAR(40) NULL;
ALTER TABLE knowledge_point ADD COLUMN level_sort_index INT NULL;
ALTER TABLE knowledge_point ADD COLUMN node_kind VARCHAR(40) NULL;
ALTER TABLE knowledge_point ADD COLUMN source_status VARCHAR(40) NULL;
ALTER TABLE knowledge_point ADD COLUMN source_content_sha256 CHAR(64) NULL;
ALTER TABLE knowledge_point
    ADD CONSTRAINT fk_knowledge_taxonomy_parent FOREIGN KEY (taxonomy_parent_id) REFERENCES knowledge_point(id);
CREATE UNIQUE INDEX uk_knowledge_source ON knowledge_point(source_system, source_know_id);
CREATE INDEX idx_knowledge_search
    ON knowledge_point(enabled, education_stage_code, knowledge_type_id, level_sort_index, id);

-- 业务方向始终 prerequisite -> dependent；源 Neo4j start/end 元数据不进入本表。
CREATE TABLE knowledge_point_prerequisite (
    id CHAR(36) PRIMARY KEY,
    prerequisite_id CHAR(36) NOT NULL,
    dependent_id CHAR(36) NOT NULL,
    relation_type VARCHAR(30) NOT NULL,
    source_system VARCHAR(80) NOT NULL,
    source_relation_type VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_knowledge_prerequisite_from FOREIGN KEY (prerequisite_id) REFERENCES knowledge_point(id),
    CONSTRAINT fk_knowledge_prerequisite_to FOREIGN KEY (dependent_id) REFERENCES knowledge_point(id),
    CONSTRAINT uk_knowledge_prerequisite UNIQUE (prerequisite_id, dependent_id, relation_type),
    CONSTRAINT chk_knowledge_prerequisite_type CHECK (relation_type = 'PREREQUISITE'),
    CONSTRAINT chk_knowledge_prerequisite_not_self CHECK (prerequisite_id <> dependent_id)
);
CREATE INDEX idx_knowledge_prerequisite_to ON knowledge_point_prerequisite(dependent_id, prerequisite_id);

-- 导入任务和 staging 表只保存结构化字段与摘要，不保存源绝对路径或原始 JSON。
CREATE TABLE knowledge_graph_import_task (
    id CHAR(36) PRIMARY KEY,
    administrator_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    source_system VARCHAR(80) NOT NULL,
    dry_run BOOLEAN NOT NULL,
    node_file_sha256 CHAR(64) NOT NULL,
    relation_file_sha256 CHAR(64) NOT NULL,
    expected_node_count INT NOT NULL,
    expected_relation_count INT NOT NULL,
    processed_node_count INT NOT NULL DEFAULT 0,
    processed_relation_count INT NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL,
    summary_json VARCHAR(10000) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_knowledge_graph_import_request UNIQUE (administrator_id, client_request_id)
);

CREATE TABLE knowledge_graph_import_batch (
    import_task_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    batch_kind VARCHAR(20) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    final_batch BOOLEAN NOT NULL,
    accepted_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (import_task_id, batch_id),
    CONSTRAINT fk_knowledge_graph_batch_task FOREIGN KEY (import_task_id) REFERENCES knowledge_graph_import_task(id)
);

CREATE TABLE knowledge_graph_import_node (
    import_task_id CHAR(36) NOT NULL,
    source_know_id VARCHAR(80) NOT NULL,
    name VARCHAR(100) NOT NULL,
    discipline_code VARCHAR(30) NOT NULL,
    education_stage_code VARCHAR(20) NOT NULL,
    knowledge_type_id VARCHAR(80) NOT NULL,
    knowledge_type_colour VARCHAR(40) NULL,
    level_sort_index INT NOT NULL,
    node_kind VARCHAR(40) NOT NULL,
    source_parent_key VARCHAR(80) NULL,
    source_status VARCHAR(40) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (import_task_id, source_know_id),
    CONSTRAINT fk_knowledge_graph_node_task FOREIGN KEY (import_task_id) REFERENCES knowledge_graph_import_task(id)
);

CREATE TABLE knowledge_graph_import_relation (
    import_task_id CHAR(36) NOT NULL,
    prerequisite_source_know_id VARCHAR(80) NOT NULL,
    dependent_source_know_id VARCHAR(80) NOT NULL,
    source_relation_type VARCHAR(80) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (import_task_id, prerequisite_source_know_id, dependent_source_know_id),
    CONSTRAINT fk_knowledge_graph_relation_task FOREIGN KEY (import_task_id) REFERENCES knowledge_graph_import_task(id)
);

CREATE TABLE knowledge_graph_import_issue (
    id CHAR(36) PRIMARY KEY,
    import_task_id CHAR(36) NOT NULL,
    issue_code VARCHAR(80) NOT NULL,
    subject_kind VARCHAR(20) NOT NULL,
    subject_fingerprint CHAR(64) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_knowledge_graph_issue UNIQUE (import_task_id, issue_code, subject_kind, subject_fingerprint),
    CONSTRAINT fk_knowledge_graph_issue_task FOREIGN KEY (import_task_id) REFERENCES knowledge_graph_import_task(id)
);
CREATE INDEX idx_knowledge_graph_issue_page ON knowledge_graph_import_issue(import_task_id, created_at, id);

-- 历史题目版本仍以内部 UUID 关联；新增列忠实保留 tags 的顺序与标记语义。
ALTER TABLE question_version_knowledge_point ADD COLUMN priority INT NULL;
ALTER TABLE question_version_knowledge_point ADD COLUMN core_flag BOOLEAN NULL;
ALTER TABLE question_version_knowledge_point ADD COLUMN exam_point_flag BOOLEAN NULL;
ALTER TABLE question_version_knowledge_point ADD COLUMN last_exam_point_flag BOOLEAN NULL;
ALTER TABLE question_version_knowledge_point ADD COLUMN source_tag_sort_index INT NULL;
