package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.*;
import com.eduplatform.learning.course.CourseTaskClock;
import com.eduplatform.learning.messaging.CourseAvailabilityRabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.progress.ProgressBufferWorker;
import com.eduplatform.learning.progress.RedisProgressBufferStore;
import com.eduplatform.learning.service.LearningAssessmentService;
import com.eduplatform.learning.web.LearningDtos;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Learning 真实 HTTP/MySQL/Redis/RabbitMQ 验收；Course 事实仅由 HTTP 获取，finally 精确清理本域数据。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "platform.learning.import.messaging-enabled=false",
        "platform.learning.course-content-messaging.enabled=false",
        "platform.learning.suspension-messaging.enabled=true",
        "platform.learning.progress-buffer.enabled=true",
        "platform.learning.progress-buffer.worker-delay=1h",
        "platform.learning.assessment-settlement-poll-delay=1h"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="T161_REAL_ACCEPTANCE_ENABLED",matches="true")
class T161LearningRealEnvironmentAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired LearningAssessmentService learning;
    @Autowired CourseTaskClock clock;
    @Autowired ProgressBufferWorker worker;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    final String course=System.getenv("T161_COURSE_ID"), teacher=System.getenv("T161_TEACHER_ID"), lesson=System.getenv("T161_LESSON_ID");
    final String learner=UUID.randomUUID().toString();
    final List<String> assessments=new ArrayList<>();
    final List<String> attempts=new ArrayList<>();
    final String invalidEventId=UUID.randomUUID().toString();
    boolean invalidEventPending;

    /** 两次真实暂停按 Course 区间精确补时；保存/提交/Worker/自动关闭争用同一课程锁，暂停不造缺交。 */
    @Test void liveCourseClockAndRedisGenerationAreEnforcedAcrossIndependentServices() throws Exception {
        assertThat(System.getenv("LEARNING_MYSQL_DATABASE")).isEqualTo("edu_platform_learning_dev");
        assertThat(System.getenv("LEARNING_REDIS_DATABASE")).isEqualTo("4");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("edu_platform_learning_dev");
        // 不领取人工演示或其他测试遗留的 dirty；存在既有缓冲时先停止，不触碰这些事实。
        assertThat(redis.opsForZSet().zCard("edu:learning:progress:dirty")).as("real acceptance requires no preexisting dirty progress").isZero();
        assertThat(deadLetterCount()).as("real acceptance preserves preexisting DLQ messages").isZero();
        String key=RedisProgressBufferStore.key(learner,course,lesson);
        try {
            learning.enroll(learner,course,"t161-real-enroll");
            String exam=task(LearningDtos.AssessmentType.EXAM);
            String homework=task(LearningDtos.AssessmentType.HOMEWORK);
            String practice=task(LearningDtos.AssessmentType.PRACTICE);
            var examDraft=learning.startAttempt(learner,exam,new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
            var homeworkDraft=learning.startAttempt(learner,homework,new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
            attempts.add(examDraft.id()); attempts.add(homeworkDraft.id());
            Instant originalDeadline=jdbc.queryForObject("SELECT deadline_at FROM assessment_attempt WHERE id=?",Timestamp.class,examDraft.id()).toInstant();
            Instant originalHomework=repository.assessment(homework).orElseThrow().lateUntil();
            assertThat(report("PAUSE",4).statusCode()).isEqualTo(200); // 保留可比较的 MySQL 基线。
            assertThat(report("HEARTBEAT",8).statusCode()).isEqualTo(200);
            assertThat(redis.hasKey(key)).isTrue();
            long persisted=repository.findProgress(learner,course,lesson).orElseThrow().watchedSeconds();
            String first=UUID.randomUUID().toString();
            change(first,1,true,200); change(first,1,true,200);
            assertThat(courseRequest("GET","/api/v1/courses/"+course,null,false).statusCode()).isEqualTo(409);
            assertThat(ownerMediaAccess().statusCode()).isEqualTo(409);
            assertThat(courseRequest("POST","/api/v1/courses/"+course+"/lessons/"+lesson+"/resources/"+UUID.randomUUID()+"/playback-grants",null,false).statusCode()).isEqualTo(409);
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM course_availability_consumed_event WHERE course_id=?",Integer.class,course)>=1);
            // 并发暂停后请求均须拒绝；后台 Worker 则 ACK 舍弃旧缓冲，不物化暂停期间增量。
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try {
                List<Callable<Integer>> operations=List.of(
                        () -> learningRequest("PUT","/api/v1/learning/attempts/"+examDraft.id()+"/draft",draftCommand(examDraft.revision())).statusCode(),
                        () -> learningRequest("POST","/api/v1/learning/attempts/"+examDraft.id()+"/submission",draftCommand(examDraft.revision())).statusCode(),
                        () -> { worker.flushDue(); return 409; },
                        () -> { jdbc.update("UPDATE assessment SET closes_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),exam); learning.autoCloseAssessment(exam); return 409; });
                for(var result:pool.invokeAll(operations)) assertThat(result.get()).isEqualTo(409);
            } finally { pool.shutdownNow(); }
            assertThat(report("HEARTBEAT",9).statusCode()).isEqualTo(409);
            assertThat(learningRequest("GET","/api/v1/learning/attempts/"+examDraft.id()+"/draft",null).statusCode()).isEqualTo(409);
            assertThat(learningRequest("POST","/api/v1/learning/assessments/"+practice+"/attempts",Map.of("clientRequestId",UUID.randomUUID())).statusCode()).isEqualTo(409);
            assertThat(repository.findProgress(learner,course,lesson).orElseThrow().watchedSeconds()).isEqualTo(persisted);
            assertThat(redis.hasKey(key)).isFalse();
            assertThat(repository.assessment(exam).orElseThrow().closeStatus()).isEqualTo(LearningDtos.AssessmentCloseStatus.OPEN);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id=? AND submission_status='MISSING'",Integer.class,exam)).isZero();
            jdbc.update("UPDATE assessment SET closes_at=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(3600)),exam);
            TimeUnit.MILLISECONDS.sleep(200);
            change(first,2,false,200);
            await(() -> jdbc.queryForObject("SELECT applied_sequence FROM learning_course_clock WHERE course_id=?",Long.class,course)>=1);
            String second=UUID.randomUUID().toString();
            change(second,3,true,200); change(first,2,false,200); // 旧恢复不能覆盖新暂停。
            assertThat(learningRequest("GET","/api/v1/learning/attempts/"+examDraft.id()+"/draft",null).statusCode()).isEqualTo(409);
            change(UUID.randomUUID().toString(),4,false,409); // 不同周期恢复被拒绝。
            TimeUnit.MILLISECONDS.sleep(200);
            change(second,4,false,200);
            await(() -> jdbc.queryForObject("SELECT applied_sequence FROM learning_course_clock WHERE course_id=?",Long.class,course)>=3);
            assertThat(ownerMediaAccess().statusCode()).isEqualTo(204);
            JsonNode fact=json.readTree(courseRequest("GET","/internal/v1/courses/"+course+"/availability",null,true).body());
            long micros=0;
            for(JsonNode interval:fact.path("intervals")) micros+=Duration.between(Instant.parse(interval.path("suspendedAt").asText()),Instant.parse(interval.path("resumedAt").asText())).toNanos()/1000;
            assertThat(learning.getDraft(learner,examDraft.id()).deadlineAt()).isEqualTo(originalDeadline.plusNanos(micros*1000));
            assertThat(repository.assessment(homework).orElseThrow().lateUntil()).isEqualTo(originalHomework.plusNanos(micros*1000));
            assertThat(repository.assessment(practice).orElseThrow().revision()).isEqualTo(1);
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM course_availability_consumed_event WHERE course_id=?",Integer.class,course)==4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_course_clock_interval WHERE course_id=?",Integer.class,course)).isEqualTo(2);
            // Broker 实际重投同 eventId 与迟到的旧暂停提示；权威状态与两次补时不被覆盖。
            String delayedId=UUID.randomUUID().toString();
            var envelope=json.createObjectNode();
            envelope.put("eventId",delayedId).put("eventType","CourseAvailabilityChanged").put("eventVersion",1)
                    .put("aggregateId",course).put("occurredAt",Instant.now().toString()).put("traceId","t161-reordered-event");
            var payload=envelope.putObject("payload");
            payload.put("courseId",course).put("suspensionId",first).put("availabilityRevision",1).put("paused",true)
                    .put("suspendedAt",fact.path("intervals").get(0).path("suspendedAt").asText()).putNull("resumedAt");
            String delayed=json.writeValueAsString(envelope);
            rabbit.convertAndSend(CourseAvailabilityRabbitConfig.EXCHANGE,CourseAvailabilityRabbitConfig.ROUTING_KEY,delayed);
            rabbit.convertAndSend(CourseAvailabilityRabbitConfig.EXCHANGE,CourseAvailabilityRabbitConfig.ROUTING_KEY,delayed);
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM course_availability_consumed_event WHERE course_id=?",Integer.class,course)==5);
            assertThat(learning.getDraft(learner,examDraft.id()).deadlineAt()).isEqualTo(originalDeadline.plusNanos(micros*1000));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_course_clock_interval WHERE course_id=?",Integer.class,course)).isEqualTo(2);
            // 不支持的事件版本必须有限重试后进入 DLQ；只 ACK 本轮精确 eventId。
            envelope.put("eventId",invalidEventId).put("eventVersion",2);
            invalidEventPending=true;
            rabbit.convertAndSend(CourseAvailabilityRabbitConfig.EXCHANGE,CourseAvailabilityRabbitConfig.ROUTING_KEY,json.writeValueAsString(envelope));
            await(() -> deadLetterCount()>0);
            assertThat(ackExactDeadLetter()).as("only this acceptance event may be acknowledged").isTrue();
            invalidEventPending=false;
            TimeUnit.MILLISECONDS.sleep(1100);
            assertThat(report("HEARTBEAT",1).statusCode()).isEqualTo(200);
            assertThat(redis.opsForHash().get(key,"availabilityRevision")).isEqualTo("4");
            worker.flushDue();
            assertThat(repository.findProgress(learner,course,lesson).orElseThrow().watchedSeconds()).isEqualTo(persisted+1);
            System.out.println("T16.1 independent Course/Learning HTTP + MySQL + Redis + confirmed RabbitMQ acceptance passed.");
        } finally { cleanup(key); }
    }

    /** 使用既有 Repository 创建本域任务/名单与草稿；没有创建账号、媒体对象或其他领域的表。 */
    private String task(LearningDtos.AssessmentType type) {
        String id=UUID.randomUUID().toString(); assessments.add(id);
        Instant now=Instant.now();
        repository.insertAssessment(new AssessmentRow(id,teacher,course,lesson,"t161_task_test","test",type,
                LearningDtos.AssessmentStatus.PUBLISHED,LearningDtos.AnswerPublicationPolicy.NEVER,
                type==LearningDtos.AssessmentType.PRACTICE?null:now.minusSeconds(10),type==LearningDtos.AssessmentType.PRACTICE?null:now.plusSeconds(3600),
                type==LearningDtos.AssessmentType.EXAM?600:null,1,now.minusSeconds(1),null,now,now));
        if(type==LearningDtos.AssessmentType.HOMEWORK)jdbc.update("UPDATE assessment SET late_policy='ALLOW_UNTIL',late_until=? WHERE id=?",Timestamp.from(now.plusSeconds(7200)),id);
        repository.ensureCourseAssignment(UUID.randomUUID().toString(),id,course,now);
        repository.insertParticipant(UUID.randomUUID().toString(),id,learner,now);
        return id;
    }

    private Map<String,Object> draftCommand(long revision) {return Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",revision,"answers",List.of());}
    private HttpResponse<String> report(String type,int watched) throws Exception {
        return learningRequest("POST","/api/v1/learning/courses/"+course+"/lessons/"+lesson+"/progress-events",Map.of("clientEventId",UUID.randomUUID(),"contentRevisionId",lesson,"occurredAt",Instant.now().toString(),"positionSeconds",20,"watchedSeconds",watched,"completed",false,"eventType",type));
    }
    private void change(String cycle,long revision,boolean paused,int expected) throws Exception {
        HttpResponse<String> result=courseRequest("PUT","/internal/v1/teachers/"+teacher+"/course-availability",Map.of("suspensionId",cycle,"accessRevision",revision,"paused",paused),true);
        assertThat(result.statusCode()).as("Course command HTTP status").isEqualTo(expected);
    }
    private HttpResponse<String> learningRequest(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-User-Id",learner).header("X-User-Roles","STUDENT").header("X-User-Permissions","learning:access");
        return send(builder,method,body);
    }
    private HttpResponse<String> courseRequest(String method,String path,Object body,boolean internal) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(System.getenv("T161_COURSE_URL")+path)).header("X-User-Id",learner);
        if(internal)builder.header("X-Internal-Caller",method.equals("PUT")?"iam-service":"learning-service").header("X-Internal-Service-Token",System.getenv(method.equals("PUT")?"COURSE_SUSPENSION_IAM_TOKEN":"COURSE_MEDIA_AUTH_SECRET"));
        return send(builder,method,body);
    }
    private HttpResponse<String> ownerMediaAccess() throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(System.getenv("T161_COURSE_URL")+"/internal/v1/teachers/"+teacher+"/course-media-access"))
                .header("X-Internal-Caller","media-service").header("X-Internal-Service-Token",System.getenv("COURSE_MEDIA_AUTH_SECRET"));
        return send(builder,"GET",null);
    }
    private HttpResponse<String> send(HttpRequest.Builder builder,String method,Object body) throws Exception {
        builder.timeout(Duration.ofSeconds(10)).header("X-Trace-Id","t161-real-acceptance").header("Content-Type","application/json");
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()<until)TimeUnit.MILLISECONDS.sleep(100);
        assertThat(condition.getAsBoolean()).as("bounded RabbitMQ/clock convergence").isTrue();
    }

    private int deadLetterCount() {
        return rabbit.execute(channel -> channel.queueDeclarePassive(CourseAvailabilityRabbitConfig.DLQ).getMessageCount());
    }

    /** 检查内容后才确认；遇到非本轮消息立即 requeue，不能清共享死信队列。 */
    private boolean ackExactDeadLetter() {
        return Boolean.TRUE.equals(rabbit.execute(channel -> {
            var message=channel.basicGet(CourseAvailabilityRabbitConfig.DLQ,false);
            if(message==null)return false;
            if(!new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8).contains(invalidEventId)) {
                channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true); return false;
            }
            assertThat(message.getProps().getHeaders()).containsKey("x-death");
            channel.basicAck(message.getEnvelope().getDeliveryTag(),false); return true;
        }));
    }

    /** 精确课程/作答 ID 清理；共享 Redis dirty 集合只移除本轮成员，不清空数据库或队列。 */
    private void cleanup(String key) {
        if(invalidEventPending) {
            try { await(() -> deadLetterCount()>0); assertThat(ackExactDeadLetter()).isTrue(); }
            catch(Exception failure) { throw new AssertionError("精确事件 DLQ 清理失败，保留其他消息并报告",failure); }
        }
        redis.delete(key); redis.opsForZSet().remove("edu:learning:progress:dirty",key);
        for(String id:attempts)jdbc.update("DELETE FROM assessment_answer WHERE attempt_id=?",id);
        for(String id:assessments) {
            jdbc.update("DELETE FROM assessment_participant WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM assessment_attempt WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM assessment_question WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM assessment WHERE id=? AND course_id=?",id,course);
        }
        for(String table:List.of("lesson_learning_day","learning_activity_day","lesson_progress_event","lesson_progress","published_lesson_content_projection","published_course_content_projection","learning_course_clock_interval","course_availability_consumed_event","learning_course_clock","course_student_number","course_student_number_counter"))jdbc.update("DELETE FROM "+table+" WHERE course_id=?",course);
        for(String id:jdbc.query("SELECT id FROM enrollment WHERE learner_id=? AND course_id=?",(rs,n)->rs.getString(1),learner,course))jdbc.update("DELETE FROM learning_outbox_event WHERE aggregate_id=?",id);
        jdbc.update("DELETE FROM enrollment WHERE learner_id=? AND course_id=?",learner,course);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE course_id=?",Integer.class,course)).isZero();
        assertThat(redis.hasKey(key)).isFalse();
        System.out.println("T16.1 Learning fixture exact-ID cleanup complete.");
    }
}
