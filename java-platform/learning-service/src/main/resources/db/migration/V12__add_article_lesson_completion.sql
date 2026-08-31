-- T12F.1 Learning V12：ARTICLE 完成事实与请求幂等键独立于视频 progress-event，普通客户端没有回退命令。
CREATE TABLE article_lesson_completion (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_article_completion_fact UNIQUE (learner_id, course_id, lesson_id),
    CONSTRAINT chk_article_completion_revision CHECK (revision >= 1)
);

CREATE TABLE article_completion_request (
    client_request_id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    completion_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_article_completion_request_fact FOREIGN KEY (completion_id) REFERENCES article_lesson_completion(id)
);

CREATE INDEX idx_article_completion_owner ON article_lesson_completion(learner_id, course_id, completed_at);
