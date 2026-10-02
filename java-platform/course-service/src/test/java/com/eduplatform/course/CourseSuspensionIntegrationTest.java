package com.eduplatform.course;

import static org.assertj.core.api.Assertions.*;

import com.eduplatform.course.service.CourseCatalogService;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.service.CourseSuspensionService;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** 独立 H2 验证 owner 命令、乱序水位、公开错误和媒体门禁；只创建无正文的合成课程。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:t161_course;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.course.suspension.iam-token=t161-test-iam-command-secret-at-least-32",
        "platform.course.media.service-authorization.secret=t161-test-learning-read-secret-at-least-32"})
@ActiveProfiles("test")
class CourseSuspensionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseSuspensionService suspension;
    @Autowired CourseCatalogService catalog;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final String owner=UUID.randomUUID().toString();
    final String published=UUID.randomUUID().toString();
    final String draft=UUID.randomUUID().toString();
    final String withdrawn=UUID.randomUUID().toString();

    @BeforeEach void seed() {
        for (String id : new String[]{published,draft,withdrawn}) jdbc.update(
                "INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status) VALUES(?,?,?,?)",
                id,UUID.randomUUID().toString(),owner,id.equals(published)?"PUBLISHED":id.equals(draft)?"DRAFT":"UNPUBLISHED");
    }

    /** 影响预览只包含当前发布课程；恢复仅匹配原周期，缺周期/错误内部身份明确拒绝。 */
    @Test void impactMatchesOnlyItsCycleAndAuthenticatesCaller()throws Exception {
        assertThat(suspension.impact(owner,true,null).courseIds()).containsExactly(published);
        String cycle=UUID.randomUUID().toString();suspension.change(owner,cycle,1,true,"t163-impact");
        assertThat(suspension.impact(owner,false,cycle).courseIds()).containsExactly(published);
        assertThat(suspension.impact(owner,false,UUID.randomUUID().toString()).courseIds()).isEmpty();
        assertThat(request("GET","/internal/v1/teachers/"+owner+"/availability-impact?paused=false",null,"iam-service","t161-test-iam-command-secret-at-least-32").statusCode()).isEqualTo(422);
        assertThat(request("GET","/internal/v1/teachers/"+owner+"/availability-impact?paused=true",null,"learning-service","t161-test-iam-command-secret-at-least-32").statusCode()).isEqualTo(401);
    }

    /** 超过1000门不能静默截断影响并误导管理员确认；独立H2里建立有界元数据夹具。 */
    @Test void impactLimitFailsInsteadOfTruncating() {
        for(int n=0;n<1000;n++)jdbc.update("INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status) VALUES(?,?,?,'PUBLISHED')",
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),owner);
        assertThatThrownBy(()->suspension.impact(owner,true,null)).isInstanceOfSatisfying(CourseException.class,
                failure->assertThat(failure.status().value()).isEqualTo(503));
    }

    /** 重放、旧恢复与不匹配周期不能复活课程；草稿和主动下架始终不变，Outbox 与状态同事务。 */
    @Test void commandsAreMonotonicAndRestoreOnlyTheirOwnCycle() {
        String first=UUID.randomUUID().toString(), second=UUID.randomUUID().toString();
        assertThat(suspension.change(owner,first,1,true,"t161-pause").affectedCourses()).isEqualTo(1);
        assertThat(suspension.change(owner,first,1,true,"t161-replay").affectedCourses()).isEqualTo(1);
        assertThat(suspension.availability(published,0).paused()).isTrue();
        assertThat(suspension.availability(draft,0).paused()).isFalse();
        assertThat(suspension.availability(withdrawn,0).paused()).isFalse();
        assertThatThrownBy(()->suspension.change(owner,second,2,false,"t161-conflict"))
                .isInstanceOfSatisfying(CourseException.class,e->assertThat(e.status().value()).isEqualTo(409));
        suspension.change(owner,first,2,false,"t161-resume");
        suspension.change(owner,first,1,true,"t161-stale");
        assertThat(suspension.availability(published,0).paused()).isFalse();
        suspension.change(owner,second,3,true,"t161-second");
        suspension.change(owner,first,2,false,"t161-old-resume");
        assertThat(suspension.availability(published,0).paused()).isTrue();
        suspension.change(owner,second,4,false,"t161-second-resume");
        assertThat(suspension.availability(published,0).intervals()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='CourseAvailabilityChanged'",Integer.class,published)).isEqualTo(4);
        assertThatThrownBy(()->suspension.change(owner,second,5,true,"t161-reused-cycle"))
                .isInstanceOf(CourseException.class);
    }

    /** 内部写入口只接受 IAM 专用 token；公开深链与媒体申请统一安全 409，旧 URL 不参与吊销。 */
    @Test void realHttpCallerAuthenticationAndSafeUnavailableProjection() throws Exception {
        String cycle=UUID.randomUUID().toString();
        String command="{\"suspensionId\":\""+cycle+"\",\"accessRevision\":1,\"paused\":true}";
        assertThat(request("PUT","/internal/v1/teachers/"+owner+"/course-availability",command,"iam-service","wrong").statusCode()).isEqualTo(401);
        assertThat(request("PUT","/internal/v1/teachers/"+owner+"/course-availability",command,"learning-service","t161-test-iam-command-secret-at-least-32").statusCode()).isEqualTo(401);
        assertThat(request("PUT","/internal/v1/teachers/not-a-uuid/course-availability",command,"iam-service","t161-test-iam-command-secret-at-least-32").statusCode()).isEqualTo(422);
        assertThat(request("GET","/internal/v1/courses/"+published+"/availability?afterSequence=-1",null,"learning-service","t161-test-learning-read-secret-at-least-32").statusCode()).isEqualTo(422);
        assertThat(request("PUT","/internal/v1/teachers/"+owner+"/course-availability",command,"iam-service","t161-test-iam-command-secret-at-least-32").statusCode()).isEqualTo(200);
        var unavailable=request("GET","/api/v1/courses/"+published,null,null,null);
        assertThat(unavailable.statusCode()).isEqualTo(409);
        var body=json.readTree(unavailable.body());
        assertThat(body.path("message").asText()).isEqualTo("课程暂不可用");
        assertThat(body.path("supportEmail").asText()).isEqualTo("support@example.invalid");
        assertThat(body.has("title")).isFalse();
        assertThat(body.toString()).doesNotContain(cycle,owner,"撤权");
        assertThatThrownBy(()->catalog.createPublishedPlaybackGrant(published,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"t161-media"))
                .isInstanceOfSatisfying(CourseException.class,e->assertThat(e.code()).isEqualTo("COURSE.SUSPENDED"));
        assertThat(request("GET","/internal/v1/courses/"+UUID.randomUUID()+"/availability",null,"learning-service","t161-test-learning-read-secret-at-least-32").statusCode()).isEqualTo(404);
        var fact=request("GET","/internal/v1/courses/"+published+"/availability",null,"learning-service","t161-test-learning-read-secret-at-least-32");
        assertThat(fact.statusCode()).isEqualTo(200);
        assertThat(json.readTree(fact.body()).path("intervals").get(0).has("resumedAt")).isTrue();
        assertThat(json.readTree(fact.body()).path("intervals").get(0).path("resumedAt").isNull()).isTrue();
        assertThat(request("GET","/internal/v1/teachers/"+owner+"/course-media-access",null,"learning-service","t161-test-learning-read-secret-at-least-32").statusCode()).isEqualTo(401);
        assertThat(request("GET","/internal/v1/teachers/"+owner+"/course-media-access",null,"media-service","t161-test-learning-read-secret-at-least-32").statusCode()).isEqualTo(409);
    }

    private HttpResponse<String> request(String method,String path,String body,String caller,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(caller!=null)builder.header("X-Internal-Caller",caller).header("X-Internal-Service-Token",token);
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    /** 超过单页上限仍按区间开始水位继续，恢复补齐的 null 键不受全局展示序列化设置影响。 */
    @Test void intervalHistoryIsBoundedAndKeepsRequiredNullInOutbox() {
        for(int n=0;n<101;n++) {
            String cycle=UUID.randomUUID().toString();
            suspension.change(owner,cycle,2L*n+1,true,"t161-page-pause");
            if(n==0) {
                String event=jdbc.queryForObject("SELECT payload_json FROM outbox_event WHERE aggregate_id=?",String.class,published);
                assertThat(json.readTree(event).path("payload").has("resumedAt")).isTrue();
            }
            suspension.change(owner,cycle,2L*n+2,false,"t161-page-resume");
        }
        var first=suspension.availability(published,0);
        assertThat(first.intervals()).hasSize(100);
        assertThat(first.hasMore()).isTrue();
        var last=suspension.availability(published,first.intervals().get(99).sequence());
        assertThat(last.intervals()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.availabilityRevision()).isEqualTo(202);
    }
}
