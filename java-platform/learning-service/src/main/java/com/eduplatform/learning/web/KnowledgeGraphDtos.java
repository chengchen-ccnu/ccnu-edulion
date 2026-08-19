package com.eduplatform.learning.web;

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

/** T10B 知识图谱导入与有限邻域查询契约；DTO 不携带源文件路径或原始 JSON。 */
public final class KnowledgeGraphDtos {
    private KnowledgeGraphDtos() {}

    public enum Status { AWAITING_NODES, AWAITING_RELATIONS, VALIDATING, COMPLETED, COMPLETED_WITH_ISSUES, FAILED }
    public enum SubjectKind { TASK, NODE, RELATION }

    public record CreateImportRequest(@NotNull UUID clientRequestId,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{2,79}$") String sourceSystem,
            boolean dryRun,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String nodeFileSha256,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String relationFileSha256,
            @Min(1) @Max(100000) int expectedNodeCount,
            @Min(0) @Max(500000) int expectedRelationCount) {}

    public record NodeInput(@NotBlank @Size(max = 80) String sourceKnowId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String disciplineCode,
            @NotBlank @Size(max = 20) String educationStageCode,
            @NotBlank @Size(max = 80) String knowledgeTypeId,
            @Size(max = 40) String knowledgeTypeColour,
            int levelSortIndex,
            @NotBlank @Size(max = 40) String nodeKind,
            @Size(max = 80) String sourceParentKey,
            @NotBlank @Size(max = 40) String sourceStatus,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String contentSha256) {}

    public record RelationInput(@NotBlank @Size(max = 80) String prerequisiteSourceKnowId,
            @NotBlank @Size(max = 80) String dependentSourceKnowId,
            @NotBlank @Size(max = 80) String sourceRelationType,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String contentSha256) {}

    public record NodeBatchRequest(@NotNull UUID batchId, boolean finalBatch,
            @NotNull @Size(min = 1, max = 500) List<@Valid NodeInput> nodes) {}
    public record RelationBatchRequest(@NotNull UUID batchId, boolean finalBatch,
            @NotNull @Size(min = 1, max = 500) List<@Valid RelationInput> relations) {}

    public record ImportSummary(int nodeCount, int relationCount, java.util.Map<String, Integer> stageDistribution,
            java.util.Map<String, Integer> typeDistribution, int invalidNodeCount, int duplicateNameCount,
            int isolatedNodeCount, int unresolvedParentKeyCount, int addedNodeCount, int reusedNodeCount, int conflictNodeCount,
            int addedRelationCount, int reusedRelationCount, int skippedRelationCount,
            int missingEndpointCount, int duplicateEdgeCount, int selfLoopCount, int crossDisciplineEdgeCount, int crossStageEdgeCount,
            boolean cycleDetected, int maxInDegree, int maxOutDegree, int longestPrerequisiteChain) {}
    public record ImportTask(String id, String sourceSystem, boolean dryRun, Status status,
            int expectedNodeCount, int expectedRelationCount, int processedNodeCount,
            int processedRelationCount, ImportSummary summary, Instant createdAt, Instant updatedAt) {}
    public record BatchResult(String importTaskId, String batchId, boolean replayed,
            int acceptedCount, Status status) {}
    public record ImportIssue(String id, String issueCode, SubjectKind subjectKind,
            int occurrenceCount, Instant createdAt) {}
    public record ImportIssuePage(List<ImportIssue> items, int page, int size, long total) {}

    /** 教师只读搜索投影不暴露 sourceKnowId/sourceParentKey，颜色只是辅助提示。 */
    public record SearchItem(String id, String name, String normalizedStage, String knowledgeTypeId,
            String knowledgeTypeName, String knowledgeTypeColour, int levelSortIndex, boolean enabled) {}
    public record SearchPage(List<SearchItem> items, int page, int size, long total) {}
    public record GraphEdge(String prerequisiteId, String dependentId, String relationType) {}
    public record Graph(SearchItem center, List<SearchItem> directPrerequisites,
            List<SearchItem> directDependents, List<SearchItem> nodes, List<GraphEdge> edges, int depth) {}
}
