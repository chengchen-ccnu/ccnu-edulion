package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.progress.RedisProgressBufferStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 容量服务进程退出后执行的精确清理；run ID 与容量测试相同，不扫描或清空共享业务数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true", "platform.learning.progress-buffer.enabled=false"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12G1_CAPACITY_CLEANUP_ENABLED", matches = "true")
class T12G1ProgressBufferCapacityCleanupTest {
    private static final String COURSE = "5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057";
    private static final String LESSON = "70775426-c55f-4662-bbc7-14750ce183e6";
    private static final String DIRTY = "edu:learning:progress:dirty";
    private static final int LEARNERS = 1_000;

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void cleanupExactCapacityRun() {
        String runId = System.getenv("T12G1_CAPACITY_RUN_ID");
        if (runId == null || runId.isBlank()) throw new IllegalStateException("T12G1_CAPACITY_RUN_ID is required");
        List<String> learners = new ArrayList<>(LEARNERS);
        for (int i = 0; i < LEARNERS; i++) {
            learners.add(UUID.nameUUIDFromBytes((runId + "-learner-" + i).getBytes(StandardCharsets.UTF_8)).toString());
        }
        for (int offset = 0; offset < learners.size(); offset += 100) {
            List<String> chunk = learners.subList(offset, Math.min(offset + 100, learners.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Object[] args = new Object[chunk.size() + 2];
            args[0] = COURSE;
            args[1] = LESSON;
            for (int i = 0; i < chunk.size(); i++) args[i + 2] = chunk.get(i);
            jdbc.update("DELETE FROM lesson_progress_event WHERE course_id=? AND lesson_id=? AND learner_id IN ("
                    + placeholders + ")", args);
            jdbc.update("DELETE FROM lesson_progress WHERE course_id=? AND lesson_id=? AND learner_id IN ("
                    + placeholders + ")", args);
        }
        List<String> keys = learners.stream().map(value -> RedisProgressBufferStore.key(value, COURSE, LESSON)).toList();
        redis.delete(keys);
        redis.opsForZSet().remove(DIRTY, keys.toArray());
        assertThat(redis.opsForZSet().range(DIRTY, 0, -1))
                .as("capacity run must not leave dirty members")
                .noneMatch(value -> value != null && keys.contains(value));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE course_id=? AND lesson_id=? AND learner_id IN ("
                + String.join(",", Collections.nCopies(LEARNERS, "?")) + ")", Integer.class,
                concat(COURSE, LESSON, learners))).isZero();
        System.out.printf("T12G.1 capacity cleanup learners=%d mysqlRows=0 redisKeys=0%n", LEARNERS);
    }

    private static Object[] concat(String course, String lesson, List<String> learners) {
        Object[] args = new Object[learners.size() + 2];
        args[0] = course;
        args[1] = lesson;
        for (int i = 0; i < learners.size(); i++) args[i + 2] = learners.get(i);
        return args;
    }
}
