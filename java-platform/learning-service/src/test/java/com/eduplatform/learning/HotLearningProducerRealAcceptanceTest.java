package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.*;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.web.HotLearningSourceController;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** 真实MySQL进度Worker→日资格→既有Outbox/confirm→独立Rabbit队列，不逐心跳发热度消息。 */
@SpringBootTest(properties={"platform.learning.import.consumer-enabled=false","platform.learning.suspension-messaging.enabled=false","platform.learning.course-content-messaging.enabled=false","platform.learning.progress-buffer.enabled=false"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="T16A_REAL_ACCEPTANCE_ENABLED",matches="true")
class HotLearningProducerRealAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired HotLearningSourceController source;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper json;
    /** 同日重复版本与跨日进度仅两条资格事件；再四线程争抢第三日只新增一条，七日同人仍1，finally精确清理。 */
    @Test void persistedProgressQualifiesOncePerStudentCourseDay() throws Exception {
        assertThat(System.getenv("LEARNING_MYSQL_DATABASE")).isEqualTo("edu_platform_learning_dev");
        String course=id(),student=id(),lesson=id(),content=id();String queue=System.getenv("OPERATION_HOT_QUEUE")+".producer";
        var broker=new RabbitAdmin(rabbit);org.springframework.amqp.core.Queue q=QueueBuilder.durable(queue).build();TopicExchange exchange=new TopicExchange("edu.learning.hot-course.events.v1",true,false);
        broker.declareExchange(exchange);broker.declareQueue(q);broker.declareBinding(BindingBuilder.bind(q).to(exchange).with("learning.course.day-qualified.v1"));
        List<String> ownEvents=new ArrayList<>();LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));Instant now=Instant.now();
        try {
            repository.applyBufferedProgress(student,course,lesson,content,5,5,false,now.minus(Duration.ofDays(1)),1,now,Map.of(today.minusDays(1),5L),today.minusDays(1));
            repository.applyBufferedProgress(student,course,lesson,content,10,10,false,now,2,now,Map.of(today,5L),null);
            repository.applyBufferedProgress(student,course,lesson,content,10,10,false,now,2,now,Map.of(today,5L),null);
            repository.applyBufferedProgress(student,course,lesson,content,15,15,false,now.plusSeconds(1),3,now,Map.of(today,5L),null);
            ownEvents.addAll(jdbc.queryForList("SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?",String.class,course));assertThat(ownEvents).hasSize(2);
            assertThat(source.source(UUID.fromString(course),"operation-service",System.getenv("OPERATION_INTERNAL_SERVICE_TOKEN")).learners7d()).isEqualTo(1);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND status='PUBLISHED'",Long.class,course)!=2)Thread.sleep(100);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND status='PUBLISHED'",Long.class,course)).isEqualTo(2);
            var seen=new HashSet<String>();for(int n=0;n<10;n++) {Message message=rabbit.receive(queue);if(message==null)break;String event=json.readTree(message.getBody()).path("eventId").asText();assertThat(ownEvents).contains(event);seen.add(event);}
            assertThat(seen).containsExactlyInAnyOrderElementsOf(ownEvents);
            // 四个线程同时首次认领另一真实日；唯一键竞争后当前读只允许一个Outbox，跨日仍同人1。
            var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var start=new java.util.concurrent.CountDownLatch(1);var tasks=new ArrayList<java.util.concurrent.Future<?>>();
                for(int n=0;n<4;n++)tasks.add(pool.submit(()->{try {start.await();repository.qualifyHotLearningDay(course,student,today.minusDays(2),now.minus(Duration.ofDays(2)));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
                start.countDown();for(var task:tasks)task.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=?",Long.class,course)).isEqualTo(3);
                assertThat(source.source(UUID.fromString(course),"operation-service",System.getenv("OPERATION_INTERNAL_SERVICE_TOKEN")).learners7d()).isEqualTo(1);
                deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND status='PUBLISHED'",Long.class,course)!=3)Thread.sleep(100);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND status='PUBLISHED'",Long.class,course)).isEqualTo(3);
                ownEvents.clear();ownEvents.addAll(jdbc.queryForList("SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?",String.class,course));
                for(int n=0;n<10;n++){Message message=rabbit.receive(queue);if(message==null)break;String event=json.readTree(message.getBody()).path("eventId").asText();assertThat(ownEvents).contains(event);seen.add(event);}
                assertThat(seen).containsExactlyInAnyOrderElementsOf(ownEvents);
            } finally {pool.shutdownNow();}
            System.out.println("T16A real Learning persisted progress→daily qualification→Outbox confirm→Rabbit and 4-way qualification race passed (3 days, 1 distinct learner).");
        } finally {
            for(String table:List.of("hot_learning_day","lesson_learning_day","lesson_progress"))jdbc.update("DELETE FROM "+table+" WHERE course_id=?",course);
            jdbc.update("DELETE FROM learning_outbox_event WHERE aggregate_id=?",course);while(rabbit.receive(queue)!=null){}broker.deleteQueue(queue);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hot_learning_day WHERE course_id=?",Long.class,course)).isZero();
        }
    }
    private String id() {return UUID.randomUUID().toString();}
}
