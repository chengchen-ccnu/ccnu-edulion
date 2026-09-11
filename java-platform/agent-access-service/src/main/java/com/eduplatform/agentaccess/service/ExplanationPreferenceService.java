package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import com.eduplatform.agentaccess.web.AgentDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理学生本人主动设置的讲解偏好。
 *
 * <p>公开接口只投影 EXPLICIT 事实；推断偏好、画像和证据都不会离开服务。写入使用 revision
 * 防止并发覆盖，并以 clientRequestId 保存稳定结果，网络重试不会再次推进 revision。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class ExplanationPreferenceService {
    private final MemoryProfileRepository repository;

    public ExplanationPreferenceService(MemoryProfileRepository repository) {
        this.repository = repository;
    }

    /** 返回本人明确值；未设置或只有推断值时返回默认分步引导和 revision 0。 */
    public AgentDtos.ExplanationPreferenceView get(UUID owner) {
        return repository.findExplanationPreference(owner)
                .filter(row -> "EXPLICIT".equals(row.source()))
                .map(row -> new AgentDtos.ExplanationPreferenceView(
                        AgentDtos.ExplanationPreference.valueOf(row.value()), row.revision(), true))
                .orElseGet(() -> new AgentDtos.ExplanationPreferenceView(
                        AgentDtos.ExplanationPreference.GUIDED_STEP_BY_STEP, 0, false));
    }

    /**
     * 按 expectedRevision 原子保存明确偏好。相同 clientRequestId 与相同请求返回原结果；不同请求冲突。
     * @throws AgentException revision 过期或幂等键复用时返回 409，不修改已有偏好
     */
    @Transactional
    public AgentDtos.ExplanationPreferenceView update(UUID owner, AgentDtos.UpdateExplanationPreferenceRequest request) {
        String fingerprint = fingerprint(request.value().name(), request.expectedRevision());
        repository.lockPreferenceCommandLane();
        var replay = repository.findPreferenceCommand(owner, request.clientRequestId()).orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.fingerprint())) {
                throw conflict("clientRequestId 已用于不同的偏好请求");
            }
            return new AgentDtos.ExplanationPreferenceView(
                    AgentDtos.ExplanationPreference.valueOf(replay.value()), replay.revision(), true);
        }
        Instant now = Instant.now();
        long revision = repository.saveExplicitExplanationPreference(owner, request.value().name(),
                request.expectedRevision(), now);
        if (revision < 0) throw conflict("讲解偏好已变化，请刷新后重试");
        repository.insertPreferenceCommand(owner, request.clientRequestId(), fingerprint,
                request.value().name(), revision, now);
        return new AgentDtos.ExplanationPreferenceView(request.value(), revision, true);
    }

    private static String fingerprint(String style, long revision) {
        try {
            byte[] value = (style + "|" + revision).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot fingerprint preference request", failure);
        }
    }

    private static AgentException conflict(String message) {
        return new AgentException(HttpStatus.CONFLICT, "AGENT.PREFERENCE_REVISION_CONFLICT", message);
    }
}
