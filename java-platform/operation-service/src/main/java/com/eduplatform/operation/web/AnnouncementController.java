package com.eduplatform.operation.web;

import com.eduplatform.operation.config.TraceFilter;
import com.eduplatform.operation.service.AnnouncementService;
import com.eduplatform.operation.web.NotificationDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 课程公告独立受保护入口；Gateway重写可信身份，教师能力与课程owner双重检查，学生ACTIVE报名授权。 */
@RestController
@Validated
public class AnnouncementController {
    private final AnnouncementService service;
    public AnnouncementController(AnnouncementService service) { this.service=service; }
    private String user(HttpServletRequest r,boolean teacher) {
        return (teacher?OperationIdentity.teaching(r.getHeader("X-User-Id"),r.getHeader("X-User-Roles"),r.getHeader("X-User-Permissions")):
                OperationIdentity.learner(r.getHeader("X-User-Id"),r.getHeader("X-User-Roles"),r.getHeader("X-User-Permissions"))).userId();
    }
    /** owner公告分页包含撤回事实，学生名单/确认记录不返回教师。 */
    @GetMapping("/api/v1/teacher/courses/{courseId}/announcements")
    public AnnouncementPage teacherList(@PathVariable UUID courseId,@RequestParam(defaultValue="1") @Min(1) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest r) {
        return service.list(user(r,true),courseId.toString(),true,page,size);
    }
    /** 标题/纯文本新公告与Outbox同事务；异步送达状态不冒充同步逐学生成功。 */
    @PostMapping("/api/v1/teacher/courses/{courseId}/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public Announcement create(@PathVariable UUID courseId,@Valid @RequestBody Create input,HttpServletRequest r) {
        return service.create(user(r,true),courseId.toString(),input,(String)r.getAttribute(TraceFilter.ATTRIBUTE));
    }
    /** 当前revision改版保留审计，同一条消息重置未读/确认，冲突409不覆盖。 */
    @PutMapping("/api/v1/teacher/courses/{courseId}/announcements/{announcementId}")
    public Announcement update(@PathVariable UUID courseId,@PathVariable UUID announcementId,@Valid @RequestBody Update input,HttpServletRequest r) {
        return service.change(user(r,true),courseId.toString(),announcementId.toString(),input,false,(String)r.getAttribute(TraceFilter.ATTRIBUTE));
    }
    /** owner显式撤回保留审计；学生旧链接立即404，不再提供正文。 */
    @PostMapping("/api/v1/teacher/courses/{courseId}/announcements/{announcementId}/withdraw")
    public Announcement withdraw(@PathVariable UUID courseId,@PathVariable UUID announcementId,@Valid @RequestBody Revision input,HttpServletRequest r) {
        return service.change(user(r,true),courseId.toString(),announcementId.toString(),new Update(input.clientRequestId(),input.expectedRevision(),"",""),true,(String)r.getAttribute(TraceFilter.ATTRIBUTE));
    }
    /** owner有界不可变版本审计，不提供个人阅读名单。 */
    @GetMapping("/api/v1/teacher/courses/{courseId}/announcements/{announcementId}/revisions")
    public AuditPage revisions(@PathVariable UUID courseId,@PathVariable UUID announcementId,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest r) {
        return service.revisions(user(r,true),courseId.toString(),announcementId.toString(),page,size);
    }
    /** 当前ACTIVE学生读取独立公告区与最新未确认摘要；公共课程详情不能复用此正文。 */
    @GetMapping("/api/v1/courses/{courseId}/announcements")
    public AnnouncementPage studentList(@PathVariable UUID courseId,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest r) {
        return service.list(user(r,false),courseId.toString(),false,page,size);
    }
    /** 读最新版不自动确认或清未读；退课/撤回/暂停安全404。 */
    @GetMapping("/api/v1/courses/{courseId}/announcements/{announcementId}")
    public Announcement get(@PathVariable UUID courseId,@PathVariable UUID announcementId,HttpServletRequest r) {
        return service.studentGet(user(r,false),courseId.toString(),announcementId.toString());
    }
    /** 我知道了确认精确revision并清对应未读；旧版本409、幂等命令不确认后来版本。 */
    @PostMapping("/api/v1/courses/{courseId}/announcements/{announcementId}/confirm")
    public Announcement confirm(@PathVariable UUID courseId,@PathVariable UUID announcementId,@Valid @RequestBody Revision input,HttpServletRequest r) {
        return service.confirm(user(r,false),courseId.toString(),announcementId.toString(),input);
    }
}
