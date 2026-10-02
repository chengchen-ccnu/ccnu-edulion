package com.eduplatform.course.web;

import com.eduplatform.course.media.MediaCoverClient;
import com.eduplatform.course.service.CourseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Operation公开卡片重建源；只读本域发布版本，非公开返回tombstone，封面仍经Course→Media授权。 */
@RestController
public class HotCourseSourceController {
    private final JdbcTemplate jdbc;
    private final MediaCoverClient media;
    private final String secret;
    public HotCourseSourceController(JdbcTemplate jdbc,MediaCoverClient media,@Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret) {
        this.jdbc=jdbc;this.media=media;this.secret=secret;
    }
    /** 输入可空稳定ID游标，输出最多100课程事实；并发新课程由可靠事件及下轮补齐，不扫描正文。 */
    @GetMapping("/internal/v1/hot-course-source")
    public Page page(@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="100") int size,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        authorize(caller,token);
        if(size<1 || size>100) throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,"COURSE.VALIDATION_FAILED","单页范围为1至100");
        if(!after.isEmpty()) try {UUID.fromString(after);} catch(IllegalArgumentException failure) {
            throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,"COURSE.VALIDATION_FAILED","课程游标不合法");
        }
        List<Card> rows=query("c.id>? ORDER BY c.id LIMIT "+(size+1),after);
        return new Page(rows.subList(0,Math.min(size,rows.size())),rows.size()>size);
    }
    /** 输入课程ID，输出当前卡片或visible=false，不泄露草稿/暂停内容；数据库失败不冒充下架。 */
    @GetMapping("/internal/v1/hot-course-source/{courseId}")
    public Card source(@PathVariable UUID courseId,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        authorize(caller,token);return current(courseId.toString());
    }
    /** 重新检查公开资格及稳定mediaId后申请短授权；无封面404、授权依赖失败503，不返回legacy URL。 */
    @PostMapping("/internal/v1/hot-course-cover/{courseId}")
    public Cover cover(@PathVariable UUID courseId,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token,
            @RequestHeader(value="X-Trace-Id",defaultValue="") String trace) {
        authorize(caller,token);Card card=current(courseId.toString());
        if(!card.visible() || card.coverMediaId()==null)
            throw new CourseException(HttpStatus.NOT_FOUND,"COURSE.NOT_FOUND","课程封面不可用");
        var grant=media.issueCoverGrant(card.coverMediaId(),"00000000-0000-0000-0000-000000000000",trace.matches("[A-Za-z0-9_-]{8,64}")?trace:UUID.randomUUID().toString());
        return new Cover(card.coverMediaId(),grant.url(),grant.expiresAt());
    }
    private Card current(String id) { return query("c.id=?",id).stream().findFirst().orElse(Card.hidden(id)); }
    /** 元数据来自当前发布版本，归档/草稿/下架/系统暂停均只能输出ID失效事实。 */
    private List<Card> query(String predicate,String argument) {
        return jdbc.query("SELECT c.id,c.publication_status,c.system_suspended,c.published_at,v.lifecycle_status,v.title,v.summary,v.instructor_display_name,v.category_id,v.level,v.access_type,v.cover_media_id FROM course c LEFT JOIN course_version v ON v.id=c.published_version_id WHERE "+predicate,
                (rs,n)-> {
                    String id=rs.getString(1);
                    if(!"PUBLISHED".equals(rs.getString(2)) || rs.getBoolean(3) || !"PUBLISHED".equals(rs.getString(5))) return Card.hidden(id);
                    return new Card(id,true,rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getTimestamp(4).toInstant(),rs.getString(12));
                },argument);
    }
    /** 独立只读秘密与固定caller同时匹配；空配置关闭入口，不授课程写入或OSS访问。 */
    private void authorize(String caller,String token) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32 || !"operation-service".equals(caller)
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new CourseException(HttpStatus.UNAUTHORIZED,"COURSE.INTERNAL_UNAUTHORIZED","内部调用身份无效");
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Card(String courseId,boolean visible,String title,String summary,String instructorDisplayName,
            String categoryId,String level,String accessType,Instant publishedAt,String coverMediaId) {
        static Card hidden(String id) {return new Card(id,false,null,null,null,null,null,null,null,null);}
    }
    public record Page(List<Card> items,boolean hasMore) {}
    public record Cover(String mediaId,String url,Instant expiresAt) {}
}
