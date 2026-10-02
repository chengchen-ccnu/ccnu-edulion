package com.eduplatform.operation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.eduplatform.operation.hot.HotCourseModels.*;
import com.eduplatform.operation.hot.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/** 真实派生事务测试：领域HTTP用协议替身，验证排序/失败保留/重建并发；外部依赖另有显式验收。 */
@SpringBootTest
@ActiveProfiles("test")
class HotCourseProjectionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired HotCourseWorker worker;
    @Autowired HotCourseEvents events;
    @Autowired ObjectMapper mapper;
    @MockitoBean HotCourseSources sources;
    @MockitoBean HotCourseCache cache;
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    @BeforeEach void setup() {
        for(String table:List.of("hot_course_projection","hot_course_dirty","hot_course_consumed_event")) jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE hot_course_refresh_state SET rebuild_after='',last_completed_at=NULL,last_failure_at=NULL WHERE id=1");reset(sources,cache);
    }
    /** 高报名不能压过高学习人数；无学习近期课只补位NEW，超过六门真实截断。 */
    @Test void rankLexicographicallyAndLabelNew() {
        var cards=new ArrayList<SourceCard>();for(int n=0;n<8;n++) cards.add(card(n));
        when(sources.page("")).thenReturn(new SourcePage(cards,false));
        for(int n=0;n<8;n++) when(sources.learning(cards.get(n).courseId())).thenReturn(fact(cards.get(n).courseId(),n<2?2-n:0,n==1?1000000:1));
        worker.refresh();var board=org.mockito.ArgumentCaptor.forClass(Board.class);verify(cache).publish(board.capture());
        assertThat(board.getValue().items()).hasSize(6);
        assertThat(board.getValue().items().subList(0,2)).extracting(Card::courseId).containsExactly(cards.get(0).courseId(),cards.get(1).courseId());
        assertThat(board.getValue().items().subList(2,6)).extracting(Card::label).containsOnly("NEW");
    }
    @Test void emptyAndHiddenRemoveProjection() {
        SourceCard card=card(1);when(sources.page("")).thenReturn(new SourcePage(List.of(card),false));when(sources.learning(card.courseId())).thenReturn(fact(card.courseId(),1,1));worker.refresh();
        when(sources.card(card.courseId())).thenReturn(hidden(card.courseId()));events.consume(event(card.courseId(),id()));worker.refresh();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hot_course_projection",Long.class)).isZero();
        verify(cache).publish(argThat(board->"EMPTY".equals(board.status())));
    }
    /** 重投一个eventId只登记一次；旧失效信号读取当前报名数，源故障保留dirty并不发布假零。 */
    @Test void duplicatesOutOfOrderAndSourceFailure() {
        SourceCard card=card(1);String event=event(card.courseId(),id());events.consume(event);events.consume(event);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hot_course_consumed_event",Long.class)).isEqualTo(1);
        when(sources.card(card.courseId())).thenThrow(new IllegalStateException("source down"));
        assertThatThrownBy(worker::refresh).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hot_course_dirty",Long.class)).isEqualTo(1);verify(cache,never()).publish(any());
        doReturn(card).when(sources).card(card.courseId());when(sources.learning(card.courseId())).thenReturn(fact(card.courseId(),1,0));
        when(sources.page("")).thenReturn(new SourcePage(List.of(card),false));worker.refresh();
        events.consume(event(card.courseId(),id()));when(sources.learning(card.courseId())).thenReturn(fact(card.courseId(),1,7));worker.refresh();
        assertThat(jdbc.queryForObject("SELECT active_enrollments FROM hot_course_projection WHERE course_id=?",Long.class,card.courseId())).isEqualTo(7);
    }
    /** 源扫描停在旧快照期间提交的新失效信号必须排在锁后，随后刷新最新源，不能被重建DELETE吞掉。 */
    @Test void rebuildSerializesConcurrentSignals() throws Exception {
        SourceCard card=card(1);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(sources.page("")).thenAnswer(inv->{started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return new SourcePage(List.of(card),false);});
        AtomicReference<Learning> latest=new AtomicReference<>(fact(card.courseId(),1,1));
        when(sources.learning(card.courseId())).thenAnswer(inv->latest.get());when(sources.card(card.courseId())).thenReturn(card);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var rebuild=pool.submit(worker::refresh);assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var concurrent=pool.submit(()->events.consume(event(card.courseId(),id())));release.countDown();rebuild.get(10,TimeUnit.SECONDS);concurrent.get(10,TimeUnit.SECONDS);
            latest.set(fact(card.courseId(),3,4));worker.refresh();
            assertThat(jdbc.queryForObject("SELECT learners_7d FROM hot_course_projection WHERE course_id=?",Long.class,card.courseId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hot_course_dirty",Long.class)).isZero();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void expireProjectionAndCoverWithoutRefetchInReadModel() {
        SourceCard card=card(1);when(sources.page("")).thenReturn(new SourcePage(List.of(card),false));when(sources.learning(card.courseId())).thenReturn(fact(card.courseId(),1,1));worker.refresh();reset(cache);
        jdbc.update("UPDATE hot_course_projection SET window_end=?",java.sql.Date.valueOf(today.minusDays(1)));worker.refresh();verify(cache).invalidate();verify(cache,never()).publish(any());
        Card publicCard=new Card(card.courseId(),"课","", "teacher_test","00000000-0000-0000-0000-000000000002","BEGINNER","PUBLIC",Instant.now(),"HOT",1,1,id(),"READY","https://example.invalid/temporary",Instant.now().minusSeconds(1));
        assertThat(publicCard.at(Instant.now()).coverStatus()).isEqualTo("UNAVAILABLE");assertThat(publicCard.at(Instant.now()).coverUrl()).isNull();
    }
    private SourceCard card(int n) {return new SourceCard("00000000-0000-0000-0000-%012d".formatted(n+10),true,"测试课程","summary","teacher_test","00000000-0000-0000-0000-000000000002","BEGINNER","PUBLIC",Instant.now().minusSeconds(n),null);}
    private SourceCard hidden(String id) {return new SourceCard(id,false,null,null,null,null,null,null,null,null);}
    private Learning fact(String id,long count,long active) {return new Learning(id,count,active,today.minusDays(6),today);}
    private String event(String course,String id) {return mapper.writeValueAsString(Map.of("eventId",id,"eventType","CoursePortalChanged","eventVersion",1,"aggregateId",course,"occurredAt",Instant.now(),"traceId",id,"payload",Map.of("courseId",course)));}
    private String id() {return UUID.randomUUID().toString();}
}
