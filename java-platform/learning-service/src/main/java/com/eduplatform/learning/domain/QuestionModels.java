package com.eduplatform.learning.domain;

import java.time.Instant;
import java.util.List;

/** T09～T09B 题库内部模型；答案只会进入教师 DTO 或导入处理，不提供学生投影。 */
public final class QuestionModels {
    private QuestionModels() {}

    public enum Stage { JUNIOR }
    public enum Grade { SEVEN, EIGHT, NINE }
    public enum Subject { MATHEMATICS }
    public enum Type { SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK, SHORT_ANSWER, COMPOSITE }
    public enum Difficulty { EASY, MEDIUM, HARD }
    public enum Status { DRAFT, ACTIVE, ARCHIVED }
    public enum MediaPurpose { QUESTION_IMAGE, QUESTION_AUDIO, QUESTION_ATTACHMENT, QUESTION_IMPORT_SOURCE }
    public enum ImportFormat { CSV, XLSX, LEGACY_JSON }
    public enum ImportStatus { PENDING, PROCESSING, COMPLETED, PARTIAL_SUCCESS, FAILED, CLEANED }
    public enum ImportOutcome { IMPORTED, VALID, NEEDS_REVIEW, SKIPPED, FAILED }
    public enum ResolutionAction { IMPORT_CORRECTED, CONFIRM_SKIP }

    public record KnowledgePoint(String id, String code, String name, String parentId, Stage stage, Grade grade,
            Subject subject, int sortOrder, boolean enabled, long revision, Instant createdAt, Instant updatedAt) {}
    public record Option(String key, String content) {}
    public record Media(String id, String mediaId, MediaPurpose purpose, int sequence) {}
    public record Version(String id, String questionId, int versionNumber, Type type, Difficulty difficulty,
            Stage stage, Grade grade, Subject subject, String stem, List<Option> options, List<String> answers,
            String analysis, List<KnowledgePoint> knowledgePoints, List<Media> media, Instant createdAt) {}
    public record Aggregate(String id, String ownerId, Status status, String currentVersionId, long revision,
            Instant createdAt, Instant updatedAt) {}
    public record ImportTask(String id, String ownerId, String clientRequestId, String requestFingerprint,
            String sourceMediaId, ImportFormat sourceFormat, ImportStatus status, int progress, int totalRows,
            int processedRows, int successCount, int reviewCount, int skippedCount, int failureCount,
            String sourceSystem, String sourceRootFingerprint, boolean dryRun, Integer maxItems,
            String failureCode, String failureMessage, Instant retainUntil, Instant sourceReleasedAt,
            Instant cleanedAt, Instant createdAt, Instant updatedAt) {}
    public record RowError(String id, String importTaskId, int rowNumber, String code, String message,
            Instant createdAt) {}
    public record LegacySource(String id, String importTaskId, String batchId, String ownerId,
            String sourceSystem, String externalId, boolean dryRun, String contentSha256, String relativePathSha256,
            String pathEdition, String jsonEdition, String pathGrade, String jsonGrade, String originalDifficulty,
            ImportOutcome outcome, String questionId, List<String> issueCodes, Instant createdAt, Instant updatedAt) {}
    public record LegacyBatch(String importTaskId, String batchId, String requestFingerprint, boolean finalBatch,
            int processedCount, int importedCount, int validCount, int reviewCount, int skippedCount,
            int failedCount, String resultJson, Instant createdAt) {}
    public record LegacyReviewItem(String id, String importTaskId, String batchId, String ownerId,
            String sourceRefId, String externalId, String contentSha256, String pathEdition,
            String jsonEdition, String pathGrade, String jsonGrade, String originalDifficulty,
            ImportOutcome outcome, String questionId, List<String> issueCodes, long revision,
            ResolutionAction resolutionAction, Instant resolvedAt, Instant createdAt, Instant updatedAt) {}
}
