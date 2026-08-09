package com.eduplatform.learning.importing;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.ImportTask;
import com.eduplatform.learning.domain.QuestionModels.KnowledgePoint;
import com.eduplatform.learning.domain.QuestionModels.RowError;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.QuestionDtos;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 消费 QuestionImportRequested：短期下载、严格逐行转换、部分成功与 eventId 幂等。 */
@Component
@ConditionalOnBean(QuestionRepository.class)
public class QuestionImportProcessor {
    public static final String CONSUMER = "learning-question-import-v1";
    private final QuestionRepository repository;
    private final QuestionImportParser parser;
    private final QuestionService questions;
    private final MediaClient media;
    private final LearningProperties properties;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public QuestionImportProcessor(QuestionRepository repository, QuestionImportParser parser, QuestionService questions,
            MediaClient media, LearningProperties properties, TransactionTemplate transactions, ObjectMapper mapper) {
        this.repository = repository; this.parser = parser; this.questions = questions; this.media = media;
        this.properties = properties; this.transactions = transactions; this.mapper = mapper;
    }

    /**
     * 处理一次至少一次投递。重复 eventId 直接返回；若崩溃发生在部分行提交后，确定性请求 ID
     * 让下一次消费复用原题目而不重复插入。
     */
    public void process(String json) {
        Event event = event(json);
        if (!"QuestionImportRequested".equals(event.eventType()) || event.eventVersion() != 1) {
            throw new IllegalArgumentException("Unsupported question import event");
        }
        if (repository.consumed(event.eventId())) return;
        ImportTask task = repository.findImportTask(event.aggregateId()).orElseThrow(() -> new IllegalStateException("Import task missing"));
        if (terminal(task.status())) {
            transactions.executeWithoutResult(status -> { if (!repository.consumed(event.eventId())) repository.markConsumed(event.eventId(), event.eventType(), CONSUMER, Instant.now()); });
            return;
        }
        repository.startImport(task.id(), Instant.now());
        QuestionImportParser.Parsed parsed;
        try (InputStream source = media.openOriginal(task.sourceMediaId(), task.ownerId(), event.traceId())) {
            parsed = parser.parse(source, task.sourceFormat());
        } catch (QuestionException dependency) {
            throw new RetryableImportException("media source unavailable", dependency);
        } catch (Exception failure) {
            throw new RetryableImportException("media source stream failed", failure);
        }
        List<RowError> errors = new ArrayList<>(); int success = 0; int processed = 0;
        int total = parsed.rows().size();
        for (QuestionImportParser.Row row : parsed.rows()) {
            if (!row.valid()) {
                errors.add(error(task.id(), row.rowNumber(), row.errorCode(), row.errorMessage()));
            } else {
                try {
                    List<String> codes = row.knowledgePointCodes().stream().distinct().toList();
                    List<KnowledgePoint> points = repository.findKnowledgePointsByCodes(codes);
                    Map<String, KnowledgePoint> byCode = points.stream().collect(Collectors.toMap(KnowledgePoint::code, Function.identity()));
                    if (byCode.size() != codes.size()) {
                        errors.add(error(task.id(), row.rowNumber(), "QUESTION_IMPORT.KNOWLEDGE_POINT_UNKNOWN", "知识点编码不存在"));
                    } else {
                        QuestionDtos.VersionInput source = row.input();
                        QuestionDtos.VersionInput resolved = new QuestionDtos.VersionInput(source.type(), source.difficulty(), source.stage(),
                                source.grade(), source.subject(), source.stem(), source.options(), source.answers(), source.analysis(),
                                codes.stream().map(code -> UUID.fromString(byCode.get(code).id())).toList(), source.media());
                        String requestId = UUID.nameUUIDFromBytes((task.id() + ":" + row.rowNumber()).getBytes(StandardCharsets.UTF_8)).toString();
                        questions.createImported(task.ownerId(), requestId, resolved, event.traceId()); success++;
                    }
                } catch (QuestionException invalid) {
                    boolean externalImage = invalid.fieldErrors().stream().anyMatch(field -> field.reason().contains("图片必须"));
                    String code = externalImage ? "QUESTION_IMPORT.EXTERNAL_IMAGE_REQUIRES_UPLOAD"
                            : ("QUESTION.MEDIA_NOT_BINDABLE".equals(invalid.code()) ? "QUESTION_IMPORT.MEDIA_NOT_BINDABLE"
                            : "QUESTION_IMPORT.ROW_INVALID");
                    errors.add(error(task.id(), row.rowNumber(), code, safeMessage(invalid)));
                } catch (RuntimeException failure) {
                    throw new RetryableImportException("row persistence failed", failure);
                }
            }
            processed++;
            repository.updateImportProgress(task.id(), total, processed, success, errors.size(), Instant.now());
        }
        int completed = success;
        transactions.executeWithoutResult(status -> {
            repository.completeImport(task.id(), total, completed, errors, Instant.now().plus(properties.importSettings().retention()), Instant.now());
            repository.markConsumed(event.eventId(), event.eventType(), CONSUMER, Instant.now());
        });
    }

    /** 重试耗尽时持久化安全失败原因；原消息随后由 Broker 进入 DLQ。 */
    public void exhausted(String json, Throwable failure) {
        Event event = event(json); Instant now = Instant.now();
        repository.failImport(event.aggregateId(), "QUESTION_IMPORT.RETRY_EXHAUSTED", "导入处理重试已耗尽，请人工检查后重放",
                now.plus(properties.importSettings().retention()), now);
    }

    private Event event(String json) {
        try { return mapper.readValue(json, Event.class); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid question import event", exception); }
    }
    private static boolean terminal(QuestionModels.ImportStatus status) {
        return status == QuestionModels.ImportStatus.COMPLETED || status == QuestionModels.ImportStatus.PARTIAL_SUCCESS
                || status == QuestionModels.ImportStatus.FAILED;
    }
    private static RowError error(String taskId, int row, String code, String message) {
        return new RowError(UUID.randomUUID().toString(), taskId, Math.max(2, row), code, message, Instant.now());
    }
    private static String safeMessage(QuestionException exception) {
        if (exception.fieldErrors().stream().anyMatch(field -> field.reason().contains("图片必须")))
            return "外部或嵌入图片不支持，请先上传 QUESTION_IMAGE 并使用 mediaJson";
        return "题目字段、题型答案或媒体引用不合法";
    }

    public record Event(String eventId, String eventType, int eventVersion, String aggregateId, Instant occurredAt,
            String traceId, Payload payload) {}
    public record Payload(String importTaskId, String sourceMediaId, QuestionModels.ImportFormat sourceFormat) {}
    public static class RetryableImportException extends RuntimeException {
        public RetryableImportException(String message, Throwable cause) { super(message, cause); }
    }
}
