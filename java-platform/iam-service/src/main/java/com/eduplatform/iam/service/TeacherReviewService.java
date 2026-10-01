package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.TeacherApplication;
import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.media.TeacherQualificationMediaClient;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.persistence.TeacherReviewRepository;
import com.eduplatform.iam.web.TeacherReviewDtos;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAM 教师资格审核的唯一业务入口。管理员权限在 Gateway/IAM HTTP 层及这里双重检查；
 * 打开、决定、能力授予、安全审计和 Outbox 使用 IAM 本地事务与 revision 冲突保护。
 */
@Service
public class TeacherReviewService {
    private final IamMapper mapper;
    private final TeacherReviewRepository reviews;
    private final TeacherQualificationMediaClient media;
    private final ObjectMapper json;

    public TeacherReviewService(IamMapper mapper, TeacherReviewRepository reviews,
            TeacherQualificationMediaClient media, ObjectMapper json) {
        this.mapper = mapper; this.reviews = reviews; this.media = media; this.json = json;
    }

    /** 查询申请当前轮次的安全列表；仅接受四状态、精确身份及有界页码，查询不改变审核状态。 */
    public TeacherReviewDtos.Page list(String actor, TeacherReviewDtos.Status status, String exact,
            TeacherReviewDtos.Order order, int page, int size) {
        requireAdmin(actor);
        if (page < 1 || size < 1 || size > 100 || ((long) page - 1) * size > 1_000_000) invalid();
        String normalized = exact == null || exact.isBlank() ? null : exact.trim().toLowerCase(Locale.ROOT);
        if (normalized != null && normalized.length() > 128) invalid();
        boolean newest = order == null || order == TeacherReviewDtos.Order.NEWEST;
        String state = status == null ? null : status.name();
        List<TeacherReviewDtos.Summary> items = reviews.list(state, normalized, newest, ((long) page - 1) * size, size)
                .stream().map(this::summary).toList();
        return new TeacherReviewDtos.Page(items, page, size, reviews.count(state, normalized));
    }

    /** 主动打开详情首次转审核中并记录查看者；后续管理员可继续查看或决定，不取得独占权。 */
    @Transactional
    public TeacherReviewDtos.Detail open(String actor, String applicationId,
            String remoteAddress, String traceId) {
        requireAdmin(actor);
        TeacherApplication application = requireApplication(reviews.lockApplication(applicationId));
        if ("SUBMITTED".equals(application.getStatus())) {
            TeacherReviewRepository.Round round = reviews.latestRound(applicationId);
            if (round == null) throw new IllegalStateException("Teacher application round missing");
            Instant now = Instant.now();
            reviews.startReview(applicationId, round.id(), actor, now);
            mapper.insertSecurityAudit(UUID.randomUUID().toString(), application.getUserId(), actor,
                    "TEACHER_APPLICATION_FIRST_VIEWED", "管理员首次打开申请",
                    remoteAddress == null ? "unknown" : remoteAddress,
                    traceId == null ? "unknown" : traceId, now);
        }
        return detail(applicationId);
    }

    /**
     * 按 revision 决定当前轮次；批准前验证当前资质可真实读取。
     * 决定、教师能力、审计和最小事件要么全部提交、要么全部回滚；同请求 ID 精确重放。
     */
    @Transactional
    public TeacherReviewDtos.Detail decide(String actor, String applicationId,
            TeacherReviewDtos.DecisionRequest request, String remoteAddress, String traceId) {
        requireAdmin(actor);
        validateDecision(request);
        String fingerprint = fingerprint(actor, applicationId, request);
        TeacherReviewRepository.Replay replay = reviews.replay(request.clientRequestId().toString());
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint)) conflict();
            try { return json.readValue(replay.resultJson(), TeacherReviewDtos.Detail.class); }
            catch (RuntimeException failure) { throw new IllegalStateException("Stored review response invalid", failure); }
        }
        TeacherApplication application = requireApplication(reviews.lockApplication(applicationId));
        replay = reviews.replay(request.clientRequestId().toString());
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint)) conflict();
            try { return json.readValue(replay.resultJson(), TeacherReviewDtos.Detail.class); }
            catch (RuntimeException failure) { throw new IllegalStateException("Stored review response invalid", failure); }
        }
        if (application.getRevision() != request.expectedRevision()
                || !"UNDER_REVIEW".equals(application.getStatus())) conflict();
        TeacherReviewRepository.Round round = reviews.latestRound(applicationId);
        if (round == null || !round.mediaId().equals(application.getQualificationMediaId())) conflict();
        UserAccount user = mapper.findUserById(application.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !mapper.findRoles(user.getId()).contains("STUDENT")
                || mapper.findRoles(user.getId()).contains("ADMIN")) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IAM.STUDENT_ACCOUNT_REQUIRED", "申请人不具备普通学习账号资格");
        }
        if (request.decision() == TeacherReviewDtos.Decision.APPROVED) {
            TeacherQualificationMediaClient.Grant grant = media.grant(round.mediaId(), actor, false, traceId);
            media.requireReadable(grant);
        }
        Instant now = Instant.now();
        String decision = request.decision().name();
        String reason = request.decision() == TeacherReviewDtos.Decision.REJECTED
                ? request.rejectionReason().trim() : null;
        String decisionId = UUID.randomUUID().toString();
        reviews.decide(applicationId, round.id(), decision, reason, now);
        reviews.insertDecision(decisionId, applicationId, round.id(), actor, decision, reason, now);
        if (request.decision() == TeacherReviewDtos.Decision.APPROVED) {
            mapper.upsertRole("TEACHER", "TEACHER");
            mapper.upsertPermission("teaching:access", "teaching:access");
            mapper.assignPermission("TEACHER", "teaching:access");
            mapper.assignRole(application.getUserId(), "TEACHER");
            if (!mapper.findPermissions(application.getUserId()).contains("teaching:access")
                    || !mapper.findPermissions(application.getUserId()).contains("learning:access")) {
                throw new IllegalStateException("Teacher or student capability was not granted");
            }
        }
        mapper.insertSecurityAudit(UUID.randomUUID().toString(), application.getUserId(), actor,
                "TEACHER_APPLICATION_" + decision, reason == null ? "管理员批准当前材料" : reason,
                remoteAddress == null ? "unknown" : remoteAddress,
                traceId == null ? "unknown-trace" : traceId, now);
        reviews.outbox("TeacherQualificationDecided", "edu.iam.events.v1",
                "iam.teacher-qualification.decided.v1", applicationId,
                Map.of("applicationId", applicationId, "roundId", round.id(),
                        "userId", application.getUserId(), "decision", decision),
                traceId == null ? "unknown-trace" : traceId, now);
        TeacherReviewDtos.Detail result = detail(applicationId);
        try {
            reviews.insertCommand(request.clientRequestId().toString(), applicationId, fingerprint,
                    decisionId, result, now);
        } catch (DuplicateKeyException duplicateRequestId) {
            // 另一申请并发占用了同一请求 ID，本事务的决定、授能、审计和 Outbox 一并回滚。
            conflict();
        }
        return result;
    }

    /** 仅在 IAM 管理员权限和申请轮次绑定均成立时，为该轮仍保留的文件申请短期原件 URL。 */
    public TeacherQualificationMediaClient.Grant grant(String actor, String applicationId,
            TeacherReviewDtos.AccessRequest request, String traceId) {
        requireAdmin(actor);
        requireApplication(reviews.application(applicationId));
        TeacherReviewRepository.Round round = reviews.round(request.roundId().toString());
        if (round == null || !applicationId.equals(round.applicationId())) {
            throw new AuthException(HttpStatus.NOT_FOUND, "IAM.NOT_FOUND", "申请或材料不存在");
        }
        if (round.releaseEnqueuedAt() != null || (round.retentionUntil() != null
                && !round.retentionUntil().isAfter(Instant.now()))) {
            throw new AuthException(HttpStatus.CONFLICT, "IAM.MATERIAL_EXPIRED", "该轮材料保留期已结束");
        }
        return media.grant(round.mediaId(), actor, request.action() == TeacherReviewDtos.Action.DOWNLOAD, traceId);
    }

    /** 将 IAM 当前投影和全轮次事实组成管理员详情；材料只给稳定 ID，不附短期 URL。 */
    private TeacherReviewDtos.Detail detail(String applicationId) {
        TeacherReviewRepository.Summary current = reviews.summary(applicationId);
        if (current == null) throw new AuthException(HttpStatus.NOT_FOUND, "IAM.NOT_FOUND", "申请不存在");
        Instant now = Instant.now();
        return new TeacherReviewDtos.Detail(UUID.fromString(current.id()), UUID.fromString(current.userId()),
                current.account(), current.displayName(), TeacherReviewDtos.Status.valueOf(current.status()),
                current.revision(), reviews.rounds(applicationId).stream().map(round -> new TeacherReviewDtos.Round(
                        UUID.fromString(round.id()), round.number(), UUID.fromString(round.mediaId()),
                        round.statement(), round.submittedAt(), TeacherReviewDtos.Status.valueOf(round.status()),
                        round.releaseEnqueuedAt() == null && (round.retentionUntil() == null
                                || round.retentionUntil().isAfter(now)), uuid(round.firstViewedBy()),
                        round.firstViewedAt(), uuid(round.decidedBy()), round.decidedAt(),
                        round.rejectionReason(), round.retentionUntil())).toList());
    }

    private TeacherReviewDtos.Summary summary(TeacherReviewRepository.Summary value) {
        return new TeacherReviewDtos.Summary(UUID.fromString(value.id()), UUID.fromString(value.userId()),
                value.account(), value.displayName(), TeacherReviewDtos.Status.valueOf(value.status()),
                value.revision(), value.submittedAt());
    }

    /** 按 IAM 当前数据库能力复核管理员，旧 JWT 或伪造角色头均不能代替实时授权。 */
    private void requireAdmin(String actor) {
        UserAccount user = mapper.findUserById(actor);
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || !mapper.findRoles(actor).contains("ADMIN")
                || !mapper.findPermissions(actor).contains("administration:access")) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IAM.FORBIDDEN", "无权审核教师资格");
        }
    }

    private static TeacherApplication requireApplication(TeacherApplication application) {
        if (application == null) throw new AuthException(HttpStatus.NOT_FOUND, "IAM.NOT_FOUND", "申请不存在");
        return application;
    }

    /** 校验明确的 revision、材料确认及驳回理由，非法输入返回可理解的 422。 */
    private static void validateDecision(TeacherReviewDtos.DecisionRequest request) {
        if (request.expectedRevision() == null || request.materialConfirmed() == null) invalid();
        if (request.decision() == TeacherReviewDtos.Decision.APPROVED) {
            if (!Boolean.TRUE.equals(request.materialConfirmed()) || request.rejectionReason() != null) invalid();
        } else if (request.rejectionReason() == null || request.rejectionReason().trim().length() < 10
                || request.rejectionReason().trim().length() > 1000) invalid();
    }

    /** 将管理员、申请及决定参数组成不可逆请求指纹，用来区分真正重放和请求 ID 误用。 */
    private static String fingerprint(String actor, String applicationId, TeacherReviewDtos.DecisionRequest request) {
        try {
            String value = actor + "\n" + applicationId + "\n" + request.expectedRevision() + "\n" + request.decision()
                    + "\n" + request.materialConfirmed() + "\n" + request.rejectionReason();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static void conflict() { throw new AuthException(HttpStatus.CONFLICT, "IAM.REVISION_CONFLICT",
            "教师申请状态已变化，请刷新后重试"); }
    private static void invalid() { throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.VALIDATION_FAILED",
            "审核参数不符合要求"); }
}
