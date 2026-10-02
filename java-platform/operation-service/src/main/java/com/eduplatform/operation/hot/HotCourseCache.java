package com.eduplatform.operation.hot;

import static com.eduplatform.operation.hot.HotCourseModels.*;
import java.time.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 热点单key原子读模型；只存最多6卡片的短期投影，Redis故障绝不触发数据库/领域回源。 */
@Component
public class HotCourseCache {
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectMapper mapper;
    private final String key;
    public HotCourseCache(ObjectProvider<StringRedisTemplate> redis,ObjectMapper mapper,
            @Value("${platform.operation.hot.namespace:edu:operation:hot:v1}") String namespace) {
        if(!namespace.matches("[A-Za-z0-9:_-]{1,150}")) throw new IllegalArgumentException("热点namespace不合法");
        this.redis=redis;this.mapper=mapper;this.key=namespace+":board";
    }
    /** 输入无身份的首页GET，输出当前榜单或明确降级；只执行Redis GET，封面到期仅内存裁剪。 */
    public Board read() {
        try {
            var client=redis.getIfAvailable();if(client==null) return Board.degraded("UNAVAILABLE");
            String json=client.opsForValue().get(key);if(json==null) return Board.degraded("UNINITIALIZED");
            Board board=mapper.readValue(json,Board.class);Instant now=Instant.now();
            if(board.validUntil()==null || !now.isBefore(board.validUntil())) return Board.degraded("UNINITIALIZED");
            return new Board(board.status(),board.items().stream().map(card->card.at(now)).toList(),board.generatedAt(),board.validUntil(),board.windowStart(),board.windowEnd());
        } catch(RuntimeException failure) {return Board.degraded("UNAVAILABLE");}
    }
    /** 后台输入已排序公开卡片，原子SET完整结果；TTL75～90秒抖动并在上海零点之前失效。 */
    public void publish(Board board) {
        StringRedisTemplate client=redis.getIfAvailable();if(client==null) throw new IllegalStateException("热点Redis不可用");
        Instant now=Instant.now();Instant until=now.plusSeconds(ThreadLocalRandom.current().nextLong(75,91));
        Instant midnight=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        if(midnight.isBefore(until)) until=midnight;
        if(board.validUntil()!=null && board.validUntil().isBefore(until)) until=board.validUntil();
        if(!now.isBefore(until)) {invalidate();return;}
        Board output=new Board(board.status(),board.items(),now,until,board.windowStart(),board.windowEnd());
        client.opsForValue().set(key,mapper.writeValueAsString(output),Duration.between(now,until));
    }
    /** 可靠事件登记后尽力失效整个小榜单；Redis故障由TTL及后台重建修复，不回滚领域事实。 */
    public void invalidate() {
        try {var client=redis.getIfAvailable();if(client!=null) client.delete(key);}catch(RuntimeException ignored) {}
    }
}
