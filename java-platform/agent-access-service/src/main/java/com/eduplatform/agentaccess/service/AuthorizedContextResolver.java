package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.web.AgentDtos;
import java.util.UUID;
import java.util.List;

/** 通过版本化领域 API 把浏览器资源提示转换为 Java 已授权上下文，不允许直接信任请求 ID。 */
public interface AuthorizedContextResolver {
    /**
     * @param userId Gateway 已验证的 IAM 用户 ID
     * @param entryPoint 会话固定入口
     * @param input 浏览器提交的待验证稳定 ID
     * @param traceId 当前链路 Trace
     * @return 可持久化稳定上下文和仅用于本次 Python 调用的可信投影
     */
    AgentDtos.AuthorizedContext resolve(UUID userId, AgentDtos.EntryPoint entryPoint,
            AgentDtos.MessageContextRequest input, String traceId);

    /**
     * 重新生成时按已保存的不可变版本恢复授权；默认实现供简单替身保持兼容，生产 HTTP 实现必须精确解析历史版本。
     */
    default AgentDtos.AuthorizedContext resolveSaved(UUID userId, AgentDtos.EntryPoint entryPoint,
            AgentDtos.MessageContext stable, List<UUID> questionMediaIds, List<UUID> aiQuestionMediaIds,
            String traceId) {
        return resolve(userId, entryPoint, new AgentDtos.MessageContextRequest(stable.questionId(),
                stable.courseId(), stable.lessonId(), questionMediaIds, aiQuestionMediaIds), traceId);
    }
}
