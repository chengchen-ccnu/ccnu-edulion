package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.SecurityDigests;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 专用开发库真实Gateway→IAM→MySQL恢复验收；Redis会话保持开启，无Mock领域端口。
 * 两管理员和两学生是可精确重建的合成Fixture；命令经正式HTTP产生，不伪造审核或课程事实。
 * 临时回执丢弃代理仅切断客户端响应，Gateway/IAM提交及读取仍为真实服务。
 */
@SpringBootTest(properties={"platform.iam.seed.enabled=false", "platform.iam.review.messaging-enabled=false",
        "platform.iam.review.retention-enabled=false", "platform.iam.account-permissions.worker-enabled=false"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="T1631_RECOVERY_REAL_ACCEPTANCE",matches="true")
class T1631RecoveryQueryRealAcceptanceTest {
    private static final List<String> IDS=List.of("16310000-0000-4000-8000-000000000001",
            "16310000-0000-4000-8000-000000000002","16310000-0000-4000-8000-000000000003",
            "16310000-0000-4000-8000-000000000004");
    private static final String BASE="http://127.0.0.1:18080";
    @Autowired JdbcTemplate jdbc;
    @Autowired IamMapper users;
    @Autowired PasswordEncoder encoder;
    @Autowired StringRedisTemplate redis;
    @Autowired IamProperties properties;
    @Autowired SecurityDigests digests;
    @Autowired ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 在建立Spring连接前拒绝非指定开发Schema；凭据只从安全包装器进程环境获得。 */
    @DynamicPropertySource static void dedicatedSchema(DynamicPropertyRegistry ignored) {
        if(!"edu_platform_iam_dev".equals(System.getenv("IAM_MYSQL_DATABASE")))
            throw new IllegalStateException("Dedicated IAM development schema required");
    }

    /** 正式BAN提交后代理丢回执；另一管理员仅凭userId恢复，验证只读、排序、隔离和当前会话授权。 */
    @Test void recoverAfterLostHttpResponseAndCleanExactFixtures() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("edu_platform_iam_dev");
        cleanup();
        if("true".equals(System.getenv("T1631_CLEANUP_ONLY")))return;
        var existingUsers=jdbc.queryForList("SELECT id,status,revision,session_version FROM `user` ORDER BY id");
        var existingOutbox=jdbc.queryForList("SELECT event_id,status FROM iam_outbox_event ORDER BY event_id");
        String password=UUID.randomUUID()+"aA1!";
        try {
            for(int i=0;i<IDS.size();i++) {
                String id=IDS.get(i);
                users.insertUser(id,account(id),"T1631 recovery test",encoder.encode(password));
                users.assignRole(id,i<2?"ADMIN":"STUDENT");
                users.insertIdentity(UUID.randomUUID().toString(),id,"EMAIL",email(id),Instant.now());
            }
            String adminA=login(IDS.get(0),password),adminB=login(IDS.get(1),password),student=login(IDS.get(3),password);
            String target=IDS.get(2),other=IDS.get(3),latest=latestPath(target);
            expect("GET",latest,adminA,null,204);
            expect("GET",latestPath(UUID.randomUUID().toString()),adminB,null,404);
            expect("GET",latest,null,null,401);expect("GET",latest,student,null,403);
            expect("GET",latestPath("invalid"),adminB,null,422);

            var preview=json.readTree(expect("POST",userPath(target)+"/permission-previews",adminA,Map.of("action","BAN"),201).body());
            var request=commandRequest(preview,"BAN");
            loseResponseAfterGatewayCommit(userPath(target)+"/permission-commands",adminA,request);
            // 恢复逻辑只使用目标账号，不读取原请求ID或命令表来定位commandId。
            var before=targetFacts(target);
            JsonNode recovered=json.readTree(expect("GET",latest,adminB,null,200).body());
            String commandId=recovered.get("commandId").asText();
            assertThat(recovered.get("coordinationStatus").asText()).isEqualTo("NOT_REQUIRED");
            assertThat(recovered.get("replayed").asBoolean()).isFalse();
            assertThat(recovered.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                    "commandId","userId","action","decidedRevision","accessRevision","coordinationStatus","attempts","maxAttempts","affectedCourses","replayed");
            assertThat(json.readTree(expect("GET","/api/v1/admin/permission-commands/"+commandId,adminB,null,200).body())).isEqualTo(recovered);
            expect("GET",latest,adminA,null,200);
            assertThat(targetFacts(target).equals(before)).as("GET preserves fixture rows and budgets").isTrue();
            expect("GET",latestPath(other),adminB,null,204);
            JsonNode next=change(target,"UNBAN",adminB);
            change(other,"BAN",adminA);
            assertThat(json.readTree(expect("GET",latest,adminB,null,200).body()).get("commandId").asText())
                    .isEqualTo(next.get("commandId").asText());

            // 正式封禁/解封临时管理员：旧JWT不会因恢复ACTIVE而重新有效，不操作任何原管理员。
            change(IDS.get(0),"BAN",adminB);
            expect("GET",latest,adminA,null,503); // 正式撤销删除Redis版本，Gateway安全拒绝。
            change(IDS.get(0),"UNBAN",adminB);
            String currentA=login(IDS.get(0),password);
            expect("GET",latest,adminA,null,401);expect("GET",latest,currentA,null,200);
            // 在专用Fixture中移除角色但保留JWT及会话版本，证明IAM仍核验当前数据库身份。
            jdbc.update("DELETE FROM user_role WHERE user_id=?",IDS.get(0));users.assignRole(IDS.get(0),"STUDENT");
            expect("GET",latest,currentA,null,403);
        } finally {
            cleanup();
            assertThat(jdbc.queryForList("SELECT id,status,revision,session_version FROM `user` ORDER BY id")).isEqualTo(existingUsers);
            assertThat(jdbc.queryForList("SELECT event_id,status FROM iam_outbox_event ORDER BY event_id")).isEqualTo(existingOutbox);
        }
    }

    /** 仅转发一次POST到真实Gateway，等202提交确认后关闭socket而不向调用方发送任何HTTP响应。 */
    private void loseResponseAfterGatewayCommit(String path,String token,Map<String,Object> body) throws Exception {
        var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger upstream=new AtomicInteger();
        proxy.createContext("/drop",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();
                upstream.set(send("POST",path,token,body).statusCode());
            }catch(Exception failure){upstream.set(-1);}finally{exchange.close();}
        });
        proxy.start();
        try {
            boolean lost=false;
            try {
                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/drop"))
                        .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString("submit")).build(),HttpResponse.BodyHandlers.discarding());
            }catch(java.io.IOException expected){lost=true;}
            assertThat(lost).isTrue();assertThat(upstream.get()).isEqualTo(202);
        }finally{proxy.stop(0);}
    }

    /** 读取本例目标的所有权限写事实及预算以比较GET前后；不读取其他领域或输出原始内容。 */
    private List<?> targetFacts(String id) {
        return List.of(jdbc.queryForList("SELECT * FROM account_permission_command WHERE user_id=? ORDER BY id",id),
                jdbc.queryForList("SELECT * FROM account_permission_preview WHERE user_id=? ORDER BY id",id),
                jdbc.queryForList("SELECT * FROM account_permission_retry WHERE command_id IN(SELECT id FROM account_permission_command WHERE user_id=?) ORDER BY id",id),
                jdbc.queryForList("SELECT * FROM account_security_audit WHERE user_id=? ORDER BY id",id),
                jdbc.queryForList("SELECT * FROM iam_outbox_event WHERE aggregate_id=? ORDER BY event_id",id),
                jdbc.queryForList("SELECT * FROM `user` WHERE id=?",id));
    }
    private String login(String id,String password) throws Exception {
        return json.readTree(expect("POST","/api/v1/auth/login",null,Map.of("identity",email(id),"password",password),200).body())
                .get("accessToken").asText();
    }
    private JsonNode change(String target,String action,String token) throws Exception {
        var preview=json.readTree(expect("POST",userPath(target)+"/permission-previews",token,Map.of("action",action),201).body());
        return json.readTree(expect("POST",userPath(target)+"/permission-commands",token,commandRequest(preview,action),202).body());
    }
    private Map<String,Object> commandRequest(JsonNode preview,String action) {
        return Map.of("clientRequestId",UUID.randomUUID().toString(),"previewId",preview.get("previewId").asText(),
                "expectedRevision",preview.get("expectedRevision").asLong(),"action",action,
                "reason","合成账号恢复查询验收明确确认无课程影响","confirmed",true);
    }
    /** 断言只报告方法/路径/状态，不在失败消息输出Token、Cookie、登录请求或原始响应正文。 */
    private HttpResponse<String> expect(String method,String path,String token,Object body,int status) throws Exception {
        var response=send(method,path,token,body);
        assertThat(response.statusCode()).as("%s %s",method,path).isEqualTo(status);
        return response;
    }
    /** 输入Gateway相对路径、可空Bearer及正文，发送有15秒超时的真实HTTP；Token只驻内存，不写日志。 */
    private HttpResponse<String> send(String method,String path,String token,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    /**
     * 可重复清理固定四个UUID；先核对技术标识，冲突即停止。只删这些账号的本域Fixture和确切Redis键。
     * Worker/Outbox在脚本中关闭，不发布临时事件；历史Outbox、原管理员、其他域均不修改。
     */
    private void cleanup() {
        for(String id:IDS) {
            var existing=jdbc.queryForList("SELECT account FROM `user` WHERE id=?",String.class,id);
            if(!existing.isEmpty()&&!existing.equals(List.of(account(id))))throw new IllegalStateException("Fixture UUID collision; cleanup refused");
        }
        for(String id:IDS) {
            jdbc.update("DELETE FROM account_permission_retry WHERE command_id IN(SELECT id FROM account_permission_command WHERE user_id=?)",id);
            jdbc.update("DELETE FROM account_permission_command WHERE user_id=?",id);
            jdbc.update("DELETE FROM account_permission_preview WHERE user_id=?",id);
            jdbc.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?",id);
            jdbc.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?",id,id);
            jdbc.update("DELETE FROM login_audit WHERE user_id=?",id);
            jdbc.update("DELETE FROM refresh_session WHERE user_id=?",id);
            jdbc.update("DELETE FROM user_identity WHERE user_id=?",id);
            jdbc.update("DELETE FROM user_role WHERE user_id=?",id);
            jdbc.update("DELETE FROM `user` WHERE id=?",id);
            String prefix=properties.sessionCache().keyPrefix().replace(":session-version","");
            String digest=digests.digest("login-user",id);
            redis.delete(List.of(properties.sessionCache().keyPrefix()+":"+id,
                    prefix+":login-failure:account:"+digest,prefix+":login-lock:account:"+digest));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=?",Integer.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_permission_command WHERE user_id=?",Integer.class,id)).isZero();
        }
        // 自有Gateway前缀且本轮只走IPv4 localhost密码登录；删除确切限流键，不扫描或清空Redis。
        String ratePrefix=System.getenv("GATEWAY_RATE_LIMIT_KEY_PREFIX");
        if(!"edu:t1631:test:gateway:rate".equals(ratePrefix))throw new IllegalStateException("Dedicated rate prefix required");
        redis.delete(ratePrefix+":login:"+com.eduplatform.iam.service.AuthService.hash("ip:127.0.0.1"));
        String prefix=properties.sessionCache().keyPrefix().replace(":session-version","");
        String address=digests.digest("login-address","127.0.0.1");
        redis.delete(List.of(prefix+":login-failure:ip:"+address,prefix+":login-lock:ip:"+address));
    }
    private static String userPath(String id){return "/api/v1/admin/users/"+id;}
    private static String latestPath(String id){return userPath(id)+"/permission-commands/latest";}
    private static String account(String id){return "t1631_test_"+id.replace("-","");}
    private static String email(String id){return account(id)+"@example.invalid";}
}
