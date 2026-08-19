package com.eduplatform.learning.service;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.ImportTask;
import com.eduplatform.learning.domain.QuestionModels.LegacyBatch;
import com.eduplatform.learning.domain.QuestionModels.LegacySource;
import com.eduplatform.learning.domain.QuestionModels.LegacyReviewItem;
import com.eduplatform.learning.importing.LegacyQuestionJsonParser;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.persistence.KnowledgeGraphRepository;
import com.eduplatform.learning.web.QuestionDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * T09A/T09B 受控历史 JSON 编排：任务上限、批次幂等、来源幂等、dry-run、可恢复复核与精确清理。
 *
 * <p>每次调用最多 20 项并顺序处理；跨请求并发由唯一键和事务锁收口。原始 JSON 与外部 URL
 * 只存在于当前调用栈，持久化和事件仅包含哈希、来源键、计数及问题码。</p>
 */
@Service
@ConditionalOnBean(QuestionRepository.class)
public class LegacyQuestionImportService {
    private final QuestionRepository repository; private final LegacyQuestionJsonParser parser;
    private final QuestionService questions; private final MediaClient media; private final LearningEvents events;
    private final LearningProperties properties; private final ObjectMapper mapper;
    private final KnowledgeGraphRepository knowledgeGraph;
    private final TransactionTemplate itemTransactions;

    public LegacyQuestionImportService(QuestionRepository repository, LegacyQuestionJsonParser parser,
            QuestionService questions, MediaClient media, LearningEvents events, LearningProperties properties,
            ObjectMapper mapper, PlatformTransactionManager transactionManager, KnowledgeGraphRepository knowledgeGraph) {
        this.repository = repository; this.parser = parser; this.questions = questions; this.media = media;
        this.events = events; this.properties = properties; this.mapper = mapper; this.knowledgeGraph = knowledgeGraph;
        this.itemTransactions = new TransactionTemplate(transactionManager);
        this.itemTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** 创建空的受控任务；maxItems 是不可突破的硬上限，接口不接受历史目录路径。 */
    @Transactional
    public QuestionImportService.Creation create(String ownerId, QuestionDtos.CreateLegacyImportRequest request) {
        String fingerprint = sha256(request.sourceSystem() + "\0" + request.sourceRootFingerprint() + "\0"
                + request.dryRun() + "\0" + request.maxItems());
        var replay = repository.findImportByRequest(ownerId, request.clientRequestId().toString());
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(fingerprint)) throw conflict("QUESTION.IDEMPOTENCY_CONFLICT",
                    "clientRequestId 已用于不同的历史导入任务");
            return new QuestionImportService.Creation(response(replay.get()), false);
        }
        Instant now = Instant.now();
        ImportTask task = new ImportTask(UUID.randomUUID().toString(), ownerId, request.clientRequestId().toString(),
                fingerprint, null, QuestionModels.ImportFormat.LEGACY_JSON, QuestionModels.ImportStatus.PENDING,
                0, request.maxItems(), 0, 0, 0, 0, 0, request.sourceSystem(), request.sourceRootFingerprint(),
                request.dryRun(), request.maxItems(), null, null, now.plus(properties.importSettings().retention()),
                null, null, now, now);
        repository.insertImportTask(task);
        return new QuestionImportService.Creation(response(task), true);
    }

    /**
     * 处理一个有界批次并返回无内容结果。相同 batchId/指纹返回原结果；不同指纹返回 409。
     * 实际导入时图片逐张交给 media-service，子题先按顺序建立，再连接组合题父版本。
     */
    @Transactional
    public QuestionDtos.LegacyImportBatchResult submit(String ownerId, String taskId,
            QuestionDtos.LegacyImportBatchRequest request, String traceId) {
        ImportTask task = ownedForUpdate(ownerId, taskId);
        if (task.sourceFormat() != QuestionModels.ImportFormat.LEGACY_JSON) throw notFound();
        String fingerprint = fingerprint(request);
        var replay = repository.findLegacyBatch(taskId, request.batchId().toString());
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(fingerprint)) throw conflict("QUESTION.IDEMPOTENCY_CONFLICT",
                    "batchId 已用于不同的历史导入批次");
            return readResult(replay.get(), true);
        }
        if (terminal(task.status())) throw conflict("QUESTION_IMPORT.TASK_TERMINAL", "历史导入任务已经结束");
        if (task.processedRows() + request.items().size() > task.maxItems()) throw invalid("items", "批次会超过任务 maxItems 上限");

        boolean finalBatch = request.finalBatch() || task.processedRows() + request.items().size() == task.maxItems();
        List<QuestionDtos.LegacyImportItemResult> results = new ArrayList<>();
        int imported = 0, valid = 0, review = 0, skipped = 0, failed = 0;
        for (QuestionDtos.LegacyImportItem item : request.items()) {
            validateItem(item);
            LegacySource existing = repository.findLegacySource(ownerId, task.sourceSystem(), item.externalId(), task.dryRun()).orElse(null);
            if (existing != null) {
                if (existing.contentSha256().equals(item.contentSha256())) {
                    persistReview(task, request.batchId().toString(), item, existing, QuestionModels.ImportOutcome.SKIPPED,
                            existing.questionId(), existing.issueCodes());
                    results.add(new QuestionDtos.LegacyImportItemResult(item.externalId(), QuestionModels.ImportOutcome.SKIPPED,
                            existing.questionId(), existing.issueCodes())); skipped++;
                } else {
                    repository.insertLegacyIssue(taskId, request.batchId().toString(), item.externalId(),
                            item.contentSha256(), "SOURCE_CONTENT_CONFLICT", Instant.now());
                    persistReview(task, request.batchId().toString(), item, null, QuestionModels.ImportOutcome.NEEDS_REVIEW,
                            null, List.of("SOURCE_CONTENT_CONFLICT"));
                    results.add(result(item.externalId(), QuestionModels.ImportOutcome.NEEDS_REVIEW, null,
                            List.of("SOURCE_CONTENT_CONFLICT"))); review++;
                }
                continue;
            }
            LegacyQuestionJsonParser.Parsed parsed = parser.parse(item.json(), item.pathEdition(), item.pathGrade());
            if (parsed.review()) {
                persistSource(task, request, item, parsed, QuestionModels.ImportOutcome.NEEDS_REVIEW, null);
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.NEEDS_REVIEW, null, parsed.issueCodes())); review++; continue;
            }
            ResolvedCandidate resolved = resolveTags(task.sourceSystem(), parsed.candidate());
            if (!resolved.issues().isEmpty()) {
                persistSource(task, request, item, parsed, QuestionModels.ImportOutcome.NEEDS_REVIEW, null, resolved.issues());
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.NEEDS_REVIEW, null, resolved.issues())); review++; continue;
            }
            LegacyQuestionJsonParser.Parsed accepted = new LegacyQuestionJsonParser.Parsed(resolved.candidate(), List.of(), parsed.originalDifficulty(),
                    parsed.jsonEdition(), parsed.jsonGrade(), parsed.grade());
            if (task.dryRun()) {
                persistSource(task, request, item, accepted, QuestionModels.ImportOutcome.VALID, null);
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.VALID, null, List.of())); valid++; continue;
            }
            try {
                String questionId = itemTransactions.execute(status -> {
                    String created = importCandidate(task, request.batchId().toString(), item.externalId(), accepted.candidate(), traceId);
                    persistSource(task, request, item, accepted, QuestionModels.ImportOutcome.IMPORTED, created);
                    return created;
                });
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.IMPORTED, questionId, List.of())); imported++;
            } catch (QuestionException dependency) {
                String issue = accepted.candidate().imageUrls().isEmpty() ? "FIELD_INVALID" : "EXTERNAL_IMAGE_TRANSFER_FAILED";
                persistSource(task, request, item, accepted, QuestionModels.ImportOutcome.NEEDS_REVIEW, null,
                        List.of(issue));
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.NEEDS_REVIEW, null,
                        List.of(issue))); review++;
            } catch (RuntimeException failure) {
                persistReview(task, request.batchId().toString(), item, null, QuestionModels.ImportOutcome.FAILED,
                        null, List.of("FIELD_INVALID"));
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.FAILED, null, List.of("FIELD_INVALID"))); failed++;
            }
        }
        Instant now = Instant.now(); String resultJson = write(results);
        LegacyBatch batch = new LegacyBatch(taskId, request.batchId().toString(), fingerprint, finalBatch,
                results.size(), imported, valid, review, skipped, failed, resultJson, now);
        repository.insertLegacyBatch(batch);
        repository.applyLegacyBatchProgress(taskId, batch, finalBatch, now.plus(properties.importSettings().retention()), now);
        repository.insertOutbox(events.legacyBatchProcessed(taskId, batch.batchId(), batch.processedCount(),
                batch.importedCount(), batch.reviewCount(), batch.skippedCount(), batch.failedCount(), task.dryRun(), traceId, now));
        return result(taskId, batch, false, results);
    }

    /** 分页返回可刷新复核项；仅本人 LEGACY_JSON 任务可见，投影不含原题内容。 */
    public QuestionDtos.LegacyReviewPage items(String ownerId, String taskId, int page, int size,
            QuestionModels.ImportOutcome outcome) {
        ImportTask task = repository.findImportTask(taskId, ownerId).orElseThrow(LegacyQuestionImportService::notFound);
        if (task.sourceFormat() != QuestionModels.ImportFormat.LEGACY_JSON) throw notFound();
        var rows = repository.listLegacyReviewItems(ownerId, taskId, page, size, outcome);
        return new QuestionDtos.LegacyReviewPage(rows.stream().map(this::reviewResponse).toList(), page, size,
                repository.countLegacyReviewItems(ownerId, taskId, outcome));
    }

    /** 精确返回一个复核项；itemId 不属于本人任务时按不存在处理。 */
    public QuestionDtos.LegacyReviewItem item(String ownerId, String taskId, String itemId) {
        ImportTask task = repository.findImportTask(taskId, ownerId).orElseThrow(LegacyQuestionImportService::notFound);
        if (task.sourceFormat() != QuestionModels.ImportFormat.LEGACY_JSON) throw notFound();
        return reviewResponse(repository.findLegacyReviewItem(ownerId, taskId, itemId)
                .orElseThrow(LegacyQuestionImportService::notFound));
    }

    /**
     * 显式解决 NEEDS_REVIEW 项：结构化修正后创建题目，或确认跳过。
     * expectedRevision 防止并发覆盖；clientRequestId/完整请求指纹保证网络重放不重复创建题目或累计计数。
     */
    @Transactional
    public QuestionDtos.LegacyReviewItem resolve(String ownerId, String taskId, String itemId,
            QuestionDtos.ResolveLegacyItemRequest request, String traceId) {
        String fingerprint = fingerprint(request);
        String clientRequestId = request.clientRequestId().toString();
        var replay = replayResolution(ownerId, taskId, itemId, clientRequestId, fingerprint);
        if (replay.isPresent()) return replay.get();

        ImportTask task = ownedForUpdate(ownerId, taskId);
        if (task.sourceFormat() != QuestionModels.ImportFormat.LEGACY_JSON) throw notFound();
        // 两个相同请求可能在任务锁前都未读到幂等行；获得锁后必须重查首个事务已提交的结果。
        replay = replayResolutionForUpdate(ownerId, taskId, itemId, clientRequestId, fingerprint);
        if (replay.isPresent()) return replay.get();
        if (!reviewable(task.status())) {
            throw conflict("QUESTION_IMPORT.TASK_NOT_REVIEWABLE", "历史导入任务未进入可人工复核终态");
        }
        LegacyReviewItem current = repository.findLegacyReviewItemForUpdate(ownerId, taskId, itemId)
                .orElseThrow(LegacyQuestionImportService::notFound);
        if (current.revision() != request.expectedRevision()) {
            throw conflict("QUESTION_IMPORT.ITEM_REVISION_CONFLICT", "复核项已被更新，请刷新后重试");
        }
        if (current.outcome() != QuestionModels.ImportOutcome.NEEDS_REVIEW || current.resolutionAction() != null) {
            throw conflict("QUESTION_IMPORT.ITEM_ALREADY_RESOLVED", "复核项已是终态，不能再次解决");
        }
        QuestionModels.ResolutionAction action = QuestionModels.ResolutionAction.valueOf(request.action().name());
        String questionId = null;
        if (action == QuestionModels.ResolutionAction.IMPORT_CORRECTED) {
            if (request.correctedVersion() == null) throw invalid("correctedVersion", "修正后导入必须提交完整结构化题目");
            String questionRequestId = deterministic(taskId, itemId + ":resolution:" + request.clientRequestId());
            questionId = questions.create(ownerId, questionRequestId, request.correctedVersion(), traceId).response().id();
        } else if (request.correctedVersion() != null) {
            throw invalid("correctedVersion", "确认跳过时 correctedVersion 必须为 null");
        }
        Instant now = Instant.now();
        QuestionModels.ImportOutcome outcome = action == QuestionModels.ResolutionAction.IMPORT_CORRECTED
                ? QuestionModels.ImportOutcome.IMPORTED : QuestionModels.ImportOutcome.SKIPPED;
        if (repository.resolveLegacyReviewItem(ownerId, taskId, itemId, request.expectedRevision(), action,
                outcome, questionId, now) == 0) {
            throw conflict("QUESTION_IMPORT.ITEM_REVISION_CONFLICT", "复核项已被更新，请刷新后重试");
        }
        if (current.sourceRefId() != null) repository.resolveLegacySource(ownerId, current.sourceRefId(), outcome,
                questionId, now);
        repository.insertLegacyResolution(ownerId, taskId, itemId, clientRequestId,
                fingerprint, action, questionId, now);
        if (repository.applyLegacyResolution(taskId, action, now) == 0) {
            throw conflict("QUESTION_IMPORT.TASK_NOT_REVIEWABLE", "历史导入任务未进入可人工复核终态");
        }
        return item(ownerId, taskId, itemId);
    }

    /**
     * 精确清理一个小样本任务创建的 DRAFT 题目，先写媒体 Released Outbox，再删除本域图。
     * dry-run 只清理来源摘要；重复调用返回 alreadyCleaned=true。
     */
    @Transactional
    public QuestionDtos.LegacyImportCleanupResult cleanup(String ownerId, String taskId, String traceId) {
        ImportTask task = ownedForUpdate(ownerId, taskId);
        if (task.sourceFormat() != QuestionModels.ImportFormat.LEGACY_JSON) throw notFound();
        if (task.status() == QuestionModels.ImportStatus.CLEANED) return new QuestionDtos.LegacyImportCleanupResult(taskId, 0, 0, 0, true);
        List<String> ids = repository.legacyCreatedQuestionIds(taskId, ownerId);
        var versionMedia = repository.mediaForQuestions(ids); Instant now = Instant.now();
        versionMedia.forEach(item -> repository.insertOutbox(events.mediaReference("MediaReferenceReleased", item.mediaId(),
                "QUESTION_VERSION_MEDIA", item.id(), traceId, now)));
        var counts = repository.deleteLegacyTaskQuestions(taskId, ownerId, now);
        return new QuestionDtos.LegacyImportCleanupResult(taskId, counts.questionsDeleted(),
                counts.sourceReferencesDeleted(), versionMedia.size(), false);
    }

    private String importCandidate(ImportTask task, String batchId, String externalId,
            LegacyQuestionJsonParser.Candidate candidate, String traceId) {
        List<String> childIds = new ArrayList<>(); int childIndex = 0;
        for (LegacyQuestionJsonParser.Candidate child : candidate.children()) {
            childIndex++;
            var input = withMedia(task, externalId + ":child:" + childIndex, child, traceId);
            String requestId = deterministic(task.id(), externalId + ":child:" + childIndex);
            String childId = questions.createImported(task.ownerId(), requestId, input, traceId).response().id();
            repository.applyLegacyKnowledgeMetadata(childId, child.tags());
            childIds.add(childId);
        }
        QuestionDtos.VersionInput parent = withMedia(task, externalId + ":parent", candidate, traceId);
        String requestId = deterministic(task.id(), externalId + ":parent");
        String created = candidate.children().isEmpty()
                ? questions.createImported(task.ownerId(), requestId, parent, traceId).response().id()
                : questions.createImportedComposite(task.ownerId(), requestId, parent, childIds, traceId).response().id();
        repository.applyLegacyKnowledgeMetadata(created, candidate.tags());
        return created;
    }

    /** 逐题、逐子题按 sourceSystem+knowId 解析 UUID，并校验名称、类型和已确认学段映射。 */
    private ResolvedCandidate resolveTags(String sourceSystem, LegacyQuestionJsonParser.Candidate candidate) {
        List<String> issues = new ArrayList<>(); List<LegacyQuestionJsonParser.LegacyTag> tags = new ArrayList<>();
        for (var tag : candidate.tags()) {
            var point = knowledgeGraph.findSourcePoint(sourceSystem, tag.knowId());
            if (point.isEmpty()) { issues.add("KNOWLEDGE_POINT_UNKNOWN"); continue; }
            boolean nameMatches = point.get().name().equals(tag.knowName());
            boolean typeMatches = point.get().knowledgeTypeId().equals(tag.knowTypeId());
            boolean stageMatches = "JUNIOR".equals(point.get().normalizedStage());
            if (!nameMatches) issues.add("KNOWLEDGE_POINT_NAME_CONFLICT");
            if (!typeMatches) issues.add("KNOWLEDGE_POINT_TYPE_CONFLICT");
            if (!stageMatches) issues.add("KNOWLEDGE_POINT_STAGE_MISMATCH");
            if (nameMatches && typeMatches && stageMatches) tags.add(tag.resolved(point.get().id()));
        }
        List<LegacyQuestionJsonParser.Candidate> children = new ArrayList<>();
        for (var child : candidate.children()) {
            ResolvedCandidate resolved = resolveTags(sourceSystem, child); issues.addAll(resolved.issues());
            if (resolved.candidate() != null) children.add(resolved.candidate());
        }
        issues = issues.stream().distinct().toList();
        if (!issues.isEmpty()) return new ResolvedCandidate(null, issues);
        var resolved = candidate.withResolvedTags(tags);
        return new ResolvedCandidate(new LegacyQuestionJsonParser.Candidate(resolved.input(), resolved.imageUrls(),
                List.copyOf(children), resolved.tags()), List.of());
    }

    private QuestionDtos.VersionInput withMedia(ImportTask task, String sourceKey,
            LegacyQuestionJsonParser.Candidate candidate, String traceId) {
        List<QuestionDtos.MediaInput> rows = new ArrayList<>(); int sequence = 0;
        for (String url : candidate.imageUrls()) {
            sequence++;
            String mediaId = media.transferExternalImage(deterministic(task.id(), sourceKey + ":image:" + sequence),
                    url, task.ownerId(), traceId);
            rows.add(new QuestionDtos.MediaInput(UUID.fromString(mediaId), QuestionModels.MediaPurpose.QUESTION_IMAGE, sequence));
        }
        var value = candidate.input();
        return new QuestionDtos.VersionInput(value.type(), value.difficulty(), value.stage(), value.grade(), value.subject(),
                value.stem(), value.options(), value.answers(), value.analysis(), value.knowledgePointIds(), rows);
    }

    private void persistSource(ImportTask task, QuestionDtos.LegacyImportBatchRequest batch,
            QuestionDtos.LegacyImportItem item, LegacyQuestionJsonParser.Parsed parsed,
            QuestionModels.ImportOutcome outcome, String questionId) {
        persistSource(task, batch, item, parsed, outcome, questionId, parsed.issueCodes());
    }
    private void persistSource(ImportTask task, QuestionDtos.LegacyImportBatchRequest batch,
            QuestionDtos.LegacyImportItem item, LegacyQuestionJsonParser.Parsed parsed,
            QuestionModels.ImportOutcome outcome, String questionId, List<String> issueCodes) {
        Instant now = Instant.now();
        LegacySource source = new LegacySource(UUID.randomUUID().toString(), task.id(), batch.batchId().toString(),
                task.ownerId(), task.sourceSystem(), item.externalId(), task.dryRun(), item.contentSha256(),
                sha256(item.relativePath().replace('\\', '/')), item.pathEdition(), parsed.jsonEdition(), item.pathGrade(), parsed.jsonGrade(),
                parsed.originalDifficulty(), outcome, questionId, issueCodes, now, now);
        repository.insertLegacySource(source);
        persistReview(task, batch.batchId().toString(), item, source, outcome, questionId, issueCodes);
    }

    private void persistReview(ImportTask task, String batchId, QuestionDtos.LegacyImportItem item,
            LegacySource source, QuestionModels.ImportOutcome outcome, String questionId, List<String> issueCodes) {
        Instant now = Instant.now();
        repository.insertLegacyReviewItem(new LegacyReviewItem(UUID.randomUUID().toString(), task.id(), batchId,
                task.ownerId(), source == null ? null : source.id(), item.externalId(), item.contentSha256(),
                source == null ? item.pathEdition() : source.pathEdition(), source == null ? null : source.jsonEdition(),
                source == null ? item.pathGrade() : source.pathGrade(), source == null ? null : source.jsonGrade(),
                source == null ? null : source.originalDifficulty(), outcome, questionId, List.copyOf(issueCodes),
                0, null, null, now, now));
    }

    private void validateItem(QuestionDtos.LegacyImportItem item) {
        String normalized = item.relativePath().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*") || normalized.contains("../")
                || normalized.equals("..") || !normalized.toLowerCase().endsWith(".json")) {
            throw invalid("relativePath", "必须是安全的 JSON 相对路径");
        }
        byte[] bytes;
        try { bytes = mapper.writeValueAsBytes(item.json()); } catch (Exception exception) { throw invalid("json", "JSON 无法编码"); }
        if (bytes.length > 256 * 1024) throw invalid("json", "单项 JSON 超过 256 KiB");
        // contentSha256 是只读脚本对原始 UTF-8 文件字节计算的来源指纹；JSON 解析会改变空白，服务端不能伪造等价原文复算。
        String embeddedId = item.json().path("exerciseID").asString();
        if (!embeddedId.isBlank() && !embeddedId.equals(item.externalId())) throw invalid("externalId", "externalId 与 JSON exerciseID 不一致");
    }

    private ImportTask ownedForUpdate(String ownerId, String taskId) {
        return repository.findImportTaskForUpdate(taskId, ownerId).orElseThrow(LegacyQuestionImportService::notFound);
    }

    /**
     * 读取已提交的人工解决结果；相同 clientRequestId 必须绑定同一复核项和完整请求指纹。
     * @return 已存在时返回第一次提交后的安全复核投影，否则返回空
     */
    private java.util.Optional<QuestionDtos.LegacyReviewItem> replayResolution(String ownerId, String taskId,
            String itemId, String clientRequestId, String fingerprint) {
        var replay = repository.findLegacyResolution(ownerId, clientRequestId);
        return replayResolution(ownerId, taskId, itemId, fingerprint, replay);
    }

    /** 任务锁后的幂等二次查询使用当前读，避免 MySQL REPEATABLE READ 沿用锁前快照。 */
    private java.util.Optional<QuestionDtos.LegacyReviewItem> replayResolutionForUpdate(String ownerId, String taskId,
            String itemId, String clientRequestId, String fingerprint) {
        var replay = repository.findLegacyResolutionForUpdate(ownerId, clientRequestId);
        if (replay.isEmpty()) return java.util.Optional.empty();
        validateReplay(itemId, fingerprint, replay.get());
        // 幂等行必须与复核项一起使用当前读，否则 MySQL REPEATABLE READ 仍可能返回 revision=0 的旧快照。
        return java.util.Optional.of(reviewResponse(repository.findLegacyReviewItemForUpdate(ownerId, taskId, itemId)
                .orElseThrow(LegacyQuestionImportService::notFound)));
    }

    private java.util.Optional<QuestionDtos.LegacyReviewItem> replayResolution(String ownerId, String taskId,
            String itemId, String fingerprint,
            java.util.Optional<QuestionRepository.LegacyResolutionRow> replay) {
        if (replay.isEmpty()) return java.util.Optional.empty();
        validateReplay(itemId, fingerprint, replay.get());
        return java.util.Optional.of(item(ownerId, taskId, itemId));
    }

    private static void validateReplay(String itemId, String fingerprint,
            QuestionRepository.LegacyResolutionRow replay) {
        if (!replay.itemId().equals(itemId) || !replay.fingerprint().equals(fingerprint)) {
            throw conflict("QUESTION.IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同的复核解决请求");
        }
    }

    private QuestionDtos.LegacyReviewItem reviewResponse(LegacyReviewItem item) {
        List<QuestionDtos.LegacyIssue> issues = item.issueCodes().stream()
                .map(code -> new QuestionDtos.LegacyIssue(code, issueMessage(code))).toList();
        QuestionDtos.LegacyResolution resolution = item.resolutionAction() == null ? null
                : new QuestionDtos.LegacyResolution(QuestionDtos.ResolutionAction.valueOf(item.resolutionAction().name()),
                        item.resolvedAt());
        return new QuestionDtos.LegacyReviewItem(item.id(), item.revision(), item.externalId(), item.outcome(),
                item.questionId(), repository.findImportTask(item.importTaskId()).map(ImportTask::sourceSystem).orElse("LEGACY"),
                item.originalDifficulty(), item.pathEdition(), item.jsonEdition(), item.pathGrade(), item.jsonGrade(),
                issues, resolution, item.createdAt(), item.updatedAt());
    }

    private static String issueMessage(String code) {
        return switch (code) {
            case "EMBEDDED_OPTIONS_AMBIGUOUS" -> "内嵌选项无法可靠拆分";
            case "IMPLICIT_MULTI_PART" -> "题干疑似包含未显式建模的多问";
            case "SUBQUESTION_ANSWER_MISSING" -> "子题缺少可确认答案";
            case "PATH_JSON_METADATA_CONFLICT" -> "目录与 JSON 教材或年级信息冲突";
            case "SOURCE_CONTENT_CONFLICT" -> "相同来源标识的内容指纹发生变化";
            case "TYPE_UNSUPPORTED" -> "历史题型无法安全映射";
            case "HTML_UNSAFE" -> "历史 HTML 含不安全或被移除结构";
            case "EXTERNAL_IMAGE_TRANSFER_FAILED" -> "外部图片转存失败";
            case "KNOWLEDGE_POINT_UNKNOWN" -> "来源 knowId 未找到已导入知识点";
            case "KNOWLEDGE_POINT_NAME_CONFLICT" -> "tag 名称与知识点来源名称不一致";
            case "KNOWLEDGE_POINT_TYPE_CONFLICT" -> "tag 类型与知识点来源类型不一致";
            case "KNOWLEDGE_POINT_STAGE_MISMATCH" -> "知识点尚未确认映射为题目学段";
            case "KNOWLEDGE_POINT_DUPLICATE" -> "同一题目 tags 含重复 knowId";
            case "KNOWLEDGE_POINT_BINDING_INVALID" -> "题目 tag 缺少必要来源字段或字段类型无效";
            default -> "字段无法安全转换，需要人工复核";
        };
    }

    private QuestionDtos.ImportTaskResponse response(ImportTask task) {
        return new QuestionDtos.ImportTaskResponse(task.id(), task.sourceMediaId(), task.sourceFormat(), task.sourceSystem(), task.dryRun(),
                task.maxItems(), task.status(), task.progress(), task.totalRows(), task.processedRows(), task.successCount(), task.reviewCount(),
                repository.countResolvedLegacyItems(task.id()), task.skippedCount(), task.failureCount(), List.of(), task.failureCode(), task.failureMessage(), task.retainUntil(),
                task.sourceReleasedAt(), task.createdAt(), task.updatedAt());
    }
    private QuestionDtos.LegacyImportBatchResult readResult(LegacyBatch batch, boolean replayed) {
        try {
            List<QuestionDtos.LegacyImportItemResult> values = new ArrayList<>();
            for (var item : mapper.readTree(batch.resultJson())) {
                List<String> issues = new ArrayList<>(); item.path("issueCodes").forEach(value -> issues.add(value.asString()));
                values.add(new QuestionDtos.LegacyImportItemResult(item.path("externalId").asString(),
                        QuestionModels.ImportOutcome.valueOf(item.path("outcome").asString()),
                        item.hasNonNull("questionId") ? item.get("questionId").asString() : null, List.copyOf(issues)));
            }
            return result(batch.importTaskId(), batch, replayed, values);
        } catch (Exception exception) { throw new IllegalStateException("Stored legacy batch result is invalid", exception); }
    }
    private static QuestionDtos.LegacyImportBatchResult result(String taskId, LegacyBatch batch, boolean replayed,
            List<QuestionDtos.LegacyImportItemResult> values) {
        return new QuestionDtos.LegacyImportBatchResult(taskId, batch.batchId(), replayed, batch.finalBatch(), batch.processedCount(),
                batch.importedCount(), batch.validCount(), batch.reviewCount(), batch.skippedCount(), batch.failedCount(), values);
    }
    private static QuestionDtos.LegacyImportItemResult result(String externalId, QuestionModels.ImportOutcome outcome,
            String questionId, List<String> issues) { return new QuestionDtos.LegacyImportItemResult(externalId, outcome, questionId, issues); }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String fingerprint(Object value) { try { return sha256(mapper.writeValueAsBytes(value)); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static String deterministic(String namespace, String value) { return UUID.nameUUIDFromBytes((namespace + "\0" + value).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static boolean terminal(QuestionModels.ImportStatus status) { return status == QuestionModels.ImportStatus.COMPLETED || status == QuestionModels.ImportStatus.PARTIAL_SUCCESS || status == QuestionModels.ImportStatus.FAILED || status == QuestionModels.ImportStatus.CLEANED; }
    private static boolean reviewable(QuestionModels.ImportStatus status) { return status == QuestionModels.ImportStatus.COMPLETED || status == QuestionModels.ImportStatus.PARTIAL_SUCCESS; }
    private static QuestionException invalid(String field, String reason) { return new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED", "请求参数不合法", List.of(new com.eduplatform.shared.api.FieldError(field, reason))); }
    private static QuestionException conflict(String code, String message) { return new QuestionException(HttpStatus.CONFLICT, code, message); }
    private static QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND, "QUESTION.NOT_FOUND", "历史导入任务不存在或不可见"); }
    private record ResolvedCandidate(LegacyQuestionJsonParser.Candidate candidate, List<String> issues) {}
}
