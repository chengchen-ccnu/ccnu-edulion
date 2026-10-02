package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.TeacherApplication;
import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.media.TeacherQualificationMediaClient;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.persistence.TeacherReviewRepository;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存教师申请提交和重提事实，并在同一事务建立每轮资质媒体引用。 */
@Service
public class TeacherApplicationService {
    private final IamMapper mapper;
    private final TeacherQualificationMediaClient media;
    private final TeacherReviewRepository reviews;

    public TeacherApplicationService(IamMapper mapper, TeacherQualificationMediaClient media,
            TeacherReviewRepository reviews) {
        this.mapper = mapper; this.media = media; this.reviews = reviews;
    }

    /** 先跨服务验证稳定 mediaId，再提交/重提本域事实；同一 clientRequestId 可安全重放。 */
    @Transactional
    public Result submit(String userId, AccountLifecycleDtos.SubmitTeacherApplicationRequest request,
            String remoteAddress, String traceId) {
        UserAccount user = requireOrdinary(userId);
        String fingerprint = fingerprint(request);
        TeacherApplication replay = mapper.findTeacherApplicationReplay(request.clientRequestId().toString(), fingerprint);
        if (replay != null) {
            // 请求 ID 是全局唯一的；同载荷重放仍必须属于当前账号，不能提前泄露别人的申请投影。
            if (!userId.equals(replay.getUserId())) conflict();
            return new Result(response(replay), false);
        }
        media.requireBindable(request.qualificationMediaId().toString(), userId, traceId);
        TeacherApplication current = mapper.findTeacherApplicationByUser(userId);
        TeacherReviewRepository.Round previous = current == null ? null : reviews.latestRound(current.getId());
        Instant now = Instant.now();
        boolean created;
        try {
            if (current == null) {
                if (request.expectedRevision() != null) conflict();
                String id = UUID.randomUUID().toString();
                mapper.insertTeacherApplication(id, userId, request.qualificationMediaId().toString(),
                        request.statement().trim(), now);
                current = mapper.findTeacherApplicationByUser(userId);
                created = true;
            } else {
                if (!"REJECTED".equals(current.getStatus()) || request.expectedRevision() == null
                        || mapper.resubmitTeacherApplication(userId, request.qualificationMediaId().toString(),
                                request.statement().trim(), request.expectedRevision(), now) != 1) conflict();
                current = mapper.findTeacherApplicationByUser(userId);
                created = false;
            }
            mapper.insertTeacherApplicationCommand(request.clientRequestId().toString(), current.getId(), fingerprint, now);
            TeacherReviewRepository.Round round = reviews.insertRound(current.getId(),
                    request.qualificationMediaId().toString(), request.statement().trim(), now);
            reviews.outbox("MediaReferenceBound", "edu.media.events.v1", "media.reference.bound.v1",
                    round.mediaId(), java.util.Map.of("mediaId", round.mediaId(), "ownerService", "iam-service",
                            "referenceType", "TEACHER_APPLICATION_ROUND", "referenceId", round.id()),
                    traceId == null || traceId.isBlank() ? "unknown-trace" : traceId, now);
            reviews.markBoundEnqueued(round.id(), now);
            if (previous != null) reviews.replaceRetention(previous.id(), now);
            mapper.insertSecurityAudit(UUID.randomUUID().toString(), userId, userId,
                    created ? "TEACHER_APPLICATION_SUBMITTED" : "TEACHER_APPLICATION_RESUBMITTED",
                    "用户提交教师申请", safe(remoteAddress), safe(traceId), now);
            return new Result(response(current), created);
        } catch (DuplicateKeyException duplicate) {
            conflict(); return null;
        }
    }

    /** 查询本人申请，已批准教师也可读取历史；独立管理员无申请能力，不允许借已存事实越权读取。 */
    public AccountLifecycleDtos.TeacherApplicationResponse getMine(String userId) {
        if (mapper.findRoles(userId).contains("ADMIN") || !mapper.findRoles(userId).contains("STUDENT"))
            throw new AuthException(HttpStatus.FORBIDDEN,"IAM.STUDENT_ACCOUNT_REQUIRED","只有普通学习账号可以读取教师申请");
        TeacherApplication value = mapper.findTeacherApplicationByUser(userId);
        if (value == null) throw new AuthException(HttpStatus.NOT_FOUND, "IAM.NOT_FOUND", "账号或申请不存在");
        return response(value);
    }

    private UserAccount requireOrdinary(String userId) {
        UserAccount user = mapper.findUserByIdForUpdate(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new AuthException(HttpStatus.UNAUTHORIZED,
                "IAM.UNAUTHORIZED", "请先登录或重新登录");
        if (!mapper.findRoles(userId).contains("STUDENT") || mapper.findRoles(userId).contains("ADMIN")) throw new AuthException(HttpStatus.FORBIDDEN,
                "IAM.STUDENT_ACCOUNT_REQUIRED", "只有普通学习账号可提交教师申请");
        if (mapper.findPermissions(userId).contains("teaching:access")) throw new AuthException(HttpStatus.FORBIDDEN,
                "IAM.TEACHER_ALREADY_GRANTED", "当前账号已具备教学能力");
        return user;
    }

    private static AccountLifecycleDtos.TeacherApplicationResponse response(TeacherApplication value) {
        return new AccountLifecycleDtos.TeacherApplicationResponse(UUID.fromString(value.getId()),
                UUID.fromString(value.getUserId()), UUID.fromString(value.getQualificationMediaId()),
                value.getStatement(), value.getStatus(), value.getRejectionReason(), value.getRevision(),
                value.getSubmittedAt(), value.getUpdatedAt());
    }

    private static String fingerprint(AccountLifecycleDtos.SubmitTeacherApplicationRequest request) {
        try {
            String value = request.qualificationMediaId() + "\n" + request.statement().trim() + "\n"
                    + request.expectedRevision();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static void conflict() { throw new AuthException(HttpStatus.CONFLICT, "IAM.REVISION_CONFLICT",
            "教师申请状态已变化，请刷新后重试"); }
    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    public record Result(AccountLifecycleDtos.TeacherApplicationResponse application, boolean created) {}
}
