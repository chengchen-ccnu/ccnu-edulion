-- T11C.1 只向前扩展作业、应交名单、成绩册和同步 CSV 导出事实；不改写 V9～V13。
-- 课程与 IAM 仍只保存稳定 ID。课程学生编号属于 Learning 课程报名事实，昵称由 IAM 批量 API 投影，禁止跨 Schema Join。

ALTER TABLE assessment ADD COLUMN late_policy VARCHAR(20) NOT NULL DEFAULT 'DISALLOW';
ALTER TABLE assessment ADD COLUMN late_until TIMESTAMP(6) NULL;
ALTER TABLE assessment ADD COLUMN max_attempts INT NOT NULL DEFAULT 1;
-- V10 只允许 EXAM 带时间；V14 向前放开 HOMEWORK，但继续禁止 PRACTICE 配置时间。
ALTER TABLE assessment DROP CONSTRAINT chk_assessment_timing_type;
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_timing_type CHECK (
        (assessment_type = 'PRACTICE' AND opens_at IS NULL AND closes_at IS NULL AND time_limit_seconds IS NULL)
        -- 已存在的 T11B HOMEWORK 可能没有时间节点；新建/更新 API 由服务层强制 closesAt。
        OR (assessment_type = 'HOMEWORK' AND time_limit_seconds IS NULL)
        OR assessment_type = 'EXAM'
    );
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_late_policy CHECK (late_policy IN ('DISALLOW', 'ALLOW_UNTIL'));
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_max_attempts CHECK (max_attempts BETWEEN 1 AND 5);
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_retry_type CHECK (assessment_type = 'HOMEWORK' OR max_attempts = 1);
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_late_type CHECK (
        assessment_type = 'HOMEWORK' OR (late_policy = 'DISALLOW' AND late_until IS NULL)
    );
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_late_window CHECK (
        late_policy = 'DISALLOW' OR (closes_at IS NOT NULL AND late_until IS NOT NULL AND late_until >= closes_at)
    );
CREATE INDEX idx_assessment_homework_deadline
    ON assessment(assessment_type, close_status, closes_at, late_until, updated_at);

-- V9 的唯一键只允许一份 attempt；删除旧键后以序号支持 HOMEWORK 的 1～5 次不可变提交。
ALTER TABLE assessment_attempt DROP CONSTRAINT uk_attempt_learner_assessment;
ALTER TABLE assessment_attempt ADD COLUMN attempt_sequence INT NOT NULL DEFAULT 1;
ALTER TABLE assessment_attempt ADD COLUMN late_submission BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE assessment_attempt
    ADD CONSTRAINT uk_attempt_learner_sequence UNIQUE (assessment_id, learner_id, attempt_sequence);
ALTER TABLE assessment_attempt
    ADD CONSTRAINT chk_attempt_sequence CHECK (attempt_sequence BETWEEN 1 AND 5);
CREATE INDEX idx_attempt_final_lookup ON assessment_attempt(assessment_id, learner_id, submitted_at, id);

-- 关闭时冻结的应交名单及学生任务投影。final_attempt_id 是每名学习者唯一的最终有效 attempt 指针。
CREATE TABLE assessment_participant (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    enrollment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    submission_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    grading_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    attempts_used INT NOT NULL DEFAULT 0,
    final_attempt_id CHAR(36) NULL,
    last_submitted_at TIMESTAMP(6) NULL,
    late_submission BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_assessment_participant UNIQUE (assessment_id, learner_id),
    CONSTRAINT fk_participant_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_participant_final_attempt FOREIGN KEY (final_attempt_id) REFERENCES assessment_attempt(id),
    CONSTRAINT chk_participant_enrollment CHECK (enrollment_status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT chk_participant_submission CHECK (submission_status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'MISSING', 'WITHDRAWN')),
    CONSTRAINT chk_participant_grading CHECK (grading_status IN ('NOT_REQUIRED', 'PENDING', 'PARTIALLY_GRADED', 'GRADED')),
    CONSTRAINT chk_participant_attempts CHECK (attempts_used BETWEEN 0 AND 5)
);
CREATE INDEX idx_participant_course_status
    ON assessment_participant(learner_id, submission_status, grading_status, updated_at);

-- 课程内展示编号永久分配，不因退课回收；首次报名排序由服务层以 enrolled_at、learner_id 稳定回填。
CREATE TABLE course_student_number (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    student_number INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_course_student_learner UNIQUE (course_id, learner_id),
    CONSTRAINT uk_course_student_number UNIQUE (course_id, student_number),
    CONSTRAINT chk_course_student_number CHECK (student_number >= 1)
);

-- 多实例报名时先原子推进此行，再插入 course_student_number；唯一键仍是最终正确性约束。
CREATE TABLE course_student_number_counter (
    course_id CHAR(36) PRIMARY KEY,
    next_number INT NOT NULL,
    CONSTRAINT chk_course_student_next_number CHECK (next_number >= 1)
);

-- 截止调整必须可审计；reason 是业务原因，trace_id 只保存关联标识，不保存答案或隐私。
CREATE TABLE assessment_deadline_audit (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    operator_id CHAR(36) NOT NULL,
    expected_revision BIGINT NOT NULL,
    old_closes_at TIMESTAMP(6) NULL,
    new_closes_at TIMESTAMP(6) NOT NULL,
    old_late_until TIMESTAMP(6) NULL,
    new_late_until TIMESTAMP(6) NULL,
    reason VARCHAR(500) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_deadline_audit_request UNIQUE (assessment_id, operator_id, expected_revision, occurred_at),
    CONSTRAINT fk_deadline_audit_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id)
);

-- 结算批次与补偿使用确定性 operation_key 幂等，便于恢复和诊断，不把答案复制到审计表。
CREATE TABLE assessment_settlement_audit (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    operation_key VARCHAR(160) NOT NULL,
    processed_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    safe_error VARCHAR(120) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_settlement_audit_operation UNIQUE (assessment_id, operation_key),
    CONSTRAINT fk_settlement_audit_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT chk_settlement_audit_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

-- 同步成绩册导出审计只记录范围和数量，不记录 CSV 内容；client_request_id 保证重试不重复审计。
CREATE TABLE gradebook_export_audit (
    id CHAR(36) PRIMARY KEY,
    client_request_id CHAR(36) NOT NULL,
    operator_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    filter_json VARCHAR(4000) NOT NULL,
    row_count INT NOT NULL,
    assessment_count INT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    CONSTRAINT uk_gradebook_export_request UNIQUE (operator_id, client_request_id),
    CONSTRAINT chk_gradebook_export_counts CHECK (row_count BETWEEN 0 AND 5000 AND assessment_count BETWEEN 0 AND 100)
);

-- 存量 V9～V13 attempt 在新增序号列上统一成为 sequence=1；默认值已保证无损兼容旧成绩。
