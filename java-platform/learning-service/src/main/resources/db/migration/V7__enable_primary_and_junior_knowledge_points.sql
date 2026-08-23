-- T10B 学段开放策略：图谱来源的小学、初中节点可绑定，高中节点继续保留但保持禁用。
-- 本迁移只改变平台 enabled 投影，不补写 parent_id/taxonomy_parent_id，也不新增或删除图谱关系。
UPDATE knowledge_point
SET enabled = CASE WHEN normalized_stage IN ('PRIMARY', 'JUNIOR') THEN TRUE ELSE FALSE END,
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_system IS NOT NULL
  AND normalized_stage IN ('PRIMARY', 'JUNIOR', 'SENIOR')
  AND enabled <> CASE WHEN normalized_stage IN ('PRIMARY', 'JUNIOR') THEN TRUE ELSE FALSE END;
