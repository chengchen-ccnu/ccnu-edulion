package com.eduplatform.learning.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
import tools.jackson.databind.JsonNode;

/** OpenAPI 0.13.0 的题库请求/响应模型；所有写请求显式拒绝未声明字段。 */
public final class QuestionDtos {
    private QuestionDtos() {}

    public record OptionInput(@NotBlank @Pattern(regexp = "^[A-H]$") String key,
            @NotBlank @Size(max = 10000) String content) {}

    public record MediaInput(@NotNull UUID mediaId, @NotNull QuestionModels.MediaPurpose purpose,
            @Min(1) @Max(100) int sequence) {}

    /**
     * 题目完整版本写模型。11 个 OpenAPI 必填字段继续由严格 creator 绑定，只有可选的 children
     * 在 creator 之后注入，因此无需放松 learning-service 的全局 Jackson 严格策略。
     */
    public static final class VersionInput {
        @NotNull private final QuestionModels.Type type;
        @NotNull private final QuestionModels.Difficulty difficulty;
        @NotNull private final QuestionModels.Stage stage;
        @NotNull private final QuestionModels.Grade grade;
        @NotNull private final QuestionModels.Subject subject;
        @NotBlank @Size(max = 50000) private final String stem;
        @NotNull @Size(max = 8) private final List<@Valid OptionInput> options;
        @NotNull @Size(min = 1, max = 20)
        private final List<@NotBlank @Size(max = 20000) String> answers;
        @NotNull @Size(max = 50000) private final String analysis;
        @NotNull @Size(max = 30) private final List<@NotNull UUID> knowledgePointIds;
        @NotNull @Size(max = 100) private final List<@Valid MediaInput> media;
        @Size(max = 100) private List<@Valid ChildInput> children;

        /**
         * 反序列化并校验 OpenAPI 声明的全部必填版本字段；缺少任一参数仍由严格 creator 直接拒绝。
         * children 不属于 creator 参数，省略时保留为 null，随后由业务层规范化为空列表。
         */
        @JsonCreator
        public VersionInput(@JsonProperty("type") QuestionModels.Type type,
                @JsonProperty("difficulty") QuestionModels.Difficulty difficulty,
                @JsonProperty("stage") QuestionModels.Stage stage,
                @JsonProperty("grade") QuestionModels.Grade grade,
                @JsonProperty("subject") QuestionModels.Subject subject,
                @JsonProperty("stem") String stem,
                @JsonProperty("options") List<OptionInput> options,
                @JsonProperty("answers") List<String> answers,
                @JsonProperty("analysis") String analysis,
                @JsonProperty("knowledgePointIds") List<UUID> knowledgePointIds,
                @JsonProperty("media") List<MediaInput> media) {
            this(type, difficulty, stage, grade, subject, stem, options, answers, analysis,
                    knowledgePointIds, media, null);
        }

        /** 供组合题、导入与测试等内部调用显式携带有序子题。 */
        public VersionInput(QuestionModels.Type type, QuestionModels.Difficulty difficulty,
                QuestionModels.Stage stage, QuestionModels.Grade grade, QuestionModels.Subject subject,
                String stem, List<OptionInput> options, List<String> answers, String analysis,
                List<UUID> knowledgePointIds, List<MediaInput> media, List<ChildInput> children) {
            this.type = type;
            this.difficulty = difficulty;
            this.stage = stage;
            this.grade = grade;
            this.subject = subject;
            this.stem = stem;
            this.options = options;
            this.answers = answers;
            this.analysis = analysis;
            this.knowledgePointIds = knowledgePointIds;
            this.media = media;
            this.children = children;
        }

        /** 显式 children 只接受数组；JSON null 不等同于字段省略。 */
        @JsonSetter(value = "children", nulls = Nulls.FAIL)
        public void setChildren(List<ChildInput> children) { this.children = children; }

        @JsonProperty("type") public QuestionModels.Type type() { return type; }
        @JsonProperty("difficulty") public QuestionModels.Difficulty difficulty() { return difficulty; }
        @JsonProperty("stage") public QuestionModels.Stage stage() { return stage; }
        @JsonProperty("grade") public QuestionModels.Grade grade() { return grade; }
        @JsonProperty("subject") public QuestionModels.Subject subject() { return subject; }
        @JsonProperty("stem") public String stem() { return stem; }
        @JsonProperty("options") public List<OptionInput> options() { return options; }
        @JsonProperty("answers") public List<String> answers() { return answers; }
        @JsonProperty("analysis") public String analysis() { return analysis; }
        @JsonProperty("knowledgePointIds") public List<UUID> knowledgePointIds() { return knowledgePointIds; }
        @JsonProperty("media") public List<MediaInput> media() { return media; }
        @JsonProperty("children") public List<ChildInput> children() { return children; }

        /** 将缺省 children 规范为空列表，供题目业务校验、写入和指纹计算统一使用。 */
        public List<ChildInput> childItems() { return children == null ? List.of() : children; }

        /** 返回 children 已规范化的新值，避免修改反序列化完成后的请求对象。 */
        public VersionInput withChildren(List<ChildInput> normalizedChildren) {
            return new VersionInput(type, difficulty, stage, grade, subject, stem, options, answers, analysis,
                    knowledgePointIds, media, normalizedChildren);
        }
    }

    /** 组合题有序子题完整写模型；子题不能再次嵌套组合题。 */
    public record ChildInput(@NotNull QuestionModels.Type type, @NotNull QuestionModels.Difficulty difficulty,
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
            @NotBlank @Pattern(regexp = "^(PRIMARY|JUNIOR)_MATH_[A-Z0-9_]{2,50}$") String code,
            @NotBlank @Size(max = 100) String name, UUID parentId, @NotNull QuestionModels.Stage stage,
            @NotNull QuestionModels.Grade grade, @NotNull QuestionModels.Subject subject,
            @Min(0) @Max(100000) int sortOrder, boolean enabled) {}

    public record UpdateKnowledgePointRequest(@Min(0) long expectedRevision,
            @NotBlank @Size(max = 100) String name, UUID parentId, @NotNull QuestionModels.Grade grade,
            @Min(0) @Max(100000) int sortOrder, boolean enabled) {}

    /**
     * 题目版本中的知识点快照。父节点和年级允许为空，但持久化 JSON 必须显式保留 null，
     * 否则严格反序列化会把“字段缺失”误判为损坏的历史快照。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record KnowledgePointResponse(String id, String code, String name, String parentId,
            QuestionModels.Stage stage, QuestionModels.Grade grade, QuestionModels.Subject subject,
            int sortOrder, boolean enabled, long revision, List<KnowledgePointResponse> children) {}

    public record MediaResponse(String id, String mediaId, QuestionModels.MediaPurpose purpose, int sequence) {}
    /** 旧题来源快照中的版本、年级等元数据允许为空，持久化时必须保留这些 null 字段。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LegacySourceResponse(String sourceSystem, String externalId, String originalDifficulty,
            String pathEdition, String jsonEdition, String pathGrade, String jsonGrade, boolean metadataConflict) {}
    public record ChildResponse(String questionId, String versionId, int versionNumber, int sequence,
            QuestionModels.Type type, QuestionModels.Difficulty difficulty, QuestionModels.Stage stage,
            QuestionModels.Grade grade, QuestionModels.Subject subject, String stem, List<OptionInput> options,
            List<String> answers, String analysis, List<KnowledgePointResponse> knowledgePoints,
            List<MediaResponse> media, Instant createdAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VersionResponse(String id, int versionNumber, QuestionModels.Type type,
            QuestionModels.Difficulty difficulty, QuestionModels.Stage stage, QuestionModels.Grade grade,
            QuestionModels.Subject subject, String stem, List<OptionInput> options, List<String> answers,
            String analysis, List<KnowledgePointResponse> knowledgePoints, List<MediaResponse> media,
            LegacySourceResponse legacySource, List<ChildResponse> children, Instant createdAt) {}
    public record QuestionResponse(String id, QuestionModels.Scope scope, boolean editable,
            QuestionModels.Status status, long revision,
            VersionResponse currentVersion, Instant createdAt, Instant updatedAt) {}
    public record QuestionSummary(String id, QuestionModels.Scope scope, boolean editable,
            QuestionModels.Status status, long revision, String currentVersionId,
            int versionNumber, QuestionModels.Type type, QuestionModels.Difficulty difficulty,
            QuestionModels.Stage stage, QuestionModels.Grade grade, String stemPreview, List<String> knowledgePointIds,
            Instant createdAt, Instant updatedAt) {}
    public record QuestionPage(List<QuestionSummary> items, int page, int size, long total) {}
    /** 学习端子题投影主动移除答案与解析，避免绕过后续测评交卷边界。 */
    public record SystemQuestionChild(String questionId, String versionId, int sequence,
            QuestionModels.Type type, QuestionModels.Difficulty difficulty, QuestionModels.Stage stage,
            QuestionModels.Grade grade, QuestionModels.Subject subject, String stem, List<OptionInput> options,
            List<KnowledgePointResponse> knowledgePoints, List<MediaResponse> media, Instant createdAt) {}
    /** 已启用公共题的学习端只读投影；不包含 answers、analysis 或历史版本。 */
    public record SystemQuestion(String id, QuestionModels.Scope scope, QuestionModels.Type type,
            QuestionModels.Difficulty difficulty, QuestionModels.Stage stage, QuestionModels.Grade grade,
            QuestionModels.Subject subject, String stem, List<OptionInput> options,
            List<KnowledgePointResponse> knowledgePoints, List<MediaResponse> media,
            List<SystemQuestionChild> children, Instant createdAt, Instant updatedAt) {}
    public record SystemQuestionPage(List<QuestionSummary> items, int page, int size, long total) {}
    public record VersionSummary(String id, int versionNumber, QuestionModels.Type type,
            QuestionModels.Difficulty difficulty, String stemPreview, int childCount, Instant createdAt,
            boolean current) {}
    public record VersionPage(List<VersionSummary> items, int page, int size, long total) {}

    public record CreateImportRequest(@NotNull UUID clientRequestId, @NotNull UUID sourceMediaId,
            @NotNull QuestionModels.ImportFormat sourceFormat) {}
    public record ImportRowError(int rowNumber, String code, String message) {}
    public record ImportTaskResponse(String id, QuestionModels.Scope questionScope,
            String sourceMediaId, QuestionModels.ImportFormat sourceFormat,
            String sourceSystem, boolean dryRun, Integer maxItems, QuestionModels.ImportStatus status, int progress, int totalRows,
            int processedRows, int successCount, int reviewCount, int resolvedCount, int skippedCount, int failureCount,
            List<ImportRowError> errors, String failureCode, String failureMessage,
            Instant retainUntil, Instant sourceReleasedAt, Instant createdAt, Instant updatedAt) {}
    public record ImportPage(List<ImportTaskResponse> items, int page, int size, long total) {}

    public record CreateLegacyImportRequest(@NotNull UUID clientRequestId,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{2,79}$") String sourceSystem,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sourceRootFingerprint,
            boolean dryRun, @Min(1) @Max(1000) int maxItems) {}

    public record LegacyImportItem(@NotBlank @Size(max = 160) String externalId,
            @NotBlank @Size(max = 500) String relativePath,
            @NotBlank @Size(max = 100) String pathEdition,
            @NotBlank @Size(max = 100) String pathGrade,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String contentSha256,
            @NotNull JsonNode json) {}

    public record LegacyImportBatchRequest(@NotNull UUID batchId, boolean finalBatch,
            @NotNull @Size(min = 1, max = 20) List<@Valid LegacyImportItem> items) {}

    public record LegacyImportItemResult(String externalId, QuestionModels.ImportOutcome outcome,
            String questionId, List<String> issueCodes) {}

    public record LegacyImportBatchResult(String importTaskId, String batchId, boolean replayed, boolean finalBatch,
            int processedCount, int importedCount, int validCount, int reviewCount, int skippedCount,
            int failedCount, List<LegacyImportItemResult> items) {}

    public record LegacyImportCleanupResult(String importTaskId, int questionsDeleted,
            int sourceReferencesDeleted, int mediaReferencesReleased, boolean alreadyCleaned) {}

    public enum ResolutionAction { IMPORT_CORRECTED, CONFIRM_SKIP }
    public record LegacyIssue(String code, String message) {}
    public record LegacyResolution(ResolutionAction action, Instant resolvedAt) {}
    public record LegacyReviewItem(String id, long revision, String externalId,
            QuestionModels.ImportOutcome outcome, String questionId, String sourceSystem,
            String originalDifficulty, String pathEdition, String jsonEdition, String pathGrade,
            String jsonGrade, List<LegacyIssue> issues, LegacyResolution resolution,
            Instant createdAt, Instant updatedAt) {}
    public record LegacyReviewPage(List<LegacyReviewItem> items, int page, int size, long total) {}
    public record ResolveLegacyItemRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull ResolutionAction action, @Valid VersionInput correctedVersion) {}
}
