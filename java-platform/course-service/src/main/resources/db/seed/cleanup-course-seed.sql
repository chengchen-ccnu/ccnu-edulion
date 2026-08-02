-- 只可在已核对名称的 course local/test Schema 执行；按外键逆序清理固定 T05 seed 课程链路。

UPDATE course
SET current_draft_version_id = NULL,
    published_version_id = NULL
WHERE id = '00000000-0000-4000-8000-000000000201';

-- 先删除固定课程版本/课时资源产生的媒体引用 Outbox，避免清理后遗留无从对账的测试事件。
DELETE FROM outbox_event
WHERE EXISTS (
    SELECT 1
    FROM course_version cv
    WHERE cv.course_id = '00000000-0000-4000-8000-000000000201'
      AND payload_json LIKE CONCAT('%"referenceId":"', cv.id, '"%')
);

DELETE FROM outbox_event
WHERE EXISTS (
    SELECT 1
    FROM lesson_media_resource lmr
    JOIN lesson l ON l.id = lmr.lesson_id
    JOIN chapter ch ON ch.id = l.chapter_id
    JOIN course_version cv ON cv.id = ch.course_version_id
    WHERE cv.course_id = '00000000-0000-4000-8000-000000000201'
      AND payload_json LIKE CONCAT('%"referenceId":"', lmr.id, '"%')
);

DELETE FROM lesson_media_resource
WHERE lesson_id IN (
    SELECT l.id
    FROM lesson l
    JOIN chapter ch ON ch.id = l.chapter_id
    JOIN course_version cv ON cv.id = ch.course_version_id
    WHERE cv.course_id = '00000000-0000-4000-8000-000000000201'
);

DELETE FROM lesson
WHERE chapter_id IN (
    SELECT ch.id
    FROM chapter ch
    JOIN course_version cv ON cv.id = ch.course_version_id
    WHERE cv.course_id = '00000000-0000-4000-8000-000000000201'
);

DELETE FROM chapter
WHERE course_version_id IN (
    SELECT id
    FROM course_version
    WHERE course_id = '00000000-0000-4000-8000-000000000201'
);

DELETE FROM course_version
WHERE course_id = '00000000-0000-4000-8000-000000000201';

DELETE FROM course
WHERE id = '00000000-0000-4000-8000-000000000201';

DELETE FROM category
WHERE id = '00000000-0000-4000-8000-000000000501'
  AND NOT EXISTS (
      SELECT 1 FROM course_version
      WHERE category_id = '00000000-0000-4000-8000-000000000501'
  );
