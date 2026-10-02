package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Operation最小只读授权适配；输入最多100个稳定ID，输出本域发布/owner/暂停事实，无正文或写入权限。 */
@RestController
public class NotificationCourseController {
    private final JdbcTemplate jdbc;
    private final String secret;
    private final String supportEmail;
    public NotificationCourseController(JdbcTemplate jdbc,
            @Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret,
            @Value("${platform.course.suspension.support-email:support@example.invalid}") String supportEmail) {
        this.jdbc=jdbc; this.secret=secret; this.supportEmail=supportEmail;
    }
    /** 固定Operation身份才可批量读取；缺失ID省略，数据库故障不冒充空数组，秘密常量时间比较。 */
    @PostMapping("/internal/v1/notification-courses")
    public List<Fact> facts(@RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token,
            @Valid @RequestBody Ids request) {
        if (secret.getBytes(StandardCharsets.UTF_8).length<32 || !"operation-service".equals(caller)
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new CourseException(HttpStatus.UNAUTHORIZED,"COURSE.INTERNAL_UNAUTHORIZED","内部调用身份无效");
        if(request.ids().isEmpty()) return List.of();
        String marks=String.join(",",java.util.Collections.nCopies(request.ids().size(),"?"));
        return jdbc.query("SELECT id,owner_teacher_id,publication_status,system_suspended,availability_revision FROM course WHERE id IN ("+marks+")",
                (rs,n)->new Fact(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getLong(5),supportEmail),
                request.ids().stream().map(UUID::toString).toArray());
    }
    public record Ids(@NotNull(message="字段不可为空") @Size(max=100,message="单批最多100项") List<@NotNull(message="字段不可为空") UUID> ids) {}
    public record Fact(String courseId,String ownerTeacherId,String publicationStatus,boolean paused,long availabilityRevision,String supportEmail) {}
}
