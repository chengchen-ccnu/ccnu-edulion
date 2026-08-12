package com.eduplatform.learning.service;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.ImportTask;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.web.QuestionDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.annotation.Transactional;

/** 创建、查询和到期释放教师导入任务；源文件在终态后仍按配置保留。 */
@Service
@ConditionalOnBean(QuestionRepository.class)
public class QuestionImportService {
    private final QuestionRepository repository;
    private final MediaClient media;
    private final LearningEvents events;
    private final LearningProperties properties;

    public QuestionImportService(QuestionRepository repository, MediaClient media, LearningEvents events,
            LearningProperties properties) {
        this.repository = repository; this.media = media; this.events = events; this.properties = properties;
    }

    /** 校验 READY/owner/用途后，原子写任务、导入请求 Outbox 和源文件引用 Bound。 */
    @Transactional
    public Creation create(String ownerId, QuestionDtos.CreateImportRequest request, String traceId) {
        String fingerprint = fingerprint(request.sourceMediaId() + ":" + request.sourceFormat());
        var replay = repository.findImportByRequest(ownerId, request.clientRequestId().toString());
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(fingerprint)) throw new QuestionException(HttpStatus.CONFLICT,
                    "QUESTION.IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同的导入请求");
            return new Creation(response(replay.get(), true), false);
        }
        media.requireBindable(request.sourceMediaId().toString(), QuestionModels.MediaPurpose.QUESTION_IMPORT_SOURCE.name(), ownerId, traceId);
        Instant now = Instant.now(); String id = UUID.randomUUID().toString();
        ImportTask task = new ImportTask(id, ownerId, request.clientRequestId().toString(), fingerprint,
                request.sourceMediaId().toString(), request.sourceFormat(), QuestionModels.ImportStatus.PENDING,
                0, 0, 0, 0, 0, null, null, now.plus(properties.importSettings().retention()), null, now, now);
        repository.insertImportTask(task);
        repository.insertOutbox(events.importRequested(id, task.sourceMediaId(), task.sourceFormat().name(), traceId, now));
        repository.insertOutbox(events.mediaReference("MediaReferenceBound", task.sourceMediaId(),
                "QUESTION_IMPORT_SOURCE", id, traceId, now));
        return new Creation(response(task, true), true);
    }

    /** 返回教师本人的导入详情和完整逐行错误；他人任务按不存在处理。 */
    public QuestionDtos.ImportTaskResponse get(String ownerId, String id) {
        return response(repository.findImportTask(id, ownerId).orElseThrow(QuestionImportService::notFound), true);
    }

    /** 分页返回教师本人任务摘要，列表不装载逐行错误以控制响应大小。 */
    public QuestionDtos.ImportPage list(String ownerId, int page, int size) {
        List<QuestionDtos.ImportTaskResponse> items = repository.listImports(ownerId, page, size).stream()
                .map(task -> response(task, false)).toList();
        return new QuestionDtos.ImportPage(items, page, size, repository.countImports(ownerId));
    }

    /**
     * 到期后释放准确的导入任务引用；Outbox 与 releasedAt 同事务提交，绝不直接删除 OSS 对象。
     * MQ 不可用时 PENDING 事件留库补投，media-service 仍会执行宽限期和引用对账。
     */
    @Scheduled(fixedDelayString = "${platform.learning.import.release-poll-delay:1h}")
    public void releaseExpiredSources() {
        for (ImportTask task : repository.releasableImports(Instant.now(), 50)) release(task);
    }

    /** 对单个到期任务幂等标记并写 Released Outbox；返回前不调用 OSS。 */
    @Transactional
    public void release(ImportTask task) {
        Instant now = Instant.now();
        if (repository.markSourceReleased(task.id(), now) == 1) repository.insertOutbox(events.mediaReference(
                "MediaReferenceReleased", task.sourceMediaId(), "QUESTION_IMPORT_SOURCE", task.id(), "retention-" + task.id(), now));
    }

    private QuestionDtos.ImportTaskResponse response(ImportTask task, boolean includeErrors) {
        List<QuestionDtos.ImportRowError> errors = includeErrors ? repository.importErrors(task.id()).stream()
                .map(value -> new QuestionDtos.ImportRowError(value.rowNumber(), value.code(), value.message())).toList() : List.of();
        return new QuestionDtos.ImportTaskResponse(task.id(), task.sourceMediaId(), task.sourceFormat(), task.status(), task.progress(),
                task.totalRows(), task.processedRows(), task.successCount(), task.failureCount(), errors, task.failureCode(),
                task.failureMessage(), task.retainUntil(), task.sourceReleasedAt(), task.createdAt(), task.updatedAt());
    }

    private String fingerprint(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Import fingerprint failed", exception); }
    }

    private static QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND, "QUESTION.NOT_FOUND", "导入任务不存在或不可见"); }
    public record Creation(QuestionDtos.ImportTaskResponse response, boolean created) {}
}
