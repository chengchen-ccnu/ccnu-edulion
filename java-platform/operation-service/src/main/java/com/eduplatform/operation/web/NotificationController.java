package com.eduplatform.operation.web;

import com.eduplatform.operation.service.NotificationService;
import com.eduplatform.operation.web.NotificationDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 头像菜单消息后端契约；仅本人，列表不标已读，没有用户删除或TTL操作。 */
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    private String user(HttpServletRequest r) {
        return OperationIdentity.authenticated(r.getHeader("X-User-Id"),r.getHeader("X-User-Roles"),r.getHeader("X-User-Permissions")).userId();
    }
    /** 当前授权消息按时间分页；不返回正文/材料/理由，依赖故障503。 */
    @GetMapping
    public MessagePage list(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest r) { return service.list(user(r),page,size); }
    /** 列表相同授权口径的未读及待确认数量。 */
    @GetMapping("/unread-count")
    public Counts counts(HttpServletRequest r) { return service.counts(user(r)); }
    /** 全部已读跳过公告，返回剩余数量；依赖失败不伪称成功。 */
    @PostMapping("/read-all")
    public Counts all(HttpServletRequest r) { return service.readAll(user(r)); }
    /** 进入前重新授权固定目标，不自动标已读。 */
    @GetMapping("/{notificationId}/target")
    public Target target(@PathVariable UUID notificationId,HttpServletRequest r) { return service.target(user(r),notificationId.toString()); }
    /** 目标成功加载后由客户端提交精确revision；公告仍需我知道了。 */
    @PostMapping("/{notificationId}/read")
    public Counts read(@PathVariable UUID notificationId,@Valid @RequestBody Read input,HttpServletRequest r) { return service.read(user(r),notificationId.toString(),input.expectedRevision()); }
}
