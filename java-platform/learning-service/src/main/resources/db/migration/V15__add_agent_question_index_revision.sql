-- T14.1：公共题索引拥有独立递增 revision，确保组合子题 tombstone 能覆盖此前根题 UPSERT。
-- 不能复用 question_aggregate.revision：历史导入清理依赖业务 revision=0 判断题目未被人工修改。
CREATE TABLE question_index_revision (
    question_id CHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_question_index_revision_question
        FOREIGN KEY (question_id) REFERENCES question_aggregate(id)
);
