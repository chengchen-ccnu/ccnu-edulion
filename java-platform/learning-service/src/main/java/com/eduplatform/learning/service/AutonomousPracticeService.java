package com.eduplatform.learning.service;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.Aggregate;
import com.eduplatform.learning.domain.QuestionModels.Version;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AutonomousPracticeRow;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.web.AgentAccessDtos;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本人自主练习领域服务。输入只接受 Agent Access 的稳定 action/owner/题目版本，输出复用既有
 * assessment、attempt、answer 和结果模型；Qdrant 候选在每次校验与创建时都重新以 MySQL 为准。
 */
@Service
public class AutonomousPracticeService {
    private static final Set<QuestionModels.Type> AUTO_TYPES = Set.of(
            QuestionModels.Type.SINGLE_CHOICE, QuestionModels.Type.MULTIPLE_CHOICE,
            QuestionModels.Type.TRUE_FALSE, QuestionModels.Type.FILL_BLANK);

    private final LearningAssessmentRepository assessments;
    private final QuestionRepository questions;

    public AutonomousPracticeService(LearningAssessmentRepository assessments, QuestionRepository questions) {
        this.assessments = assessments;
        this.questions = questions;
    }

    /**
     * 以 Learning 当前事实过滤候选，并把最近做过的版本放到后面；硬条件永不因数量不足而放宽。
     * 输入最多 20 个精确题目/版本键，输出最多 requestedCount 个安全身份投影。
     */
    @Transactional(readOnly = true)
    public AgentAccessDtos.ValidatePracticeCandidatesResponse validate(
            AgentAccessDtos.ValidatePracticeCandidatesRequest request) {
        List<ValidatedSnapshot> valid = validateSnapshots(request.criteria(), request.candidates());
        Set<String> recent = new HashSet<>(assessments.recentQuestionVersionIds(
                request.ownerUserId().toString(), 100));
        valid.sort(java.util.Comparator
                .comparingInt((ValidatedSnapshot value) -> difficultyPriority(request.criteria(), value.version()))
                .thenComparing(value -> recent.contains(value.version().id())));
        return new AgentAccessDtos.ValidatePracticeCandidatesResponse(valid.stream()
                .limit(request.criteria().requestedCount()).map(this::projection).toList());
    }

    /**
     * 按 actionId 幂等创建一场立即发布、无课程、无倒计时、只对本人可见的 PRACTICE。
     * 创建事务再次校验版本，防止确认期间归档或版本前移；唯一约束保证一个 action 最多一场练习。
     */
    @Transactional
    public AgentAccessDtos.AutonomousPracticeResult create(AgentAccessDtos.CreateAutonomousPracticeRequest request) {
        AutonomousPracticeRow replay = assessments.autonomousPracticeByAction(
                request.actionId().toString(), request.ownerUserId().toString()).orElse(null);
        if (replay != null) return result(replay, true);
        List<ValidatedSnapshot> valid = validateSnapshots(request.criteria(), request.candidates()).stream()
                .limit(request.criteria().requestedCount()).toList();
        if (valid.isEmpty()) {
            throw new QuestionException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "LEARNING.AUTONOMOUS_PRACTICE_EMPTY", "没有仍然有效且可自动评分的候选题");
        }
        Instant now = Instant.now();
        String assessmentId = UUID.randomUUID().toString();
        AssessmentRow assessment = new AssessmentRow(assessmentId, null, null, null,
                assessmentTitle(request.criteria().topic()), "由学生确认的公共题库自主练习",
                LearningDtos.AssessmentType.PRACTICE, LearningDtos.AssessmentStatus.PUBLISHED,
                LearningDtos.GradePublicationPolicy.AUTOMATIC_AFTER_GRADING,
                LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION,
                LearningDtos.AssessmentCloseStatus.OPEN, LearningDtos.AssessmentSettlementStatus.NOT_STARTED,
                null, 0, 0, null, null, LearningDtos.ResultPublicationStatus.UNPUBLISHED,
                LearningDtos.ResultPublicationStatus.UNPUBLISHED, null, null, null, null, null,
                LearningDtos.HomeworkLatePolicy.DISALLOW, null, 1, 0, now, null, now, now);
        assessments.insertAssessment(assessment);
        int sequence = 1;
        for (ValidatedSnapshot candidate : valid) {
            assessments.insertAssessmentQuestion(new AssessmentQuestionRow(UUID.randomUUID().toString(),
                    assessmentId, candidate.aggregate().id(), candidate.version().id(), sequence++,
                    BigDecimal.ONE, candidate.snapshot(), now));
        }
        AutonomousPracticeRow created = new AutonomousPracticeRow(UUID.randomUUID().toString(),
                request.actionId().toString(), request.ownerUserId().toString(), assessmentId,
                request.criteria().topic(), request.criteria().difficulty().name(),
                request.criteria().requestedCount(), valid.size(), now);
        assessments.insertAutonomousPractice(created);
        return result(created, false);
    }

    /** 按 owner/action 查询未知执行结果；超时恢复不得另建练习。 */
    @Transactional(readOnly = true)
    public AgentAccessDtos.AutonomousPracticeResult find(AgentAccessDtos.FindAutonomousPracticeRequest request) {
        return assessments.autonomousPracticeByAction(request.actionId().toString(), request.ownerUserId().toString())
                .map(value -> result(value, true)).orElseThrow(AutonomousPracticeService::notFound);
    }

    /** 浏览器只按本人 autonomousPracticeId 读取稳定 assessment 入口，非 owner 统一安全 404。 */
    @Transactional(readOnly = true)
    public LearningDtos.AutonomousPracticeEntry entry(UUID autonomousPracticeId, UUID ownerUserId) {
        AutonomousPracticeRow row = assessments.ownedAutonomousPractice(
                autonomousPracticeId.toString(), ownerUserId.toString()).orElseThrow(AutonomousPracticeService::notFound);
        AssessmentRow assessment = assessments.assessment(row.assessmentId()).orElseThrow(AutonomousPracticeService::notFound);
        return new LearningDtos.AutonomousPracticeEntry(UUID.fromString(row.id()), UUID.fromString(row.assessmentId()),
                assessment.title(), row.actualCount(), "/practice/" + row.assessmentId());
    }

    /** 复核精确版本身份与可评分性；仅显式允许补齐时放宽难度，排序不改变题目快照和写入权限。 */
    private List<ValidatedSnapshot> validateSnapshots(AgentAccessDtos.PracticeCriteria criteria,
            List<AgentAccessDtos.PracticeCandidateKey> candidates) {
        List<ValidatedSnapshot> result = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (AgentAccessDtos.PracticeCandidateKey key : candidates) {
            if (!seen.add(key.questionVersionId())) continue;
            Aggregate aggregate = questions.findActiveSystemRootAggregate(key.questionId().toString()).orElse(null);
            if (aggregate == null || !aggregate.currentVersionId().equals(key.questionVersionId().toString())) continue;
            Version version = questions.findVersion(key.questionId().toString(), key.questionVersionId().toString())
                    .orElse(null);
            if (version == null || version.stage() != QuestionModels.Stage.JUNIOR
                    || version.subject() != QuestionModels.Subject.MATHEMATICS
                    || (!criteria.allowDifficultyFallback() && version.difficulty() != criteria.difficulty())
                    || !autoScorable(version)) continue;
            QuestionDtos.VersionResponse snapshot = snapshot(version);
            result.add(new ValidatedSnapshot(aggregate, version, snapshot));
        }
        // 未指定难度时先保留优先难度，数量不足再按简单、困难补齐；确认时同样按此规则复核。
        result.sort(java.util.Comparator.comparingInt(value -> difficultyPriority(criteria, value.version())));
        return result;
    }

    /** 返回组题排序等级；难度补齐只影响排序与难度过滤，不改变权限、主题或评分边界。 */
    private static int difficultyPriority(AgentAccessDtos.PracticeCriteria criteria, Version version) {
        if (version.difficulty() == criteria.difficulty()) return 0;
        return version.difficulty() == QuestionModels.Difficulty.EASY ? 1 : 2;
    }

    private boolean autoScorable(Version version) {
        if (AUTO_TYPES.contains(version.type())) return true;
        if (version.type() != QuestionModels.Type.COMPOSITE) return false;
        var children = questions.versionChildren(version.id());
        return !children.isEmpty() && children.stream().allMatch(child -> questions
                .findVersion(child.questionId(), child.versionId()).map(value -> AUTO_TYPES.contains(value.type()))
                .orElse(false));
    }

    private QuestionDtos.VersionResponse snapshot(Version version) {
        List<QuestionDtos.ChildResponse> children = questions.versionChildren(version.id()).stream().map(link -> {
            Version child = questions.findVersion(link.questionId(), link.versionId()).orElseThrow();
            return new QuestionDtos.ChildResponse(child.questionId(), child.id(), child.versionNumber(),
                    link.sequence(), child.type(), child.difficulty(), child.stage(), child.grade(), child.subject(),
                    child.stem(), options(child), child.answers(), child.analysis(), List.of(), media(child), child.createdAt());
        }).toList();
        return new QuestionDtos.VersionResponse(version.id(), version.versionNumber(), version.type(),
                version.difficulty(), version.stage(), version.grade(), version.subject(), version.stem(),
                options(version), version.answers(), version.analysis(), List.of(), media(version), null,
                children, version.createdAt());
    }

    private static List<QuestionDtos.OptionInput> options(Version version) {
        return version.options().stream().map(value -> new QuestionDtos.OptionInput(value.key(), value.content())).toList();
    }

    private static List<QuestionDtos.MediaResponse> media(Version version) {
        return version.media().stream().map(value -> new QuestionDtos.MediaResponse(value.id(), value.mediaId(),
                value.purpose(), value.sequence())).toList();
    }

    private AgentAccessDtos.ValidatedPracticeCandidate projection(ValidatedSnapshot value) {
        return new AgentAccessDtos.ValidatedPracticeCandidate(UUID.fromString(value.aggregate().id()),
                UUID.fromString(value.version().id()), value.version().type(), value.version().difficulty());
    }

    private static AgentAccessDtos.AutonomousPracticeResult result(AutonomousPracticeRow value, boolean replayed) {
        return new AgentAccessDtos.AutonomousPracticeResult(UUID.fromString(value.id()),
                UUID.fromString(value.assessmentId()), "/practice/" + value.assessmentId(), replayed);
    }

    /** assessment.title 最多 160 字符；保留主题前缀并为固定用途后缀预留空间。 */
    private static String assessmentTitle(String topic) {
        String suffix = "自主练习";
        return topic.substring(0, Math.min(topic.length(), 160 - suffix.length())) + suffix;
    }

    private static QuestionException notFound() {
        return new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
    }

    private record ValidatedSnapshot(Aggregate aggregate, Version version, QuestionDtos.VersionResponse snapshot) {}
}
