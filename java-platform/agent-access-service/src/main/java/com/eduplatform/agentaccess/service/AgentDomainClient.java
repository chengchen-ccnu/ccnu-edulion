package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Agent Access 以可信 owner 调用领域服务的只读端口；依赖失败必须向上抛出并 fail-closed。 */
public interface AgentDomainClient {
    Set<UUID> verifyCourseCandidates(UUID ownerUserId, List<AgentInternalDtos.CourseCandidate> candidates,
            String traceId);
    List<AgentInternalDtos.SafeFact> readStudentFacts(UUID ownerUserId,
            AgentInternalDtos.StudentFactsRequest request, String traceId);
    List<AgentInternalDtos.QuestionVersionProjection> projectQuestionVersions(UUID ownerUserId,
            List<AgentInternalDtos.QuestionVersionKey> versions, String traceId);
    AgentInternalDtos.MediaAccessGrant authorizeQuestionVersionMedia(UUID ownerUserId, UUID questionId,
            UUID questionVersionId, UUID mediaId, String variant, String disposition, String traceId);
    default AgentInternalDtos.ValidatePracticeCandidatesResponse validatePracticeCandidates(UUID ownerUserId,
            AgentInternalDtos.PracticeCriteria criteria, List<AgentInternalDtos.PracticeCandidateKey> candidates,
            String traceId) { throw new UnsupportedOperationException("自主组题领域端口未配置"); }
    default AgentInternalDtos.AutonomousPracticeResult createAutonomousPractice(UUID actionId, UUID ownerUserId,
            AgentInternalDtos.PracticeCriteria criteria, List<AgentInternalDtos.PracticeCandidateKey> candidates,
            String traceId) { throw new UnsupportedOperationException("自主练习创建端口未配置"); }
    default AgentInternalDtos.AutonomousPracticeResult findAutonomousPractice(UUID actionId, UUID ownerUserId,
            String traceId) { throw new UnsupportedOperationException("自主练习查询端口未配置"); }
}
