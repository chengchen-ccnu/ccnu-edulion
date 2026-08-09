package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** OpenAPI 0.8.0 的题库请求/响应模型；所有写请求显式拒绝未声明字段。 */
public final class QuestionDtos {
    private QuestionDtos() {}

    public record OptionInput(@NotBlank @Pattern(regexp = "^[A-H]$") String key,
            @NotBlank @Size(max = 10000) String content) {}

    public record MediaInput(@NotNull UUID mediaId, @NotNull QuestionModels.MediaPurpose purpose,
            @Min(1) @Max(100) int sequence) {}

    public record VersionInput(@NotNull QuestionModels.Type type, @NotNull QuestionModels.Difficulty difficulty,
            @NotNull QuestionModels.Stage stage, @NotNull QuestionModels.Grade grade,
            @NotNull QuestionModels.Subject subject, @NotBlank @Size(max = 50000) String stem,
            @NotNull @Size(max = 8) List<@Valid OptionInput> options,
            @NotNull @Size(min = 1, max = 20) List<@NotBlank @Size(max = 20000) String> answers,
            @NotNull @Size(max = 50000) String analysis,
            @NotNull @Size(max = 30) List<@NotNull UUID> knowledgePointIds,
            @NotNull @Size(max = 100) List<@Valid MediaInput> media) {}

    public record CreateQuestionRequest(@NotNull UUID clientRequestId, @NotNull @Valid VersionInput version) {}
    public record UpdateQuestionRequest(@Min(0) long expectedRevision, @NotNull @Valid VersionInput version) {}
    public record UpdateStatusRequest(@Min(0) long expectedRevision, @NotNull QuestionModels.Status status) {}

    public record CreateKnowledgePointRequest(
            @NotBlank @Pattern(regexp = "^JUNIOR_MATH_[A-Z0-9_]{2,50}$") String code,
            @NotBlank @Size(max = 100) String name, UUID parentId, @NotNull QuestionModels.Stage stage,
            @NotNull QuestionModels.Grade grade, @NotNull QuestionModels.Subject subject,
            @Min(0) @Max(100000) int sortOrder, boolean enabled) {}

    public record UpdateKnowledgePointRequest(@Min(0) long expectedRevision,
            @NotBlank @Size(max = 100) String name, UUID parentId, @NotNull QuestionModels.Grade grade,
            @Min(0) @Max(100000) int sortOrder, boolean enabled) {}

    public record KnowledgePointResponse(String id, String code, String name, String parentId,
            QuestionModels.Stage stage, QuestionModels.Grade grade, QuestionModels.Subject subject,
            int sortOrder, boolean enabled, long revision, List<KnowledgePointResponse> children) {}

    public record MediaResponse(String id, String mediaId, QuestionModels.MediaPurpose purpose, int sequence) {}
    public record VersionResponse(String id, int versionNumber, QuestionModels.Type type,
            QuestionModels.Difficulty difficulty, QuestionModels.Stage stage, QuestionModels.Grade grade,
            QuestionModels.Subject subject, String stem, List<OptionInput> options, List<String> answers,
            String analysis, List<KnowledgePointResponse> knowledgePoints, List<MediaResponse> media,
            Instant createdAt) {}
    public record QuestionResponse(String id, QuestionModels.Status status, long revision,
            VersionResponse currentVersion, Instant createdAt, Instant updatedAt) {}
    public record QuestionSummary(String id, QuestionModels.Status status, long revision, String currentVersionId,
            int versionNumber, QuestionModels.Type type, QuestionModels.Difficulty difficulty,
            QuestionModels.Grade grade, String stemPreview, List<String> knowledgePointIds,
            Instant createdAt, Instant updatedAt) {}
    public record QuestionPage(List<QuestionSummary> items, int page, int size, long total) {}

    public record CreateImportRequest(@NotNull UUID clientRequestId, @NotNull UUID sourceMediaId,
            @NotNull QuestionModels.ImportFormat sourceFormat) {}
    public record ImportRowError(int rowNumber, String code, String message) {}
    public record ImportTaskResponse(String id, String sourceMediaId, QuestionModels.ImportFormat sourceFormat,
            QuestionModels.ImportStatus status, int progress, int totalRows, int processedRows, int successCount,
            int failureCount, List<ImportRowError> errors, String failureCode, String failureMessage,
            Instant retainUntil, Instant sourceReleasedAt, Instant createdAt, Instant updatedAt) {}
    public record ImportPage(List<ImportTaskResponse> items, int page, int size, long total) {}
}
