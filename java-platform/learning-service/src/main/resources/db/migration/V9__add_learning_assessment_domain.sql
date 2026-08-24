-- T11 学习与测评领域向前迁移。真实开发库已应用到 V8，因此必须从 V9 开始；补写 V4 会在已达高版本的数据库中形成无法正常按序应用的低版本迁移。
-- 所有外键均留在 learning Schema；课程/课时只保存稳定 ID 和报名时观察到的发布版本标签，不复制课程表或建立跨 Schema 外键。

CREATE TABLE enrollment (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    course_version_id VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    enrolled_at TIMESTAMP(6) NOT NULL,
    canceled_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_enrollment_learner_course UNIQUE (learner_id, course_id),
    CONSTRAINT chk_enrollment_status CHECK (status IN ('ACTIVE', 'CANCELED'))
);
CREATE INDEX idx_enrollment_course_status ON enrollment(course_id, status, updated_at);

CREATE TABLE lesson_progress (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    position_seconds BIGINT NOT NULL DEFAULT 0,
    watched_seconds BIGINT NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    last_event_at TIMESTAMP(6) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_lesson_progress_owner UNIQUE (learner_id, course_id, lesson_id),
    CONSTRAINT chk_lesson_progress_values CHECK (position_seconds >= 0 AND watched_seconds >= 0)
);
CREATE INDEX idx_lesson_progress_course ON lesson_progress(course_id, learner_id, completed);

CREATE TABLE lesson_progress_event (
    client_event_id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    position_seconds BIGINT NOT NULL,
    watched_seconds INT NOT NULL,
    completed BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_progress_event_bounds CHECK (position_seconds BETWEEN 0 AND 28800 AND watched_seconds BETWEEN 0 AND 300)
);
CREATE INDEX idx_progress_event_owner_time ON lesson_progress_event(learner_id, lesson_id, occurred_at);

CREATE TABLE learning_note (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    content TEXT NOT NULL,
    position_seconds BIGINT,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_note_position CHECK (position_seconds IS NULL OR position_seconds BETWEEN 0 AND 28800)
);
CREATE INDEX idx_learning_note_owner_lesson ON learning_note(learner_id, course_id, lesson_id, updated_at);

CREATE TABLE assessment (
    id CHAR(36) PRIMARY KEY,
    owner_teacher_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36),
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    assessment_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    answer_publication_policy VARCHAR(30) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMP(6),
    archived_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_assessment_type CHECK (assessment_type IN ('PRACTICE', 'HOMEWORK', 'EXAM')),
    CONSTRAINT chk_assessment_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_answer_publication CHECK (answer_publication_policy IN ('AFTER_SUBMISSION', 'AFTER_GRADED', 'NEVER'))
);
CREATE INDEX idx_assessment_owner_course ON assessment(owner_teacher_id, course_id, updated_at);
CREATE INDEX idx_assessment_learner_list ON assessment(course_id, status, published_at);

CREATE TABLE assessment_question (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    question_version_id CHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    points DECIMAL(10,2) NOT NULL,
    -- 完整不可变题目快照只在本 Schema 内使用；学生投影必须显式删除答案、解析和评分信息。
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_assessment_question_sequence UNIQUE (assessment_id, sequence_no),
    CONSTRAINT uk_assessment_question_version UNIQUE (assessment_id, question_version_id),
    CONSTRAINT fk_assessment_question_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT chk_assessment_question_points CHECK (points > 0)
);

CREATE TABLE assessment_assignment (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    assignment_type VARCHAR(20) NOT NULL,
    target_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_assessment_assignment UNIQUE (assessment_id, assignment_type, target_id),
    CONSTRAINT fk_assessment_assignment_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT chk_assignment_type CHECK (assignment_type IN ('COURSE'))
);

CREATE TABLE assessment_attempt (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    draft_request_id CHAR(36),
    draft_fingerprint CHAR(64),
    submit_request_id CHAR(36),
    submission_fingerprint CHAR(64),
    score DECIMAL(10,2),
    total_points DECIMAL(10,2) NOT NULL,
    submitted_at TIMESTAMP(6),
    graded_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_attempt_learner_assessment UNIQUE (assessment_id, learner_id),
    CONSTRAINT fk_attempt_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT chk_attempt_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_GRADED', 'GRADED')),
    CONSTRAINT chk_attempt_score CHECK (score IS NULL OR score >= 0)
);
CREATE INDEX idx_attempt_assessment_status ON assessment_attempt(assessment_id, status, updated_at);

CREATE TABLE assessment_answer (
    id CHAR(36) PRIMARY KEY,
    attempt_id CHAR(36) NOT NULL,
    assessment_question_id CHAR(36) NOT NULL,
    answer_json LONGTEXT NOT NULL,
    score DECIMAL(10,2),
    correct BOOLEAN,
    grading_status VARCHAR(30) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    graded_by CHAR(36),
    graded_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_answer_attempt_question UNIQUE (attempt_id, assessment_question_id),
    CONSTRAINT fk_answer_attempt FOREIGN KEY (attempt_id) REFERENCES assessment_attempt(id),
    CONSTRAINT fk_answer_assessment_question FOREIGN KEY (assessment_question_id) REFERENCES assessment_question(id),
    CONSTRAINT chk_answer_grading_status CHECK (grading_status IN ('PENDING_MANUAL', 'AUTO_GRADED', 'MANUALLY_GRADED')),
    CONSTRAINT chk_answer_score CHECK (score IS NULL OR score >= 0)
);

CREATE TABLE manual_grading_record (
    id CHAR(36) PRIMARY KEY,
    client_request_id CHAR(36) NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    assessment_answer_id CHAR(36) NOT NULL,
    grader_id CHAR(36) NOT NULL,
    answer_revision BIGINT NOT NULL,
    score DECIMAL(10,2) NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    graded_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_manual_grade_request_answer UNIQUE (client_request_id, assessment_answer_id),
    CONSTRAINT fk_manual_grade_attempt FOREIGN KEY (attempt_id) REFERENCES assessment_attempt(id),
    CONSTRAINT fk_manual_grade_answer FOREIGN KEY (assessment_answer_id) REFERENCES assessment_answer(id),
    CONSTRAINT chk_manual_grade_score CHECK (score >= 0)
);

CREATE TABLE wrong_question (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    assessment_id CHAR(36) NOT NULL,
    question_version_id CHAR(36) NOT NULL,
    wrong_count INT NOT NULL,
    first_wrong_at TIMESTAMP(6) NOT NULL,
    last_wrong_at TIMESTAMP(6) NOT NULL,
    last_attempt_id CHAR(36) NOT NULL,
    CONSTRAINT uk_wrong_question_owner_version UNIQUE (learner_id, course_id, question_version_id),
    CONSTRAINT fk_wrong_question_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_wrong_question_attempt FOREIGN KEY (last_attempt_id) REFERENCES assessment_attempt(id),
    CONSTRAINT chk_wrong_count CHECK (wrong_count > 0)
);
CREATE INDEX idx_wrong_question_owner_time ON wrong_question(learner_id, last_wrong_at);

-- attempt + assessment_question 是错题累计的业务幂等键；无需保存请求 payload hash。
CREATE TABLE wrong_question_occurrence (
    attempt_id CHAR(36) NOT NULL,
    assessment_question_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (attempt_id, assessment_question_id),
    CONSTRAINT fk_wrong_occurrence_attempt FOREIGN KEY (attempt_id) REFERENCES assessment_attempt(id),
    CONSTRAINT fk_wrong_occurrence_question FOREIGN KEY (assessment_question_id) REFERENCES assessment_question(id)
);

CREATE TABLE learning_idempotency_request (
    owner_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_id, client_request_id)
);
