package com.eduplatform.operation.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** OpenAPI公告/消息HTTP投影；正文纯文本，目标只用稳定ID，required nullable字段显式序列化。 */
public final class NotificationDtos {
    private NotificationDtos() {}
    public record Create(@NotNull(message="字段不可为空") UUID clientRequestId,@NotBlank(message="标题或正文不能为空白") @Size(max=120,message="标题最多120个字符") String title,@NotBlank(message="标题或正文不能为空白") @Size(max=20000,message="正文最多20000个字符") String body) {}
    public record Update(@NotNull(message="字段不可为空") UUID clientRequestId,@Min(value=1,message="版本必须大于或等于1") long expectedRevision,@NotBlank(message="标题或正文不能为空白") @Size(max=120,message="标题最多120个字符") String title,@NotBlank(message="标题或正文不能为空白") @Size(max=20000,message="正文最多20000个字符") String body) {}
    public record Revision(@NotNull(message="字段不可为空") UUID clientRequestId,@Min(value=1,message="版本必须大于或等于1") long expectedRevision) {}
    public record Read(@Min(value=1,message="版本必须大于或等于1") long expectedRevision) {}
    public record Announcement(String id,String courseId,long revision,String status,String title,String body,
            Instant publishedAt,Instant updatedAt,boolean confirmationRequired,boolean confirmed) {}
    public record Audit(long revision,String status,String title,String body,Instant changedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnnouncementPage(List<Announcement> items,int page,int size,long total,Announcement latestUnconfirmed,long unconfirmedCount) {}
    public record AuditPage(List<Audit> items,int page,int size,long total) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Message(String id,String kind,String action,String title,String targetId,String courseId,long revision,
            boolean unread,boolean confirmationRequired,Instant occurredAt) {}
    public record MessagePage(List<Message> items,int page,int size,long total) {}
    public record Counts(long unreadCount,long unconfirmedAnnouncementCount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(String targetType,String targetId,String courseId,long revision,boolean paused,String supportEmail) {}
}
