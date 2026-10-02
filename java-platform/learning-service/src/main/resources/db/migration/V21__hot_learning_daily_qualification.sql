-- 真实落库学习的首次日资格及Outbox身份，同课程/学生/上海自然日只登记一次。
-- 不使用含报名的activity_day，不回填无日期的旧累计进度；现有真实日事实由只读源合并。
CREATE TABLE hot_learning_day (
    course_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    activity_date DATE NOT NULL,
    event_id CHAR(36) NOT NULL,
    PRIMARY KEY (course_id, learner_id, activity_date),
    KEY idx_hot_learning_window (course_id, activity_date, learner_id)
);
