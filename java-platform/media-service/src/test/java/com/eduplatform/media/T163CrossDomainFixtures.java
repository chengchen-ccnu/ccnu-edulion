package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.web.LearningDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** T16.3专用验收Fixture：各JdbcTemplate只连接自己命名的开发Schema；无跨库Join，不修改人工课程或创建别的任务领域表。 */
final class T163CrossDomainFixtures implements AutoCloseable {
    static final String PROTECTED="5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057";
    final JdbcTemplate course=database("COURSE_MYSQL_DATABASE","edu_platform_course_dev");
    final JdbcTemplate learning=database("LEARNING_MYSQL_DATABASE","edu_platform_learning_dev");
    final LearningAssessmentRepository tasks=new LearningAssessmentRepository(learning,new ObjectMapper());
    final List<Course> courses=new ArrayList<>();
    final List<String> assessments=new ArrayList<>();
    final String owner;
    final Map<String,Object> protectedBefore;
    T163CrossDomainFixtures(String owner) {
        this.owner=owner;
        protectedBefore=course.queryForMap("SELECT * FROM course WHERE id=?",PROTECTED);
    }

    /** 本域精确UUID最小发布/草稿课程；VIDEO只有元数据，不伪造可播放媒体，不涉及OSS。 */
    Course create(boolean published) {
        String id=UUID.randomUUID().toString(),version=UUID.randomUUID().toString(),chapter=UUID.randomUUID().toString(),lesson=UUID.randomUUID().toString();
        Course value=new Course(id,version,chapter,lesson);courses.add(value);
        String category=course.queryForObject("SELECT id FROM category WHERE status='ACTIVE' ORDER BY id LIMIT 1",String.class);
        course.update("INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,published_at) VALUES(?,?,?,?,?)",
                id,UUID.randomUUID().toString(),owner,published?"PUBLISHED":"DRAFT",published?Timestamp.from(Instant.now()):null);
        course.update("INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,description,cover_url,instructor_display_name,category_id,level,access_type) VALUES(?,?,1,'v1',?,'t163_course_test','test','test','','teacher_test_163',?,'BEGINNER','PUBLIC')",
                version,id,published?"PUBLISHED":"DRAFT",category);
        course.update("UPDATE course SET current_draft_version_id=?,published_version_id=? WHERE id=?",version,published?version:null,id);
        course.update("INSERT INTO chapter(id,course_version_id,title,position) VALUES(?,?,'t163_test',1)",chapter,version);
        course.update("INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,content_revision_id) VALUES(?,?,'t163_video_metadata','VIDEO',1,FALSE,?)",
                lesson,chapter,lesson);
        return value;
    }

    /** 使用Learning既有Repository建立最小任务/名单；报名和开始草稿仍经真实Gateway API，不创建任何审核决定。 */
    String task(Course course,LearningDtos.AssessmentType type,String learner) {
        String id=UUID.randomUUID().toString();assessments.add(id);Instant now=Instant.now();
        tasks.insertAssessment(new LearningAssessmentRepository.AssessmentRow(id,owner,course.id(),course.lesson(),
                "t163_task_test","test",type,LearningDtos.AssessmentStatus.PUBLISHED,LearningDtos.AnswerPublicationPolicy.NEVER,
                now.minusSeconds(10),now.plusSeconds(3600),type==LearningDtos.AssessmentType.EXAM?600:null,1,
                now.minusSeconds(1),null,now,now));
        if(type==LearningDtos.AssessmentType.HOMEWORK)learning.update("UPDATE assessment SET late_policy='ALLOW_UNTIL',late_until=? WHERE id=?",
                Timestamp.from(now.plusSeconds(7200)),id);
        tasks.ensureCourseAssignment(UUID.randomUUID().toString(),id,course.id(),now);
        tasks.insertParticipant(UUID.randomUUID().toString(),id,learner,now);
        return id;
    }

    /** 仅删除本轮UUID及它们的已消费事件，按本域外键顺序；保留现有分类、手工线性代数及所有无关领域数据。 */
    @Override public void close() {
        for(String task:assessments) {
            learning.update("DELETE FROM assessment_answer WHERE attempt_id IN(SELECT id FROM assessment_attempt WHERE assessment_id=?)",task);
            learning.update("DELETE FROM assessment_participant WHERE assessment_id=?",task);
            learning.update("DELETE FROM assessment_attempt WHERE assessment_id=?",task);
            learning.update("DELETE FROM assessment_assignment WHERE assessment_id=?",task);
            learning.update("DELETE FROM assessment_question WHERE assessment_id=?",task);
            learning.update("DELETE FROM assessment WHERE id=?",task);
        }
        for(Course value:courses) {
            for(String table:List.of("lesson_learning_day","learning_activity_day","lesson_progress_event","lesson_progress",
                    "published_lesson_content_projection","published_course_content_projection","learning_course_clock_interval",
                    "course_availability_consumed_event","learning_course_clock","course_student_number","course_student_number_counter"))
                learning.update("DELETE FROM "+table+" WHERE course_id=?",value.id());
            List<String> enrollments=learning.queryForList("SELECT id FROM enrollment WHERE learner_id=? AND course_id=?",String.class,owner,value.id());
            for(String enrollment:enrollments) {
                for(String event:learning.queryForList("SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?",String.class,enrollment))
                    course.update("DELETE FROM consumed_learning_event WHERE event_id=?",event);
                learning.update("DELETE FROM learning_outbox_event WHERE aggregate_id=?",enrollment);
            }
            learning.update("DELETE FROM enrollment WHERE learner_id=? AND course_id=?",owner,value.id());
            course.update("DELETE FROM course_enrollment_access WHERE course_id=?",value.id());
            course.update("DELETE FROM outbox_event WHERE aggregate_id=?",value.id());
            course.update("DELETE FROM course_suspension_interval WHERE course_id=?",value.id());
            course.update("DELETE FROM lesson WHERE id=? AND chapter_id=?",value.lesson(),value.chapter());
            course.update("DELETE FROM chapter WHERE id=? AND course_version_id=?",value.chapter(),value.version());
            course.update("UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id=? AND owner_teacher_id=?",value.id(),owner);
            course.update("DELETE FROM course_version WHERE id=? AND course_id=?",value.version(),value.id());
            course.update("DELETE FROM course WHERE id=? AND owner_teacher_id=?",value.id(),owner);
            assertThat(course.queryForObject("SELECT COUNT(*) FROM course WHERE id=?",Long.class,value.id())).isZero();
        }
        course.update("DELETE FROM teacher_course_availability WHERE teacher_id=?",owner);
        assertThat(course.queryForMap("SELECT * FROM course WHERE id=?",PROTECTED)).isEqualTo(protectedBefore);
    }

    /** 凭据仅由DPAPI包装器注入；连接前精确限定Schema，模板从不跨库查询。 */
    private static JdbcTemplate database(String variable,String required) {
        if(!required.equals(System.getenv(variable)))throw new IllegalStateException("T16.3需要明确专用开发Schema");
        var source=new DriverManagerDataSource("jdbc:mysql://"+System.getenv().getOrDefault("MYSQL_HOST","127.0.0.1")+":"
                +System.getenv().getOrDefault("MYSQL_PORT","3306")+"/"+required
                +"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                System.getenv("MYSQL_USERNAME"),System.getenv("MYSQL_PASSWORD"));
        JdbcTemplate jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(required);return jdbc;
    }
    record Course(String id,String version,String chapter,String lesson) {}
}
