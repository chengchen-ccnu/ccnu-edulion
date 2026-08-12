package com.eduplatform.learning.service;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.ImportTask;
import com.eduplatform.learning.domain.QuestionModels.LegacyBatch;
import com.eduplatform.learning.domain.QuestionModels.LegacySource;
import com.eduplatform.learning.importing.LegacyQuestionJsonParser;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.persistence.QuestionRepository;
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
 * T09A 受控历史 JSON 迁移编排：任务上限、批次幂等、来源幂等、dry-run、复核与精确清理。
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
    private final TransactionTemplate itemTransactions;

    public LegacyQuestionImportService(QuestionRepository repository, LegacyQuestionJsonParser parser,
            QuestionService questions, MediaClient media, LearningEvents events, LearningProperties properties,
            ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.repository = repository; this.parser = parser; this.questions = questions; this.media = media;
        this.events = events; this.properties = properties; this.mapper = mapper;
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
                    results.add(new QuestionDtos.LegacyImportItemResult(item.externalId(), QuestionModels.ImportOutcome.SKIPPED,
                            existing.questionId(), existing.issueCodes())); skipped++;
                } else {
                    repository.insertLegacyIssue(taskId, request.batchId().toString(), item.externalId(),
                            item.contentSha256(), "SOURCE_CONTENT_CONFLICT", Instant.now());
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
            if (task.dryRun()) {
                persistSource(task, request, item, parsed, QuestionModels.ImportOutcome.VALID, null);
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.VALID, null, List.of())); valid++; continue;
            }
            try {
                String questionId = itemTransactions.execute(status -> {
                    String created = importCandidate(task, request.batchId().toString(), item.externalId(), parsed.candidate(), traceId);
                    persistSource(task, request, item, parsed, QuestionModels.ImportOutcome.IMPORTED, created);
                    return created;
                });
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.IMPORTED, questionId, List.of())); imported++;
            } catch (QuestionException dependency) {
                String issue = parsed.candidate().imageUrls().isEmpty() ? "FIELD_INVALID" : "EXTERNAL_IMAGE_TRANSFER_FAILED";
                persistSource(task, request, item, parsed, QuestionModels.ImportOutcome.NEEDS_REVIEW, null,
                        List.of(issue));
                results.add(result(item.externalId(), QuestionModels.ImportOutcome.NEEDS_REVIEW, null,
                        List.of(issue))); review++;
            } catch (RuntimeException failure) {
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
            childIds.add(questions.createImported(task.ownerId(), requestId, input, traceId).response().id());
        }
        QuestionDtos.VersionInput parent = withMedia(task, externalId + ":parent", candidate, traceId);
        String requestId = deterministic(task.id(), externalId + ":parent");
        return candidate.children().isEmpty()
                ? questions.createImported(task.ownerId(), requestId, parent, traceId).response().id()
                : questions.createImportedComposite(task.ownerId(), requestId, parent, childIds, traceId).response().id();
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
        repository.insertLegacySource(new LegacySource(UUID.randomUUID().toString(), task.id(), batch.batchId().toString(),
                task.ownerId(), task.sourceSystem(), item.externalId(), task.dryRun(), item.contentSha256(),
                sha256(item.relativePath().replace('\\', '/')), item.pathEdition(), parsed.jsonEdition(), item.pathGrade(), parsed.jsonGrade(),
                parsed.originalDifficulty(), outcome, questionId, issueCodes, now, now));
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
    private QuestionDtos.ImportTaskResponse response(ImportTask task) {
        return new QuestionDtos.ImportTaskResponse(task.id(), task.sourceMediaId(), task.sourceFormat(), task.sourceSystem(), task.dryRun(),
                task.status(), task.progress(), task.totalRows(), task.processedRows(), task.successCount(), task.reviewCount(),
                task.skippedCount(), task.failureCount(), List.of(), task.failureCode(), task.failureMessage(), task.retainUntil(),
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
    private static QuestionException invalid(String field, String reason) { return new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED", "请求参数不合法", List.of(new com.eduplatform.shared.api.FieldError(field, reason))); }
    private static QuestionException conflict(String code, String message) { return new QuestionException(HttpStatus.CONFLICT, code, message); }
    private static QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND, "QUESTION.NOT_FOUND", "历史导入任务不存在或不可见"); }
}
