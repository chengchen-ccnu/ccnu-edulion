package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.TokenPair;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** T16.3 会话前置专项：真实 MySQL/Redis/Gateway，Spy 仅控制线程时序，不替代任何领域或持久化结果。 */
@SpringBootTest(properties = {
        "platform.iam.seed.enabled=false", "platform.iam.review.messaging-enabled=false",
        "platform.iam.account-permissions.worker-enabled=false",
        "platform.iam.review.retention-enabled=false", "spring.datasource.hikari.maximum-pool-size=2"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T163_SESSION_REAL_ACCEPTANCE", matches = "true")
class T163SessionRaceRealAcceptanceTest {
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired StringRedisTemplate redis;
    @Autowired IamProperties properties;
    @Autowired SecurityDigests digests;
    @Autowired com.eduplatform.iam.service.AccountPermissionService permissions;
    @Autowired com.eduplatform.iam.service.AdminAuthority authority;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean IamMapper mapper;
    @MockitoSpyBean IdentitySecurityStore store;
    private static final String PASSWORD = "T163-test-password-01";
    private static final String ADDRESS = "127.0.0.163";

    @Test
    /** 登录身份快照与真实管理员封禁并发；封禁后密码正确也不得签发，旧JWT在缓存缺失时安全拒绝。 */
    void concurrentLoginCannotOvertakeAccountBan() throws Exception {
        withFixture((id,email)->withAdministrator(admin->{
            TokenPair old=auth.login(email,PASSWORD,ADDRESS,"t163-ban-initial");
            CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
            var original=mapperDelegate();
            doAnswer(invocation->{Object value=original.answer(invocation);read.countDown();requireSignal(resume);return value;})
                    .when(mapper).findUserByIdentity("EMAIL",email);
            var pool=Executors.newSingleThreadExecutor();
            try {
                var login=pool.submit(()->{try {auth.login(email,PASSWORD,ADDRESS,"t163-ban-login");return false;}
                    catch(AuthException denied){return denied.status().value()==403;}});
                requireSignal(read,login);ban(admin,id);resume.countDown();
                assertThat(login.get(15,TimeUnit.SECONDS)).isTrue();assertThat(redis.hasKey(key(id))).isFalse();assertGateway(old,503);
            }finally {resume.countDown();pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS);}
        }));
    }

    @Test
    /** 刷新首读后并发封禁提交；锁内重读已撤销刷新事实，不能重新填回旧版本。 */
    void concurrentRefreshCannotOvertakeAccountBan() throws Exception {
        withFixture((id,email)->withAdministrator(admin->{
            TokenPair old=auth.login(email,PASSWORD,ADDRESS,"t163-ban-refresh-initial");
            CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();
            var original=mapperDelegate();
            doAnswer(invocation->{Object value=original.answer(invocation);if(reads.incrementAndGet()==1){read.countDown();requireSignal(resume);}return value;})
                    .when(mapper).findRefreshSession(AuthService.hash(old.refreshToken()));
            var pool=Executors.newSingleThreadExecutor();
            try {
                var refresh=pool.submit(()->{try {auth.refresh(old.refreshToken());return false;}
                    catch(AuthException denied){return denied.status().value()==401 || denied.status().value()==409;}});
                requireSignal(read,refresh);ban(admin,id);resume.countDown();
                assertThat(refresh.get(15,TimeUnit.SECONDS)).isTrue();assertThat(redis.hasKey(key(id))).isFalse();assertGateway(old,503);
            }finally {resume.countDown();pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS);}
        }));
    }

    /** 使用正式预览和确认服务封禁无课程的合成学生，不绕过权限、revision或审计。 */
    private void ban(String admin,String id) {
        var action=com.eduplatform.iam.web.AccountPermissionDtos.Action.BAN;
        var preview=permissions.preview(admin,id,action);
        var command=permissions.change(admin,id,new com.eduplatform.iam.web.AccountPermissionDtos.CommandRequest(
                UUID.randomUUID(),preview.previewId(),preview.expectedRevision(),action,"并发封禁专项验收明确确认账号影响",true),ADDRESS,"t163-ban-race");
        assertThat(command.coordinationStatus()).isEqualTo("NOT_REQUIRED");
    }

    /** 仅专用local库创建单一合成管理员用于调用正式权限服务；精确删除本轮身份，不建立审核批准记录。 */
    private void withAdministrator(AdminScenario scenario)throws Exception {
        String id=UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO `user`(id,account,display_name,password_hash,status) VALUES(?,?,?,?,'ACTIVE')",
                    id,"t163_admin_"+id.replace("-",""),"T163 race administrator",encoder.encode(PASSWORD));
            mapper.assignRole(id,"ADMIN");scenario.run(id);
        }finally {
            jdbc.update("DELETE FROM account_security_audit WHERE actor_id=? OR user_id=?",id,id);
            jdbc.update("DELETE FROM user_role WHERE user_id=?",id);jdbc.update("DELETE FROM `user` WHERE id=?",id);
        }
    }

    /** 在连接前限定专用开发 Schema；没有环境授权时测试跳过，不连接生产或猜测凭据。 */
    @DynamicPropertySource
    static void requireDevelopmentSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_iam_dev".equals(System.getenv("IAM_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T16.3 session acceptance requires dedicated IAM development schema");
        }
    }

    @Test
    /** 登录读到旧身份后暂停线程，另一个线程真实撤销并提交；继续登录必须读取新版本，旧 JWT 不能回填复活。 */
    void staleLoginSnapshotCannotRestoreOldJwt() throws Exception {
        withFixture((id, email) -> {
            TokenPair old = auth.login(email, PASSWORD, ADDRESS, "t163-initial-login");
            assertGateway(old, 200);
            CountDownLatch read = new CountDownLatch(1), resume = new CountDownLatch(1);
            org.mockito.stubbing.Answer<?> original = mapperDelegate();
            doAnswer(invocation -> {
                Object snapshot = original.answer(invocation);
                read.countDown();
                requireSignal(resume);
                return snapshot;
            }).when(mapper).findUserByIdentity("EMAIL", email);
            var threads = Executors.newSingleThreadExecutor();
            try {
                var login = threads.submit(() -> auth.login(email, PASSWORD, ADDRESS, "t163-concurrent-login"));
                requireSignal(read, login);
                auth.logoutAll(id, 0, ADDRESS, "t163-concurrent-revoke");
                assertThat(redis.hasKey(key(id))).isFalse();
                resume.countDown();
                TokenPair current = login.get(15, TimeUnit.SECONDS);
                assertThat(redis.opsForValue().get(key(id))).isEqualTo("1");
                assertGateway(old, 401);
                assertGateway(current, 200);
            } finally { resume.countDown(); threads.shutdownNow(); threads.awaitTermination(15, TimeUnit.SECONDS); }
        });
    }

    @Test
    /** 刷新读到 ACTIVE 会话后暂停，撤销提交再继续；刷新必须失败且不能把旧缓存写回。 */
    void staleRefreshSnapshotCannotRestoreOldJwt() throws Exception {
        withFixture((id, email) -> {
            TokenPair old = auth.login(email, PASSWORD, ADDRESS, "t163-refresh-initial");
            CountDownLatch read = new CountDownLatch(1), resume = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            org.mockito.stubbing.Answer<?> original = mapperDelegate();
            doAnswer(invocation -> {
                Object snapshot = original.answer(invocation);
                if (reads.incrementAndGet() == 1) { read.countDown(); requireSignal(resume); }
                return snapshot;
            }).when(mapper).findRefreshSession(AuthService.hash(old.refreshToken()));
            var threads = Executors.newSingleThreadExecutor();
            try {
                var refresh = threads.submit(() -> {
                    try { auth.refresh(old.refreshToken()); return false; }
                    catch (AuthException expected) { return expected.status().value() == 401 || expected.status().value() == 409; }
                });
                requireSignal(read, refresh);
                auth.logoutAll(id, 0, ADDRESS, "t163-refresh-revoke");
                resume.countDown();
                assertThat(refresh.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(redis.hasKey(key(id))).isFalse();
                assertGateway(old, 503);
                auth.login(email, PASSWORD, ADDRESS, "t163-current-refill");
                assertGateway(old, 401);
            } finally { resume.countDown(); threads.shutdownNow(); threads.awaitTermination(15, TimeUnit.SECONDS); }
        });
    }

    @Test
    /** 签发提交后、缓存发布加锁前插入撤销；发布必须再次读取版本而非捕获的旧账号快照。 */
    void revocationBetweenCommitAndPublicationUsesCurrentVersion() throws Exception {
        withFixture((id, email) -> {
            CountDownLatch committed = new CountDownLatch(1), resume = new CountDownLatch(1);
            AtomicInteger locks = new AtomicInteger();
            org.mockito.stubbing.Answer<?> original = mapperDelegate();
            doAnswer(invocation -> {
                if (locks.incrementAndGet() == 2) { committed.countDown(); requireSignal(resume); }
                return original.answer(invocation);
            }).when(mapper).findUserByIdForUpdate(id);
            var threads = Executors.newSingleThreadExecutor();
            try {
                var login = threads.submit(() -> auth.login(email, PASSWORD, ADDRESS, "t163-commit-gap"));
                requireSignal(committed, login);
                auth.logoutAll(id, 0, ADDRESS, "t163-commit-gap-revoke");
                resume.countDown();
                TokenPair overtaken = login.get(15, TimeUnit.SECONDS);
                assertThat(redis.opsForValue().get(key(id))).isEqualTo("1");
                assertGateway(overtaken, 401);
            } finally { resume.countDown(); threads.shutdownNow(); threads.awaitTermination(15, TimeUnit.SECONDS); }
        });
    }

    @Test
    /** Redis 发布故障不返回成功凭证；已提交会话事实保留，缺失缓存由 Gateway 安全拒绝，恢复后读取权威版本。 */
    void redisPublicationFailureRemainsFailClosed() throws Exception {
        withFixture((id, email) -> {
            TokenPair old = auth.login(email, PASSWORD, ADDRESS, "t163-failure-initial");
            auth.logoutAll(id, 0, ADDRESS, "t163-failure-revoke");
            doAnswer(invocation -> { throw new IllegalStateException("Injected Redis publication failure"); })
                    .when(store).cacheSessionVersion(eq(id), eq(1L), any(Duration.class));
            try {
                auth.login(email, PASSWORD, ADDRESS, "t163-redis-failure");
                throw new AssertionError("Publication failure must reject success");
            } catch (AuthException expected) { assertThat(expected.status().value()).isEqualTo(503); }
            assertThat(redis.hasKey(key(id))).isFalse();
            assertGateway(old, 503);
            reset(store);
            TokenPair recovered = auth.login(email, PASSWORD, ADDRESS, "t163-redis-recovered");
            assertGateway(old, 401);
            assertGateway(recovered, 200);
        });
    }

    @Test
    /** 已插入 Refresh Session 后注入真实事务中的审计失败；整条签发回滚，缓存不得发布。 */
    void databaseFailureRollsBackSessionWithoutPublishingCache() throws Exception {
        withFixture((id, email) -> {
            doAnswer(invocation -> { throw new IllegalStateException("Injected audit persistence failure"); })
                    .when(mapper).insertLoginAudit(anyString(), eq(id), eq("SUCCESS"),
                            anyString(), anyString(), any(java.time.Instant.class));
            try {
                auth.login(email, PASSWORD, ADDRESS, "t163-transaction-failure");
                throw new AssertionError("Audit failure must roll back issuance");
            } catch (IllegalStateException expected) {
                assertThat(expected.getMessage()).isEqualTo("Injected audit persistence failure");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_session WHERE user_id=?", Integer.class, id)).isZero();
            assertThat(redis.hasKey(key(id))).isFalse();
        });
    }

    @Test
    /** 两条数据库连接承载四个并发登录；提交后归还连接再发布，不能因回调争抢连接互相卡住。 */
    void smallConnectionPoolSupportsConcurrentIssuance() throws Exception {
        withFixture((id, email) -> {
            var threads = Executors.newFixedThreadPool(4);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<TokenPair>>();
                for (int index = 0; index < 4; index++) {
                    futures.add(threads.submit(() -> auth.login(email, PASSWORD, ADDRESS, "t163-small-pool")));
                }
                for (var future : futures) assertGateway(future.get(20, TimeUnit.SECONDS), 200);
                assertThat(redis.opsForValue().get(key(id))).isEqualTo("0");
            } finally { threads.shutdownNow(); threads.awaitTermination(15, TimeUnit.SECONDS); }
        });
    }

    /** 每例创建一个明确合成学生；仅使用已有 STUDENT 角色，不插入审核通过事实，finally 精确清理本轮账号及缓存。 */
    private void withFixture(Scenario scenario) throws Exception {
        String id = UUID.randomUUID().toString(), email = "t163-session-" + id + "@example.invalid";
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_iam_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `role` WHERE code='STUDENT'", Integer.class)).isEqualTo(1);
        var normalizer=new com.eduplatform.iam.seed.IamSeedInitializer(mapper,encoder,properties,store,authority,jdbc);
        var normalization=new org.springframework.transaction.support.TransactionTemplate(transactions);
        normalization.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var counts=normalization.execute(status->normalizer.normalizeCapabilities());
        if(counts.upgradedTeacherSamples()+counts.isolatedAdministratorSamples()>0)
            System.out.println("Controlled sample normalization: teachers="+counts.upgradedTeacherSamples()+", administrators="+counts.isolatedAdministratorSamples());
        try {
            jdbc.update("INSERT INTO `user`(id,account,display_name,password_hash,status) VALUES(?,?,?,?,'ACTIVE')",
                    id, "t163_" + id.replace("-", ""), "T163 session test", encoder.encode(PASSWORD));
            mapper.insertIdentity(UUID.randomUUID().toString(), id, "EMAIL", email, java.time.Instant.now());
            mapper.assignRole(id, "STUDENT");
            scenario.run(id, email);
        } finally {
            reset(mapper, store);
            jdbc.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?",id);
            jdbc.update("DELETE FROM account_permission_command WHERE user_id=?",id);
            jdbc.update("DELETE FROM account_permission_preview WHERE user_id=?",id);
            jdbc.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?", id, id);
            jdbc.update("DELETE FROM login_audit WHERE user_id=?", id);
            jdbc.update("DELETE FROM refresh_session WHERE user_id=?", id);
            jdbc.update("DELETE FROM user_identity WHERE user_id=?", id);
            jdbc.update("DELETE FROM user_role WHERE user_id=?", id);
            jdbc.update("DELETE FROM `user` WHERE id=?", id);
            String prefix = properties.sessionCache().keyPrefix().replace(":session-version", "");
            String account = digests.digest("login-user", id), address = digests.digest("login-address", ADDRESS);
            redis.delete(List.of(key(id), prefix + ":login-failure:account:" + account,
                    prefix + ":login-lock:account:" + account, prefix + ":login-failure:ip:" + address,
                    prefix + ":login-lock:ip:" + address));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=?", Integer.class, id)).isZero();
        }
    }

    /** 经真实 Gateway 读取本人；断言状态而不输出任何 JWT、Cookie 或完整身份。 */
    private void assertGateway(TokenPair pair, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/api/v1/users/me"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + pair.accessToken()).GET().build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(status);
    }
    private String key(String id) { return properties.sessionCache().keyPrefix() + ":" + id; }
    /** MyBatis JDK 代理由 Spring 的委托 Answer 保存；调用原 Answer 执行真实 SQL，不调用抽象接口实现。 */
    private org.mockito.stubbing.Answer<?> mapperDelegate() {
        return org.mockito.Mockito.mockingDetails(mapper).getMockCreationSettings().getDefaultAnswer();
    }
    private static void requireSignal(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent test signal timed out");
    }
    private static void requireSignal(CountDownLatch latch, java.util.concurrent.Future<?> action) throws Exception {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            action.get(1, TimeUnit.SECONDS);
            throw new AssertionError("Concurrent action completed without reaching timing hook");
        }
    }
    @FunctionalInterface private interface Scenario { void run(String id, String email) throws Exception; }
    @FunctionalInterface private interface AdminScenario { void run(String id) throws Exception; }
}
