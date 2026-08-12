package com.eduplatform.learning.domain;

import java.time.Instant;
import java.util.List;

/** T09 题库内部模型；答案只会进入教师 DTO 或导入处理，不提供学生投影。 */
public final class QuestionModels {
    private QuestionModels() {}

    public enum Stage { JUNIOR }
    public enum Grade { SEVEN, EIGHT, NINE }
    public enum Subject { MATHEMATICS }
    public enum Type { SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK, SHORT_ANSWER }
    public enum Difficulty { EASY, MEDIUM, HARD }
    public enum Status { DRAFT, ACTIVE, ARCHIVED }
    public enum MediaPurpose { QUESTION_IMAGE, QUESTION_AUDIO, QUESTION_ATTACHMENT, QUESTION_IMPORT_SOURCE }
    public enum ImportFormat { CSV, XLSX }
    public enum ImportStatus { PENDING, PROCESSING, COMPLETED, PARTIAL_SUCCESS, FAILED }

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
            int processedRows, int successCount, int failureCount, String failureCode, String failureMessage,
            Instant retainUntil, Instant sourceReleasedAt, Instant createdAt, Instant updatedAt) {}
    public record RowError(String id, String importTaskId, int rowNumber, String code, String message,
            Instant createdAt) {}
}
