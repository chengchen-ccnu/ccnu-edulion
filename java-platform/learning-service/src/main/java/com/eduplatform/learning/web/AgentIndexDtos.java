package com.eduplatform.learning.web;

import java.util.List;

/** T13.2 Worker 可读取的安全索引投影；题目 DTO 从类型层面排除答案和解析。 */
public final class AgentIndexDtos {
    private AgentIndexDtos() {}

    public record Cursor(long sourceRevision, String entityId) {}
    public record QuestionProjection(String entityId, String questionVersionId, long sourceRevision,
            String educationStage, String grade, String subject, String questionType, String stem,
            List<String> options, List<String> knowledgePointIds) {}
    public record QuestionPage(List<QuestionProjection> items, boolean hasMore, Cursor nextCursor) {}
    public record KnowledgeProjection(String entityId, String code, String name, long sourceRevision,
            String educationStage, String subject, List<String> prerequisiteIds) {}
    public record KnowledgePage(List<KnowledgeProjection> items, boolean hasMore, Cursor nextCursor) {}
}
