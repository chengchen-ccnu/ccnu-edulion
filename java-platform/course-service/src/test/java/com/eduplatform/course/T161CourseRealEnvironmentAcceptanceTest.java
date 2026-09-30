package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Course 自己创建/清理所属域的精确 UUID Fixture，启动真实 HTTP 后让独立 Learning JVM 验收。
 * 两个 JVM 只通过 HTTP/RabbitMQ 协调；Fixture 不含媒体对象，不请求 OSS，不修改人工课程。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "platform.course.seed.enabled=false", "platform.course.media.messaging.enabled=true",
        "platform.course.media.messaging.outbox-poll-delay=200ms"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="T161_REAL_ACCEPTANCE_ENABLED",matches="true")
class T161CourseRealEnvironmentAcceptanceTest {
    private static final String PROTECTED="5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057";
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    /** VIDEO 仅使用最小修订元数据测进度，不伪造可播放文件；媒体授权仅验证暂停时拒绝进入 Media。 */
    @Test void independentServicesUseRealMysqlRedisRabbitAndCleanExactIds() throws Exception {
        assertThat(System.getenv("COURSE_MYSQL_DATABASE")).isEqualTo("edu_platform_course_dev");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("edu_platform_course_dev");
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM course WHERE id=?",PROTECTED);
        String owner=UUID.randomUUID().toString(), course=UUID.randomUUID().toString();
        String version=UUID.randomUUID().toString(), chapter=UUID.randomUUID().toString(), lesson=UUID.randomUUID().toString();
        String category=jdbc.queryForObject("SELECT id FROM category WHERE status='ACTIVE' ORDER BY id LIMIT 1",String.class);
        Path root=Path.of("..").toAbsolutePath().normalize().getParent();
        Path log=root.resolve(".m2/t16-1-learning-real.log");
        Process child=null;
        try {
            jdbc.update("INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,published_at) VALUES(?,?,?,'PUBLISHED',CURRENT_TIMESTAMP(6))",course,UUID.randomUUID().toString(),owner);
            jdbc.update("INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,description,cover_url,instructor_display_name,category_id,level,access_type) VALUES(?,?,1,'v1','PUBLISHED','t161_course_test','test','test','','teacher_test_161',?,'BEGINNER','PUBLIC')",version,course,category);
            jdbc.update("UPDATE course SET current_draft_version_id=?,published_version_id=? WHERE id=?",version,version,course);
            jdbc.update("INSERT INTO chapter(id,course_version_id,title,position) VALUES(?,?,'test',1)",chapter,version);
            jdbc.update("INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,content_revision_id) VALUES(?,?,'test_video_metadata','VIDEO',1,FALSE,?)",lesson,chapter,lesson);
            var command=new ProcessBuilder("cmd.exe","/c","mvn.cmd","-q","-Dmaven.repo.local="+root.resolve(".m2/repository"),"-f",root.resolve("java-platform/pom.xml").toString(),"-pl","learning-service","-am","-Dtest=T161LearningRealEnvironmentAcceptanceTest","-Dsurefire.failIfNoSpecifiedTests=false","test");
            command.directory(root.toFile());
            command.environment().put("T161_COURSE_URL","http://127.0.0.1:"+port);
            command.environment().put("T161_COURSE_ID",course);
            command.environment().put("T161_TEACHER_ID",owner);
            command.environment().put("T161_LESSON_ID",lesson);
            command.environment().put("COURSE_SERVICE_BASE_URL","http://127.0.0.1:"+port);
            command.redirectErrorStream(true).redirectOutput(log.toFile());
            child=command.start();
            assertThat(child.waitFor(Duration.ofMinutes(3).toMillis(),TimeUnit.MILLISECONDS))
                    .as("Learning real acceptance exceeded bounded runtime; inspect .m2/t16-1-learning-real.log").isTrue();
            assertThat(child.exitValue()).as("Learning real acceptance; inspect .m2/t16-1-learning-real.log").isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='CourseAvailabilityChanged' AND status='PUBLISHED'",Integer.class,course)).isEqualTo(4);
        } finally {
            if(child!=null && child.isAlive()) { child.destroyForcibly(); child.waitFor(10,TimeUnit.SECONDS); }
            // 精确 ID 清理按本域外键顺序进行；不创建数据库、不删除已有分类或人工课程。
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id=?",course);
            jdbc.update("DELETE FROM course_suspension_interval WHERE course_id=?",course);
            jdbc.update("DELETE FROM teacher_course_availability WHERE teacher_id=?",owner);
            jdbc.update("DELETE FROM lesson WHERE id=? AND chapter_id=?",lesson,chapter);
            jdbc.update("DELETE FROM chapter WHERE id=? AND course_version_id=?",chapter,version);
            jdbc.update("UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id=? AND owner_teacher_id=?",course,owner);
            jdbc.update("DELETE FROM course_version WHERE id=? AND course_id=?",version,course);
            jdbc.update("DELETE FROM course WHERE id=? AND owner_teacher_id=?",course,owner);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id=?",Integer.class,course)).isZero();
            assertThat(jdbc.queryForMap("SELECT * FROM course WHERE id=?",PROTECTED)).isEqualTo(before);
            System.out.println("T16.1 Course fixture exact-ID cleanup complete; protected course unchanged.");
        }
    }
}
