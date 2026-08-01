package com.eduplatform.media.messaging;

import tools.jackson.databind.JsonNode;

/** 已验证的 v1 媒体事件信封；保留原始 JSON 用于幂等处理与调试。 */
public record MediaDomainEvent(String eventId, String eventType, int eventVersion, String aggregateId,
        String occurredAt, String traceId, JsonNode payload, String rawJson) {}
