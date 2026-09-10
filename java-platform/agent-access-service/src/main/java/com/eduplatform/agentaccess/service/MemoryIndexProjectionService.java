package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import com.eduplatform.agentaccess.web.MemoryIndexDtos;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 从 Agent Access 事实表生成可删除、可重建的记忆索引投影。 */
@Service
@ConditionalOnProperty(prefix = "platform.agent-index", name = "enabled", havingValue = "true")
public class MemoryIndexProjectionService {
    private final MemoryProfileRepository repository;

    public MemoryIndexProjectionService(MemoryProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * @param afterRevision 稳定游标中的 revision
     * @param afterEntityId 同 revision 下的 UUID 游标
     * @param entityId 事件增量读取的精确实体；提供时忽略分页游标
     * @param size 1..200 的页大小
     * @return 仅 ACTIVE 且未到期的 USER_PRIVATE 安全投影
     */
    public MemoryIndexDtos.Page page(long afterRevision, UUID afterEntityId, UUID entityId, int size) {
        var page = repository.page(afterRevision, afterEntityId.toString(), entityId, size);
        var items = page.items().stream().map(row -> new MemoryIndexDtos.Projection(
                row.id(), row.ownerUserId(), row.sourceRevision(), "JUNIOR", "MATH", row.memoryType(),
                row.safeText(), "USER_PRIVATE", row.accessScope(), row.courseId(), row.courseVersionId(),
                row.lessonId(), row.courseId() != null, row.expiresAt())).toList();
        MemoryIndexDtos.Cursor cursor = items.isEmpty() ? null : new MemoryIndexDtos.Cursor(
                items.get(items.size() - 1).sourceRevision(), items.get(items.size() - 1).entityId());
        return new MemoryIndexDtos.Page(items, page.hasMore(), cursor);
    }
}
