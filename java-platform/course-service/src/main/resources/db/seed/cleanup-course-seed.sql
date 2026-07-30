UPDATE course
SET current_draft_version_id = NULL,
    published_version_id = NULL
WHERE id = '00000000-0000-4000-8000-000000000201';

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
