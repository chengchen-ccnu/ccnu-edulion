-- T10B 已确认映射的向前迁移：staging 同时保存规范学段与文字类型名，供正式写入前复核。
-- 来源 parentId、status 和颜色仍是来源元数据；本迁移不启用节点，也不生成父级。
ALTER TABLE knowledge_graph_import_node ADD COLUMN normalized_stage VARCHAR(20) NULL;
ALTER TABLE knowledge_graph_import_node ADD COLUMN knowledge_type_name VARCHAR(100) NULL;
