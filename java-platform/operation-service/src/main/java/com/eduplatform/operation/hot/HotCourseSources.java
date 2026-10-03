package com.eduplatform.operation.hot;

import static com.eduplatform.operation.hot.HotCourseModels.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 后台专用领域只读适配器；固定内部身份、3秒超时，失败不当成零人数或下架。 */
@Component
public class HotCourseSources {
    private final String courseUrl,learningUrl,secret;
    private final ObjectMapper mapper;
    private final String tracePrefix=System.getenv().getOrDefault("OPERATION_HOT_TRACE_PREFIX","hot");
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public HotCourseSources(ObjectMapper mapper,@Value("${COURSE_SERVICE_BASE_URL:http://127.0.0.1:8082}") String courseUrl,
            @Value("${LEARNING_SERVICE_BASE_URL:http://127.0.0.1:8084}") String learningUrl,
            @Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret) {
        this.mapper=mapper;this.courseUrl=courseUrl;this.learningUrl=learningUrl;this.secret=secret;
    }
    /** 输入可空稳定游标，输出严格有序最多100项；坏页/假hasMore拒绝，避免无限扫描。 */
    public SourcePage page(String after) {
        SourcePage page=call(courseUrl,"/internal/v1/hot-course-source?after="+after+"&size=50",false,SourcePage.class);
        if(page.items()==null || page.items().size()>50 || (page.hasMore() && page.items().isEmpty())) throw unavailable();
        String previous=after;
        for(SourceCard card:page.items()) {
            validate(card); if(card.courseId().compareTo(previous)<=0) throw unavailable();previous=card.courseId();
        }
        return page;
    }
    /** 输入稳定ID，输出当前公开卡片或tombstone；不能由旧事件载荷还原可见性。 */
    public SourceCard card(String id) {
        SourceCard card=call(courseUrl,"/internal/v1/hot-course-source/"+id,false,SourceCard.class);
        validate(card);if(!id.equals(card.courseId())) throw unavailable();return card;
    }
    /** 读取领域同一快照的真实去重人数和当前报名；窗口异常/负数拒绝，不加总日人数。 */
    public Learning learning(String id) {
        Learning fact=call(learningUrl,"/internal/v1/hot-learning-source/"+id,false,Learning.class);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(!id.equals(fact.courseId()) || fact.learners7d()<0 || fact.activeEnrollments()<0
                || !today.equals(fact.windowEnd()) || !today.minusDays(6).equals(fact.windowStart())) throw unavailable();
        return fact;
    }
    /** Course重新授权当前封面；返回实际有效期，异常留给独立缺图降级，不接受永久/已过期URL。 */
    public Cover cover(String id,String mediaId) {
        Cover grant=call(courseUrl,"/internal/v1/hot-course-cover/"+id,true,Cover.class);
        if(!mediaId.equals(grant.mediaId()) || grant.url()==null || grant.expiresAt()==null
                || !Instant.now().plusSeconds(5).isBefore(grant.expiresAt()) || grant.expiresAt().isAfter(Instant.now().plusSeconds(3600))) throw unavailable();
        URI uri=URI.create(grant.url());if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null) throw unavailable();
        return grant;
    }
    /** 校验公开卡片必需字段及枚举；非公开只要求稳定ID，不能把无效响应缓存到门户。 */
    private void validate(SourceCard card) {
        UUID.fromString(card.courseId());
        if(!card.visible()) return;
        if(card.title()==null || card.summary()==null || card.instructorDisplayName()==null || card.publishedAt()==null
                || !Set.of("BEGINNER","INTERMEDIATE","ADVANCED").contains(card.level())
                || !Set.of("PUBLIC","ENROLLMENT_REQUIRED").contains(card.accessType())) throw unavailable();
        UUID.fromString(card.categoryId());if(card.coverMediaId()!=null) UUID.fromString(card.coverMediaId());
    }
    /** HTTP仅用于后台，秘密只进头；错误不记录完整响应/URL或内部Token。 */
    private <T> T call(String base,String path,boolean post,Class<T> type) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw unavailable();
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3))
                    .header("X-Internal-Caller","operation-service").header("X-Internal-Service-Token",secret)
                    .header("X-Trace-Id",tracePrefix+"_"+UUID.randomUUID());
            if(post) builder.POST(HttpRequest.BodyPublishers.noBody());else builder.GET();
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>262144) throw unavailable();
            T result=mapper.readValue(response.body(),type);if(result==null) throw unavailable();return result;
        } catch(InterruptedException failure) {Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failure) {throw unavailable();}
    }
    private static IllegalStateException unavailable() {return new IllegalStateException("热点源事实暂不可用");}
}
