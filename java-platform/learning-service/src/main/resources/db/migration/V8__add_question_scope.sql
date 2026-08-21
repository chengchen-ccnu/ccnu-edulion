-- T10B 向前扩展：区分管理员公共系统题与教师私有题，既有数据保持 TEACHER 语义。
-- owner_id 继续记录创建/管理主体；SYSTEM 不等于无主数据，正式历史导入由管理员 owner 持有。

ALTER TABLE question_aggregate
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TEACHER' AFTER owner_id;
ALTER TABLE question_aggregate
    ADD CONSTRAINT chk_question_scope CHECK (scope IN ('SYSTEM', 'TEACHER'));
CREATE INDEX idx_question_visible_scope
    ON question_aggregate(scope, status, parent_question_id, updated_at, id);

-- 导入任务冻结其产出作用域，防止重试期间因调用入口变化而混写 SYSTEM/TEACHER 题目。
ALTER TABLE question_import_task
    ADD COLUMN question_scope VARCHAR(16) NOT NULL DEFAULT 'TEACHER' AFTER owner_id;
ALTER TABLE question_import_task
    ADD CONSTRAINT chk_question_import_scope CHECK (question_scope IN ('SYSTEM', 'TEACHER'));
