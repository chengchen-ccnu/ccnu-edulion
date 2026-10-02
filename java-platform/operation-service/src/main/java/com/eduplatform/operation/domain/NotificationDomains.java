package com.eduplatform.operation.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.eduplatform.operation.service.OperationException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Operation唯一领域HTTP适配；固定caller/独立只读秘密，有限超时，依赖错误503不降为允许或假空名单。 */
@Component
public class NotificationDomains {
    private final ObjectMapper mapper;
    private final String courseUrl,learningUrl,iamUrl,secret;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public NotificationDomains(ObjectMapper mapper,
            @Value("${COURSE_SERVICE_BASE_URL:http://127.0.0.1:8082}") String courseUrl,
            @Value("${LEARNING_SERVICE_BASE_URL:http://127.0.0.1:8084}") String learningUrl,
            @Value("${IAM_SERVICE_BASE_URL:http://127.0.0.1:8081}") String iamUrl,
            @Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret) {
        this.mapper=mapper; this.courseUrl=courseUrl; this.learningUrl=learningUrl; this.iamUrl=iamUrl; this.secret=secret;
    }
    /** 批量最多100个课程的最小事实，未知资源省略；字段或越界响应非法视为503。 */
    public List<Course> courses(List<String> ids) {
        if(ids.isEmpty()) return List.of();
        List<Course> result=call(courseUrl,"/internal/v1/notification-courses",Map.of("ids",ids),new TypeReference<List<Course>>(){});
        if(result==null || result.size()>100 || result.stream().anyMatch(c->c==null || !ids.contains(c.courseId()) || c.ownerTeacherId()==null || !java.util.Set.of("DRAFT","PUBLISHED","UNPUBLISHED","ARCHIVED").contains(c.publicationStatus()) || c.supportEmail()==null || c.availabilityRevision()<0)
                || result.stream().map(Course::courseId).distinct().count()!=result.size()) throw unavailable();
        return result;
    }
    /** 触发时点的有界历史收件人名单，游标单调；不使用当前名单替代历史。 */
    public Recipients recipients(String course,Instant at,String after) {
        Recipients result=call(learningUrl,"/internal/v1/notification-recipients/"+course+"?at="+URLEncoder.encode(at.toString(),StandardCharsets.UTF_8)+"&after="+after,null,new TypeReference<Recipients>(){});
        if(result==null || result.learnerIds()==null || result.learnerIds().size()>100 || result.hasMore() && result.learnerIds().isEmpty()) throw unavailable();
        String previous=after;
        for(String id:result.learnerIds()) {
            try { java.util.UUID.fromString(id); } catch(RuntimeException e) { throw unavailable(); }
            if(id.compareTo(previous)<=0) throw unavailable(); previous=id;
        }
        return result;
    }
    /** 本人当前ACTIVE/课程任务目标及触发时资格批量校验，输入每种最多100项。 */
    public Access access(String user,List<String> courseIds,List<String> taskIds,List<Check> checks) {
        Access result=call(learningUrl,"/internal/v1/notification-access",Map.of("learnerId",user,"courseIds",courseIds,"assessmentIds",taskIds,"checks",checks),new TypeReference<Access>(){});
        if(result==null || result.activeCourseIds()==null || result.accessibleAssessmentIds()==null || result.eligibleKeys()==null
                || !courseIds.containsAll(result.activeCourseIds()) || !taskIds.containsAll(result.accessibleAssessmentIds())
                || !checks.stream().map(Check::key).toList().containsAll(result.eligibleKeys())) throw unavailable();
        return result;
    }
    /** 单页收件人当前ACTIVE批量再授权，避免fan-out逐学生发HTTP。 */
    public List<String> currentRecipients(String course,List<String> ids) {
        if(ids.isEmpty()) return List.of();
        List<String> result=call(learningUrl,"/internal/v1/notification-current-recipients",Map.of("courseId",course,"learnerIds",ids),new TypeReference<List<String>>(){});
        if(result==null || !ids.containsAll(result)) throw unavailable(); return result;
    }
    /** 当前真正可作答任务事实；404表示目标不可见，其他失败不吞为已结束。 */
    public Task task(String id) {
        Task result=call(learningUrl,"/internal/v1/notification-assessments/"+id,null,new TypeReference<Task>(){});
        if(result==null || !id.equals(result.assessmentId()) || result.courseId()==null || result.title()==null || result.revision()<1) throw unavailable();
        return result;
    }
    /** IAM只返回本人可进入的申请ID，不获取审核理由或材料。 */
    public List<String> reviews(String user,List<String> ids) {
        if(ids.isEmpty()) return List.of();
        List<String> result=call(iamUrl,"/internal/v1/notification-review-access",Map.of("userId",user,"applicationIds",ids),new TypeReference<List<String>>(){});
        if(result==null || !ids.containsAll(result)) throw unavailable(); return result;
    }
    private <T> T call(String base,String path,Object body,TypeReference<T> type) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw unavailable();
        try {
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3))
                    .header("X-Internal-Caller","operation-service").header("X-Internal-Service-Token",secret)
                    .header("X-Trace-Id",java.util.UUID.randomUUID().toString());
            if(body==null) request.GET(); else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404) throw new OperationException(HttpStatus.NOT_FOUND,"OPERATION.NOT_FOUND","资源不存在或不可见");
            if(response.statusCode()!=200 || response.body().length()>262144) throw unavailable();
            return mapper.readValue(response.body(),type);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch(OperationException e) { throw e; }
        catch(Exception e) { throw unavailable(); }
    }
    public static OperationException unavailable() { return new OperationException(HttpStatus.SERVICE_UNAVAILABLE,"OPERATION.DEPENDENCY_UNAVAILABLE","消息依赖暂时不可用，请稍后重试"); }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Course(String courseId,String ownerTeacherId,String publicationStatus,boolean paused,long availabilityRevision,String supportEmail) {
        public boolean readable() { return !paused && ("PUBLISHED".equals(publicationStatus) || "ARCHIVED".equals(publicationStatus)); }
    }
    public record Check(String key,String courseId,Instant at) {}
    public record Access(List<String> activeCourseIds,List<String> accessibleAssessmentIds,List<String> eligibleKeys) {}
    public record Recipients(List<String> learnerIds,boolean hasMore) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Task(String assessmentId,String courseId,String title,long revision,boolean deliverable) {}
}
