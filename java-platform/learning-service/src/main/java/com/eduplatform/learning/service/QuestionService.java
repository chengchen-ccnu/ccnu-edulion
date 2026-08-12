package com.eduplatform.learning.service;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.Aggregate;
import com.eduplatform.learning.domain.QuestionModels.KnowledgePoint;
import com.eduplatform.learning.domain.QuestionModels.Media;
import com.eduplatform.learning.domain.QuestionModels.Option;
import com.eduplatform.learning.domain.QuestionModels.Version;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.web.QuestionDtos;
import com.eduplatform.shared.api.FieldError;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 教师题目聚合服务：所有权、幂等、不可变版本、乐观锁与媒体 Outbox 在这里收口。
 *
 * <p>任何更新先插入新版本，再以 revision 原子移动聚合指针；旧版本及其媒体引用继续保留，避免
 * T11 快照或审计历史因“替换附件”丢失。T09B 仍不提供学生读取方法。</p>
 */
@Service
@ConditionalOnBean(QuestionRepository.class)
public class QuestionService {
    private static final Pattern EMBEDDED_IMAGE = Pattern.compile("(?is)<img\\b|!\\[[^]]*]\\(\\s*(?:https?:|data:)");
    private final QuestionRepository repository;
    private final MediaClient mediaClient;
    private final LearningEvents events;
    private final ObjectMapper mapper;

    public QuestionService(QuestionRepository repository, MediaClient mediaClient, LearningEvents events, ObjectMapper mapper) {
        this.repository = repository; this.mediaClient = mediaClient; this.events = events; this.mapper = mapper;
    }

    /** 创建 DRAFT 与版本 1；相同 clientRequestId/指纹返回原聚合，不再次发布引用事件。 */
    @Transactional
    public Creation create(String ownerId, String requestId, QuestionDtos.VersionInput input, String traceId) {
        return createInternal(ownerId, requestId, input, traceId, true, true);
    }

    /** 共享普通创建与受控组合题写入；结构化入口要求组合父题携带完整有序子题。 */
    private Creation createInternal(String ownerId, String requestId, QuestionDtos.VersionInput input,
            String traceId, boolean allowComposite, boolean requireCompositeChildren) {
        String fingerprint = fingerprint(input);
        var replay = repository.findIdempotency(ownerId, requestId);
        if (replay.isPresent()) {
            if (!replay.get().fingerprint().equals(fingerprint) || !"QUESTION".equals(replay.get().resourceType())) {
                throw conflict("QUESTION.IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同的题目请求");
            }
            return new Creation(get(ownerId, replay.get().resourceId()), false);
        }
        validateStructure(input, allowComposite, requireCompositeChildren);
        Prepared prepared = prepare(input, ownerId, traceId, allowComposite);
        List<PreparedChild> children = new ArrayList<>();
        for (QuestionDtos.ChildInput child : input.childItems()) {
            QuestionDtos.VersionInput childVersion = childVersion(child);
            children.add(new PreparedChild(childVersion, prepare(childVersion, ownerId, traceId, false)));
        }
        Instant now = Instant.now(); String questionId = UUID.randomUUID().toString(); String versionId = UUID.randomUUID().toString();
        List<Media> versionMedia = mediaRows(prepared.media());
        Aggregate aggregate = new Aggregate(questionId, ownerId, QuestionModels.Status.DRAFT, null, 0, now, now);
        Version version = version(versionId, questionId, 1, input, prepared.knowledge(), versionMedia, now);
        repository.insertAggregate(aggregate);
        List<QuestionRepository.ChildLink> childLinks = new ArrayList<>();
        for (PreparedChild child : children) childLinks.add(insertChild(ownerId, questionId, child, traceId, now));
        repository.insertVersion(version, ownerId);
        if (!childLinks.isEmpty()) repository.insertVersionChildren(questionId, versionId, childLinks);
        repository.setInitialVersion(questionId, versionId, now);
        repository.insertIdempotency(ownerId, requestId, fingerprint, "QUESTION", questionId, now);
        bindMedia(versionMedia, traceId, now);
        return new Creation(get(ownerId, questionId), true);
    }

    /** 导入行使用任务 ID + 行号派生的请求 ID，因此 Worker 崩溃重放也不会重复生成题目。 */
    @Transactional
    public Creation createImported(String ownerId, String deterministicRequestId, QuestionDtos.VersionInput input, String traceId) {
        return createInternal(ownerId, deterministicRequestId, input, traceId, false, true);
    }

    /**
     * 在同一事务中创建组合题父题并连接已创建的有序子题；重放由父题请求 ID 和唯一子题顺序共同保护。
     * @param childQuestionIds 按历史 subQues 顺序排列的同 owner 子题 ID
     * @return 组合题父聚合；任何连接失败都会回滚父子题的当前导入事务
     */
    @Transactional
    public Creation createImportedComposite(String ownerId, String deterministicRequestId,
            QuestionDtos.VersionInput input, List<String> childQuestionIds, String traceId) {
        Creation parent = createInternal(ownerId, deterministicRequestId, input, traceId, true, false);
        if (parent.created()) repository.insertCurrentVersionChildren(parent.response().id(),
                parent.response().currentVersion().id(), childQuestionIds);
        return new Creation(get(ownerId, parent.response().id()), parent.created());
    }

    /** 写入新版本并乐观移动指针；ARCHIVED 聚合拒绝修改。 */
    @Transactional
    public QuestionDtos.QuestionResponse update(String ownerId, String questionId, long expectedRevision,
            QuestionDtos.VersionInput input, String traceId) {
        Aggregate aggregate = owned(ownerId, questionId);
        if (aggregate.status() == QuestionModels.Status.ARCHIVED) throw conflict("QUESTION.ARCHIVED", "归档题目不能修改");
        if (aggregate.revision() != expectedRevision) throw conflict("QUESTION.REVISION_CONFLICT", "题目已被更新，请刷新后重试");
        validateStructure(input, true, true);
        Prepared prepared = prepare(input, ownerId, traceId, true);
        List<PreparedChild> children = new ArrayList<>();
        for (QuestionDtos.ChildInput child : input.childItems()) {
            QuestionDtos.VersionInput childValue = childVersion(child);
            children.add(new PreparedChild(childValue, prepare(childValue, ownerId, traceId, false)));
        }
        Instant now = Instant.now();
        String versionId = UUID.randomUUID().toString(); List<Media> versionMedia = mediaRows(prepared.media());
        Version version = version(versionId, questionId, repository.nextVersionNumber(questionId), input,
                prepared.knowledge(), versionMedia, now);
        List<QuestionRepository.ChildLink> childLinks = new ArrayList<>();
        for (PreparedChild child : children) childLinks.add(insertChild(ownerId, questionId, child, traceId, now));
        repository.insertVersion(version, ownerId);
        if (!childLinks.isEmpty()) repository.insertVersionChildren(questionId, versionId, childLinks);
        if (repository.moveToVersion(questionId, ownerId, expectedRevision, versionId, now) == 0) {
            throw conflict("QUESTION.REVISION_CONFLICT", "题目已被更新，请刷新后重试");
        }
        bindMedia(versionMedia, traceId, now);
        return get(ownerId, questionId);
    }

    /** 激活或归档；重复提交已达到的状态直接返回，归档后不允许重新激活。 */
    @Transactional
    public QuestionDtos.QuestionResponse status(String ownerId, String questionId, long expectedRevision, QuestionModels.Status target) {
        if (target != QuestionModels.Status.ACTIVE && target != QuestionModels.Status.ARCHIVED) {
            throw invalid("status", "状态只能为 ACTIVE 或 ARCHIVED");
        }
        Aggregate current = owned(ownerId, questionId);
        if (current.status() == target) return get(ownerId, questionId);
        if (current.status() == QuestionModels.Status.ARCHIVED) throw conflict("QUESTION.ARCHIVED", "归档题目不能重新激活");
        if (current.revision() != expectedRevision || repository.updateStatus(questionId, ownerId, expectedRevision, target, Instant.now()) == 0) {
            throw conflict("QUESTION.REVISION_CONFLICT", "题目已被更新，请刷新后重试");
        }
        return get(ownerId, questionId);
    }

    /** 返回 owner 本人的完整当前版本；答案和解析只存在于这个教师详情边界。 */
    public QuestionDtos.QuestionResponse get(String ownerId, String questionId) {
        Aggregate aggregate = owned(ownerId, questionId);
        Version version = repository.findVersion(aggregate.currentVersionId()).orElseThrow(QuestionService::notFound);
        return response(aggregate, version);
    }

    /** 列表只从不含答案/解析的 SQL 投影构建，防止后续误接公共端点时字段泄漏。 */
    public QuestionDtos.QuestionPage list(String ownerId, int page, int size, String keyword,
            QuestionModels.Type type, QuestionModels.Status status, QuestionModels.Difficulty difficulty,
            String knowledgePointId) {
        var rows = repository.listQuestions(ownerId, page, size, keyword, type, status, difficulty, knowledgePointId);
        return new QuestionDtos.QuestionPage(rows.rows().stream().map(row -> new QuestionDtos.QuestionSummary(row.id(),
                row.status(), row.revision(), row.currentVersionId(), row.versionNumber(), row.type(), row.difficulty(),
                row.grade(), preview(row.stem()), row.knowledgePointIds(), row.createdAt(), row.updatedAt())).toList(),
                page, size, rows.total());
    }

    /** 分页返回本人题目的不可变历史摘要；只读查询不会移动当前版本指针。 */
    public QuestionDtos.VersionPage versions(String ownerId, String questionId, int page, int size) {
        Aggregate aggregate = owned(ownerId, questionId);
        var rows = repository.listVersions(questionId, aggregate.currentVersionId(), page, size);
        return new QuestionDtos.VersionPage(rows.stream().map(row -> new QuestionDtos.VersionSummary(row.id(),
                row.versionNumber(), row.type(), row.difficulty(), preview(row.stem()), row.childCount(),
                row.createdAt(), row.current())).toList(), page, size, repository.countVersions(questionId));
    }

    /** 返回属于本人聚合的指定不可变版本；读取历史不改变 aggregate revision。 */
    public QuestionDtos.VersionResponse version(String ownerId, String questionId, String versionId) {
        owned(ownerId, questionId);
        Version version = repository.findVersion(questionId, versionId).orElseThrow(QuestionService::notFound);
        return versionResponse(questionId, version);
    }

    private QuestionRepository.ChildLink insertChild(String ownerId, String parentQuestionId,
            PreparedChild child, String traceId, Instant now) {
        String questionId = UUID.randomUUID().toString(); String versionId = UUID.randomUUID().toString();
        Aggregate aggregate = new Aggregate(questionId, ownerId, QuestionModels.Status.DRAFT, null, 0, now, now);
        List<Media> media = mediaRows(child.prepared().media());
        Version version = version(versionId, questionId, 1, child.input(), child.prepared().knowledge(), media, now);
        repository.insertAggregate(aggregate, parentQuestionId);
        repository.insertVersion(version, ownerId);
        repository.setInitialVersion(questionId, versionId, now);
        bindMedia(media, traceId, now);
        return new QuestionRepository.ChildLink(questionId, versionId);
    }

    private void bindMedia(List<Media> media, String traceId, Instant now) {
        for (Media item : media) repository.insertOutbox(events.mediaReference("MediaReferenceBound", item.mediaId(),
                "QUESTION_VERSION_MEDIA", item.id(), traceId, now));
    }

    private static QuestionDtos.VersionInput childVersion(QuestionDtos.ChildInput child) {
        return new QuestionDtos.VersionInput(child.type(), child.difficulty(), child.stage(), child.grade(),
                child.subject(), child.stem(), child.options(), child.answers(), child.analysis(),
                child.knowledgePointIds(), child.media(), List.of());
    }

    private void validateStructure(QuestionDtos.VersionInput input, boolean allowComposite,
            boolean requireCompositeChildren) {
        if (input.type() == QuestionModels.Type.COMPOSITE) {
            if (!allowComposite) throw invalid("type", "此入口不允许组合题");
            if (requireCompositeChildren && input.childItems().isEmpty()) {
                throw invalid("children", "组合题必须包含至少一个结构化子题");
            }
        } else if (!input.childItems().isEmpty()) {
            throw invalid("children", "普通题不能包含组合题子题");
        }
        if (input.childItems().stream().anyMatch(child -> child.type() == QuestionModels.Type.COMPOSITE)) {
            throw invalid("children", "组合题子题不能再次嵌套组合题");
        }
    }

    private Prepared prepare(QuestionDtos.VersionInput input, String ownerId, String traceId, boolean allowComposite) {
        if (input.type() == QuestionModels.Type.COMPOSITE && !allowComposite) {
            throw invalid("type", "组合题必须通过结构化父子题契约提交");
        }
        validateContent(input);
        List<String> ids = input.knowledgePointIds().stream().map(UUID::toString).distinct().toList();
        if (ids.size() != input.knowledgePointIds().size()) throw invalid("knowledgePointIds", "知识点不能重复");
        List<KnowledgePoint> knowledge = repository.findKnowledgePoints(ids);
        if (knowledge.size() != ids.size() || knowledge.stream().anyMatch(point -> !point.enabled()
                || point.stage() != input.stage() || point.grade() != input.grade() || point.subject() != input.subject())) {
            throw invalid("knowledgePointIds", "知识点不存在、未启用或与题目学段年级学科不匹配");
        }
        Set<String> mediaIds = new HashSet<>(); Set<String> order = new HashSet<>();
        for (QuestionDtos.MediaInput item : input.media()) {
            if (item.purpose() == QuestionModels.MediaPurpose.QUESTION_IMPORT_SOURCE) throw invalid("media", "导入源不能绑定题目版本");
            if (!mediaIds.add(item.mediaId().toString())) throw invalid("media", "同一媒体不能重复绑定");
            if (!order.add(item.purpose() + ":" + item.sequence())) throw invalid("media", "同用途顺序不能重复");
            mediaClient.requireBindable(item.mediaId().toString(), item.purpose().name(), ownerId, traceId);
        }
        return new Prepared(knowledge, input.media());
    }

    /** 按题型校验选项和答案；不解析或渲染 Markdown/LaTeX，但拒绝绕过 mediaId 的嵌入图片。 */
    private void validateContent(QuestionDtos.VersionInput input) {
        if (EMBEDDED_IMAGE.matcher(input.stem()).find() || EMBEDDED_IMAGE.matcher(input.analysis()).find()
                || input.options().stream().anyMatch(value -> EMBEDDED_IMAGE.matcher(value.content()).find())) {
            throw invalid("version", "图片必须先上传并使用稳定 mediaId，不能嵌入外部或 data URL");
        }
        List<String> keys = input.options().stream().map(value -> value.key().toUpperCase(Locale.ROOT)).toList();
        if (new HashSet<>(keys).size() != keys.size()) throw invalid("options", "选项 key 不能重复");
        switch (input.type()) {
            case SINGLE_CHOICE -> {
                if (keys.size() < 2 || input.answers().size() != 1 || !keys.contains(input.answers().get(0).toUpperCase(Locale.ROOT)))
                    throw invalid("answers", "单选题必须有至少两个选项且答案为一个有效选项 key");
            }
            case MULTIPLE_CHOICE -> {
                Set<String> answers = new HashSet<>(input.answers().stream().map(value -> value.toUpperCase(Locale.ROOT)).toList());
                if (keys.size() < 2 || answers.size() < 2 || answers.size() != input.answers().size() || !keys.containsAll(answers))
                    throw invalid("answers", "多选题必须有至少两个不重复的有效选项答案");
            }
            case TRUE_FALSE -> {
                String answer = input.answers().get(0).toUpperCase(Locale.ROOT);
                if (!keys.isEmpty() || input.answers().size() != 1 || !(answer.equals("TRUE") || answer.equals("FALSE")))
                    throw invalid("answers", "判断题不含选项，答案必须为 TRUE 或 FALSE");
            }
            case FILL_BLANK, SHORT_ANSWER -> {
                if (!keys.isEmpty()) throw invalid("options", "填空题和简答题不能包含选项");
            }
            // 组合父题同样保留结构化选项与答案；子题由独立完整输入逐项校验。
            case COMPOSITE -> { }
        }
    }

    private List<Media> mediaRows(List<QuestionDtos.MediaInput> values) {
        List<Media> rows = new ArrayList<>();
        for (QuestionDtos.MediaInput value : values) rows.add(new Media(UUID.randomUUID().toString(),
                value.mediaId().toString(), value.purpose(), value.sequence()));
        return rows;
    }

    private Version version(String id, String questionId, int number, QuestionDtos.VersionInput input,
            List<KnowledgePoint> knowledge, List<Media> media, Instant now) {
        return new Version(id, questionId, number, input.type(), input.difficulty(), input.stage(), input.grade(),
                input.subject(), input.stem(), input.options().stream().map(value -> new Option(value.key(), value.content())).toList(),
                List.copyOf(input.answers()), input.analysis(), knowledge, media, now);
    }

    private Aggregate owned(String ownerId, String questionId) {
        return repository.findRootAggregate(questionId, ownerId).orElseThrow(QuestionService::notFound);
    }

    private QuestionDtos.QuestionResponse response(Aggregate aggregate, Version version) {
        return new QuestionDtos.QuestionResponse(aggregate.id(), aggregate.status(), aggregate.revision(),
                versionResponse(aggregate.id(), version), aggregate.createdAt(), aggregate.updatedAt());
    }

    private QuestionDtos.VersionResponse versionResponse(String questionId, Version version) {
        List<QuestionDtos.KnowledgePointResponse> knowledge = version.knowledgePoints().stream()
                .map(value -> KnowledgePointService.response(value, List.of())).toList();
        var responseVersion = new QuestionDtos.VersionResponse(version.id(), version.versionNumber(), version.type(),
                version.difficulty(), version.stage(), version.grade(), version.subject(), version.stem(),
                version.options().stream().map(value -> new QuestionDtos.OptionInput(value.key(), value.content())).toList(),
                version.answers(), version.analysis(), knowledge, version.media().stream().map(value ->
                        new QuestionDtos.MediaResponse(value.id(), value.mediaId(), value.purpose(), value.sequence())).toList(),
                repository.findLegacySourceByQuestion(questionId).map(source -> new QuestionDtos.LegacySourceResponse(
                        source.sourceSystem(), source.externalId(), source.originalDifficulty(), source.pathEdition(),
                        source.jsonEdition(), source.pathGrade(), source.jsonGrade(),
                        source.issueCodes().contains("PATH_JSON_METADATA_CONFLICT"))).orElse(null),
                repository.versionChildren(version.id()).stream().map(child -> childResponse(child)).toList(),
                version.createdAt());
        return responseVersion;
    }

    private QuestionDtos.ChildResponse childResponse(QuestionRepository.ChildSnapshotRow child) {
        Version version = repository.findVersion(child.questionId(), child.versionId()).orElseThrow(QuestionService::notFound);
        return new QuestionDtos.ChildResponse(child.questionId(), version.id(), version.versionNumber(), child.sequence(),
                version.type(), version.difficulty(), version.stage(), version.grade(), version.subject(), version.stem(),
                version.options().stream().map(value -> new QuestionDtos.OptionInput(value.key(), value.content())).toList(),
                version.answers(), version.analysis(), version.knowledgePoints().stream()
                        .map(value -> KnowledgePointService.response(value, List.of())).toList(),
                version.media().stream().map(value -> new QuestionDtos.MediaResponse(value.id(), value.mediaId(),
                        value.purpose(), value.sequence())).toList(), version.createdAt());
    }

    private String fingerprint(Object value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(value); byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) { throw new IllegalStateException("Question fingerprint failed", exception); }
    }

    private static String preview(String value) { return value.length() <= 240 ? value : value.substring(0, 240); }
    private static QuestionException invalid(String field, String reason) {
        return new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED", "请求参数不合法",
                List.of(new FieldError(field, reason)));
    }
    private static QuestionException conflict(String code, String message) { return new QuestionException(HttpStatus.CONFLICT, code, message); }
    private static QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND, "QUESTION.NOT_FOUND", "题目不存在或不可见"); }

    private record Prepared(List<KnowledgePoint> knowledge, List<QuestionDtos.MediaInput> media) {}
    private record PreparedChild(QuestionDtos.VersionInput input, Prepared prepared) {}
    public record Creation(QuestionDtos.QuestionResponse response, boolean created) {}
}
