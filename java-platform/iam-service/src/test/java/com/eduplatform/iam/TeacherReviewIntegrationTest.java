package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.eduplatform.iam.media.TeacherQualificationMediaClient;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.persistence.TeacherReviewRepository;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.TeacherApplicationService;
import com.eduplatform.iam.service.TeacherQualificationRetentionWorker;
import com.eduplatform.iam.service.TeacherReviewService;
import com.eduplatform.iam.service.TokenPair;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import com.eduplatform.iam.web.TeacherReviewDtos;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 使用独立 H2 测试库验证 IAM 本地事务和状态；真实 OSS/RabbitMQ 由单独验收负责。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:teacher_review_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class TeacherReviewIntegrationTest {
    private static final String STUDENT = "10000000-0000-4000-8000-000000000101";
    private static final String ADMIN_A = "10000000-0000-4000-8000-000000000102";
    private static final String ADMIN_B = "10000000-0000-4000-8000-000000000103";
    private static final String APPLICATION = "10000000-0000-4000-8000-000000000201";
    private static final String MEDIA = "10000000-0000-4000-8000-000000000301";
    private static final String NEW_MEDIA = "10000000-0000-4000-8000-000000000302";
    private static final String TRACE = "review-test-trace";

    @Autowired JdbcTemplate jdbc;
    @Autowired IamMapper mapper;
    @Autowired TeacherReviewRepository reviews;
    @Autowired TeacherReviewService review;
    @Autowired TeacherApplicationService applications;
    @Autowired TeacherQualificationRetentionWorker.ReleaseTransaction release;
    @Autowired AuthService auth;
    @Autowired JwtDecoder jwt;
    @Autowired PasswordEncoder encoder;
    @MockitoBean TeacherQualificationMediaClient media;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM iam_outbox_event");
        jdbc.update("DELETE FROM teacher_review_command");
        jdbc.update("DELETE FROM teacher_review_decision");
        jdbc.update("DELETE FROM teacher_application_round");
        jdbc.update("DELETE FROM teacher_application_command");
        jdbc.update("DELETE FROM teacher_application");
        jdbc.update("DELETE FROM account_security_audit");
        jdbc.update("DELETE FROM refresh_session");
        jdbc.update("DELETE FROM login_audit");
        jdbc.update("DELETE FROM user_identity");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM `user`");
        mapper.upsertRole("STUDENT", "STUDENT"); mapper.upsertRole("ADMIN", "ADMIN");
        mapper.upsertPermission("learning:access", "learning:access");
        mapper.upsertPermission("administration:access", "administration:access");
        mapper.assignPermission("STUDENT", "learning:access");
        mapper.assignPermission("ADMIN", "administration:access");
        String hash = encoder.encode("test-password-1234");
        for (String id : List.of(STUDENT, ADMIN_A, ADMIN_B)) {
            jdbc.update("INSERT INTO `user` (id,account,display_name,password_hash,status,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, "review_" + id.substring(id.length() - 3), "审核测试", hash);
            mapper.assignRole(id, id.equals(STUDENT) ? "STUDENT" : "ADMIN");
        }
        jdbc.update("INSERT INTO user_identity (id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES (?,?, 'EMAIL', ?, CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), STUDENT, "review-student@example.invalid");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO teacher_application (id,user_id,qualification_media_id,statement,status,revision,submitted_at,updated_at) VALUES (?,?,?,?, 'SUBMITTED',0,?,?)",
                APPLICATION, STUDENT, MEDIA, "本人具有数学课程教学经验和合成测试资质材料", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        TeacherReviewRepository.Round round = reviews.insertRound(APPLICATION, MEDIA,
                "本人具有数学课程教学经验和合成测试资质材料", now);
        reviews.markBoundEnqueued(round.id(), now);
    }

    /** 列表不改状态；两名管理员打开详情只记录第一人，批准后刷新真实令牌保留学习能力。 */
    @Test
    void firstViewDecisionReplayAndRefresh() {
        TokenPair before = auth.login("review-student@example.invalid", "test-password-1234", "127.0.0.1", TRACE);
        assertThat(jwt.decode(before.accessToken()).getClaimAsStringList("permissions")).contains("learning:access")
                .doesNotContain("teaching:access");
        TeacherReviewDtos.Page page = review.list(ADMIN_A, TeacherReviewDtos.Status.SUBMITTED,
                "review-student@example.invalid", TeacherReviewDtos.Order.NEWEST, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(mapper.findTeacherApplicationByUser(STUDENT).getStatus()).isEqualTo("SUBMITTED");
        TeacherReviewDtos.Detail first = review.open(ADMIN_A, APPLICATION, "127.0.0.1", TRACE);
        assertThat(first.revision()).isEqualTo(1);
        TeacherReviewDtos.Detail second = review.open(ADMIN_B, APPLICATION, "127.0.0.1", TRACE);
        assertThat(second.revision()).isEqualTo(1);
        assertThat(second.rounds().get(0).firstViewedBy()).isEqualTo(UUID.fromString(ADMIN_A));
        when(media.grant(MEDIA, ADMIN_B, false, TRACE)).thenReturn(
                new TeacherQualificationMediaClient.Grant(MEDIA, "application/pdf", 32, "GET",
                        "https://example.invalid/test.pdf", Map.of(), Instant.now().plusSeconds(60)));
        TeacherReviewDtos.DecisionRequest command = new TeacherReviewDtos.DecisionRequest(
                UUID.randomUUID(), 1L, TeacherReviewDtos.Decision.APPROVED, true, null);
        TeacherReviewDtos.Detail decided = review.decide(ADMIN_B, APPLICATION, command, "127.0.0.1", TRACE);
        assertThat(decided.status()).isEqualTo(TeacherReviewDtos.Status.APPROVED);
        assertThat(review.decide(ADMIN_B, APPLICATION, command, "127.0.0.1", TRACE)).isEqualTo(decided);
        assertThatThrownBy(() -> review.decide(ADMIN_B, APPLICATION,
                new TeacherReviewDtos.DecisionRequest(command.clientRequestId(), 1L,
                        TeacherReviewDtos.Decision.REJECTED, false, "请补充清晰完整的教师资格证明"),
                "127.0.0.1", TRACE)).hasMessageContaining("状态已变化");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_outbox_event WHERE event_type='TeacherQualificationDecided'", Long.class)).isEqualTo(1);
        TokenPair after = auth.refresh(before.refreshToken());
        assertThat(jwt.decode(after.accessToken()).getClaimAsStringList("permissions"))
                .contains("learning:access", "teaching:access");
        assertThatThrownBy(() -> review.decide(ADMIN_A, APPLICATION,
                new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(), 1L,
                        TeacherReviewDtos.Decision.REJECTED, false, "测试原因已经足够明确"), "127.0.0.1", TRACE))
                .hasMessageContaining("状态已变化");
    }

    /** Media 授权失败使批准事务回滚；驳回保留决定，重提新轮次而不改写旧轮次。 */
    @Test
    void mediaFailureRollsBackAndRejectedRoundSurvivesResubmission() {
        review.open(ADMIN_A, APPLICATION, "127.0.0.1", TRACE);
        doThrow(new IllegalStateException("unavailable")).when(media).requireReadable(org.mockito.ArgumentMatchers.any());
        when(media.grant(anyString(), anyString(), anyBoolean(), anyString())).thenReturn(
                new TeacherQualificationMediaClient.Grant(MEDIA, "application/pdf", 32, "GET",
                        "https://example.invalid/test.pdf", Map.of(), Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> review.decide(ADMIN_A, APPLICATION,
                new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(), 1L,
                        TeacherReviewDtos.Decision.APPROVED, true, null), "127.0.0.1", TRACE)).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.findTeacherApplicationByUser(STUDENT).getStatus()).isEqualTo("UNDER_REVIEW");
        assertThat(mapper.findRoles(STUDENT)).doesNotContain("TEACHER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision", Long.class)).isZero();
        review.decide(ADMIN_A, APPLICATION, new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(), 1L,
                TeacherReviewDtos.Decision.REJECTED, false, "请补充清晰完整的教师资格证明"), "127.0.0.1", TRACE);
        applications.submit(STUDENT, new AccountLifecycleDtos.SubmitTeacherApplicationRequest(UUID.randomUUID(),
                2L, UUID.fromString(NEW_MEDIA), "已补充清晰完整的教师资格证明供重新审核"), "127.0.0.1", TRACE);
        assertThat(reviews.rounds(APPLICATION)).hasSize(2);
        assertThat(reviews.rounds(APPLICATION).get(1).status()).isEqualTo("REJECTED");
        assertThat(reviews.rounds(APPLICATION).get(1).retentionUntil()).isAfter(Instant.now().plusSeconds(6 * 86400L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision", Long.class)).isEqualTo(1);
    }

    /** 两名管理员持同 revision 同时决定，只能一人成功，另一人收到 409，历史仅一条。 */
    @Test
    void concurrentAdministratorsCannotOverwriteDecision() throws Exception {
        review.open(ADMIN_A, APPLICATION, "127.0.0.1", TRACE);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (String actor : List.of(ADMIN_A, ADMIN_B)) results.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    review.decide(actor, APPLICATION, new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(), 1L,
                            TeacherReviewDtos.Decision.REJECTED, false, "请补充清晰完整的教师资格证明"), "127.0.0.1", TRACE);
                    return 200;
                } catch (com.eduplatform.iam.service.AuthException failure) { return failure.status().value(); }
            }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS),
                    results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision", Long.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    /** 1000 字合法理由完整保存，复用的安全审计字段不溢出也不复制全文。 */
    @Test
    void maximumRejectionReasonDoesNotOverflowExistingSecurityAudit() {
        review.open(ADMIN_A, APPLICATION, "127.0.0.1", TRACE);
        String reason = "请补充清晰完整的教师资格证明".repeat(100).substring(0, 1000);
        TeacherReviewDtos.Detail result = review.decide(ADMIN_A, APPLICATION,
                new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(), 1L,
                        TeacherReviewDtos.Decision.REJECTED, false, reason), "127.0.0.1", TRACE);
        assertThat(result.rounds().get(0).rejectionReason()).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT reason FROM account_security_audit WHERE action='TEACHER_APPLICATION_REJECTED'",
                String.class)).startsWith("资格审核决定 ").hasSizeLessThan(500);
    }

    /** 在授能、审计与 Outbox 写入后强制造成命令保存失败，必须全部回滚，不能留下半个批准。 */
    @Test
    void latePersistenceFailureRollsBackDecisionRoleAuditAndOutbox() {
        review.open(ADMIN_A, APPLICATION, "127.0.0.1", TRACE);
        when(media.grant(MEDIA, ADMIN_A, false, TRACE)).thenReturn(
                new TeacherQualificationMediaClient.Grant(MEDIA, "application/pdf", 32, "GET",
                        "https://example.invalid/test.pdf", Map.of(), Instant.now().plusSeconds(60)));
        UUID requestId = UUID.randomUUID();
        jdbc.execute("ALTER TABLE teacher_review_command ADD CONSTRAINT test_reject_command CHECK (client_request_id<>'"
                + requestId + "')");
        try {
            assertThatThrownBy(() -> review.decide(ADMIN_A, APPLICATION,
                    new TeacherReviewDtos.DecisionRequest(requestId, 1L,
                            TeacherReviewDtos.Decision.APPROVED, true, null), "127.0.0.1", TRACE))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(mapper.findTeacherApplicationByUser(STUDENT).getStatus()).isEqualTo("UNDER_REVIEW");
            assertThat(mapper.findRoles(STUDENT)).doesNotContain("TEACHER");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_security_audit WHERE action='TEACHER_APPLICATION_APPROVED'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_outbox_event", Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE teacher_review_command DROP CONSTRAINT test_reject_command"); }
    }

    /** 七天截止后仅排队释放旧引用，决定事实仍在；重复执行不能再发一次释放。 */
    @Test
    void retentionReleasesExactRoundOnce() {
        review.open(ADMIN_A, APPLICATION, "127.0.0.1", TRACE);
        review.decide(ADMIN_A, APPLICATION, new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(), 1L,
                TeacherReviewDtos.Decision.REJECTED, false, "请补充清晰完整的教师资格证明"), "127.0.0.1", TRACE);
        String roundId = reviews.latestRound(APPLICATION).id();
        jdbc.update("UPDATE teacher_application_round SET retention_until=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), roundId);
        release.one(roundId);
        release.one(roundId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_outbox_event WHERE event_type='MediaReferenceReleased'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision", Long.class)).isEqualTo(1);
    }

    /** 身份精确查找覆盖既有邮箱字段长度，合法长邮箱不能被旧 128 字限制拒绝。 */
    @Test
    void exactSearchAcceptsExistingIdentityLength() {
        String identity = "a@" + ("a".repeat(60) + ".").repeat(3) + "example.invalid";
        jdbc.update("UPDATE user_identity SET normalized_value=? WHERE user_id=?", identity, STUDENT);
        assertThat(review.list(ADMIN_A, null, identity, null, 1, 20).items()).hasSize(1);
    }

    /** 其他普通账号不能通过复用全局请求 ID 绕过本人申请投影的权限。 */
    @Test
    void submissionReplayCannotReadAnotherStudentsProjection() {
        jdbc.update("DELETE FROM user_role WHERE user_id=?", ADMIN_B);
        mapper.assignRole(ADMIN_B, "STUDENT");
        jdbc.update("UPDATE teacher_application SET status='REJECTED' WHERE id=?", APPLICATION);
        AccountLifecycleDtos.SubmitTeacherApplicationRequest request = new AccountLifecycleDtos.SubmitTeacherApplicationRequest(
                UUID.randomUUID(), 0L, UUID.fromString(NEW_MEDIA), "合成测试：补充清晰完整的教师资格证明");
        applications.submit(STUDENT, request, "127.0.0.1", TRACE);
        assertThatThrownBy(() -> applications.submit(ADMIN_B, request, "127.0.0.1", TRACE))
                .hasMessageContaining("状态已变化");
        assertThat(mapper.findTeacherApplicationByUser(ADMIN_B)).isNull();
    }

    /** 学生不能列管理数据，管理员不能用别的申请或过期轮次换取材料 URL。 */
    @Test
    void materialAccessRequiresAdministratorExactRoundAndRetention() {
        String roundId = reviews.latestRound(APPLICATION).id();
        TeacherReviewDtos.AccessRequest request = new TeacherReviewDtos.AccessRequest(
                UUID.fromString(roundId), TeacherReviewDtos.Action.PREVIEW);
        assertThatThrownBy(() -> review.list(STUDENT, null, null, null, 1, 20))
                .hasMessageContaining("无权审核");
        assertThatThrownBy(() -> review.grant(STUDENT, APPLICATION, request, TRACE))
                .hasMessageContaining("无权审核");
        assertThatThrownBy(() -> review.grant(ADMIN_A, UUID.randomUUID().toString(), request, TRACE))
                .hasMessageContaining("申请不存在");
        assertThatThrownBy(() -> review.grant(ADMIN_A, APPLICATION,
                new TeacherReviewDtos.AccessRequest(UUID.randomUUID(), TeacherReviewDtos.Action.PREVIEW), TRACE))
                .hasMessageContaining("申请或材料不存在");
        jdbc.update("UPDATE teacher_application_round SET retention_until=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), roundId);
        assertThatThrownBy(() -> review.grant(ADMIN_A, APPLICATION, request, TRACE))
                .hasMessageContaining("保留期已结束");
    }
}
