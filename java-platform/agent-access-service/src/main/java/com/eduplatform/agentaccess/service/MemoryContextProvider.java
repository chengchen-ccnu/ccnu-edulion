package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 为单次 Agent run 注入本人当前有效偏好和安全记忆摘要，隐藏主题画像从不返回 Python。 */
@Component
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class MemoryContextProvider {
    private final MemoryProfileRepository repository;

    public MemoryContextProvider(MemoryProfileRepository repository) {
        this.repository = repository;
    }

    /** 返回本人当前有效的少量讲解偏好；到期推断偏好和其他用户数据不会进入结果。 */
    public List<Map<String, Object>> preferences(UUID owner) {
        return repository.activePreferences(owner, 8, Instant.now()).stream().map(row ->
                Map.<String, Object>of("kind", row.kind(), "value", row.value(), "source", row.source())).toList();
    }

    /**
     * 课程来源记忆只有在本次请求刚取得相同 course/version/lesson 授权时才返回；缺上下文或任一字段
     * 不匹配都 fail-closed。普通 USER_PRIVATE 记忆仍按可信 owner 精确隔离。
     */
    public List<Map<String, Object>> summaries(UUID owner, String courseId, String versionId, String lessonId) {
        return repository.activeForOwner(owner, 32, Instant.now()).stream()
                .filter(row -> row.courseId() == null || authorizedCourse(row.courseId().toString(),
                        row.courseVersionId(), row.lessonId() == null ? null : row.lessonId().toString(),
                        courseId, versionId, lessonId))
                .limit(8)
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("summaryId", row.id().toString());
                    value.put("revision", row.sourceRevision());
                    value.put("text", row.safeText());
                    value.put("authorizedByJava", true);
                    return value;
                }).toList();
    }

    private static boolean authorizedCourse(String memoryCourse, String memoryVersion, String memoryLesson,
            String course, String version, String lesson) {
        return course != null && version != null && lesson != null
                && memoryCourse.equals(course) && memoryVersion.equals(version) && memoryLesson.equals(lesson);
    }
}
