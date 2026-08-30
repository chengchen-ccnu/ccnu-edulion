package com.eduplatform.media.service;

import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.web.MediaDtos;
import com.eduplatform.shared.api.FieldError;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 媒体用途白名单，统一校验上传角色、MIME、扩展名、大小与直传模式。
 *
 * <p>T08A 显式启用课程用途；T09 追加题目附件和导入源；T12D 仅向普通 STUDENT 账号开放教师资质材料；
 * 其余冻结枚举仍返回未启用，
 * 避免仅因枚举存在就绕过所属领域的权限和文件策略评审。</p>
 */
@Component
public class MediaPurposePolicy {
    private static final Map<MediaPurpose, Policy> ENABLED = Map.ofEntries(
            Map.entry(MediaPurpose.COURSE_COVER, new Policy(Set.of("TEACHER"),
                    Set.of("image/jpeg", "image/png", "image/webp"),
                    Set.of("jpg", "jpeg", "png", "webp"), 10L * 1024 * 1024, false)),
            Map.entry(MediaPurpose.LESSON_VIDEO, new Policy(Set.of("TEACHER"),
                    Set.of("video/mp4", "video/quicktime", "video/webm"),
                    Set.of("mp4", "mov", "webm"), 10L * 1024 * 1024 * 1024, true)),
            Map.entry(MediaPurpose.VIDEO_POSTER, new Policy(Set.of("TEACHER"),
                    Set.of("image/jpeg", "image/png", "image/webp"),
                    Set.of("jpg", "jpeg", "png", "webp"), 10L * 1024 * 1024, false)),
            Map.entry(MediaPurpose.SUBTITLE, new Policy(Set.of("TEACHER"),
                    Set.of("text/vtt", "application/x-subrip", "text/plain"),
                    Set.of("vtt", "srt"), 5L * 1024 * 1024, false)),
            Map.entry(MediaPurpose.COURSEWARE, new Policy(Set.of("TEACHER"),
                    Set.of("application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                    Set.of("pdf", "pptx"), 200L * 1024 * 1024, true)),
            Map.entry(MediaPurpose.ARTICLE_ATTACHMENT, new Policy(Set.of("TEACHER"),
                    Set.of("application/pdf", "text/plain", "image/jpeg", "image/png", "image/webp"),
                    Set.of("pdf", "txt", "jpg", "jpeg", "png", "webp"), 50L * 1024 * 1024, true)),
            Map.entry(MediaPurpose.QUESTION_IMAGE, new Policy(Set.of("TEACHER"),
                    Set.of("image/jpeg", "image/png", "image/webp"),
                    Set.of("jpg", "jpeg", "png", "webp"), 10L * 1024 * 1024, false)),
            Map.entry(MediaPurpose.QUESTION_AUDIO, new Policy(Set.of("TEACHER"),
                    Set.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/ogg"),
                    Set.of("mp3", "m4a", "wav", "ogg"), 50L * 1024 * 1024, true)),
            Map.entry(MediaPurpose.QUESTION_ATTACHMENT, new Policy(Set.of("TEACHER"),
                    Set.of("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    Set.of("pdf", "docx"), 50L * 1024 * 1024, true)),
            Map.entry(MediaPurpose.QUESTION_IMPORT_SOURCE, new Policy(Set.of("TEACHER"),
                    Set.of("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    Set.of("csv", "xlsx"), 100L * 1024 * 1024, true)),
            Map.entry(MediaPurpose.TEACHER_QUALIFICATION, new Policy(Set.of("STUDENT"),
                    Set.of("application/pdf", "image/jpeg", "image/png", "image/webp"),
                    Set.of("pdf", "jpg", "jpeg", "png", "webp"), 20L * 1024 * 1024, true)));

    /**
     * 校验客户端声明并选择上传模式。
     * @param request 不可变的文件声明
     * @param roles Gateway 传入的已认证角色集合
     * @param multipartThreshold 触发 Multipart 的字节阈值
     * @return 规范化 MIME/扩展名与已选模式
     * @throws MediaException 用途未启用、角色不允许或文件声明不合法时抛出
     */
    public ValidatedUpload validate(MediaDtos.CreateUploadRequest request, Set<String> roles, long multipartThreshold) {
        Policy policy = ENABLED.get(request.purpose());
        if (policy == null) {
            throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA.PURPOSE_NOT_ENABLED",
                    "该媒体用途尚未启用");
        }
        if (roles.stream().noneMatch(policy.roles()::contains)) {
            throw new MediaException(HttpStatus.FORBIDDEN, "MEDIA.PURPOSE_FORBIDDEN", "当前角色不能上传该用途的媒体");
        }
        String filename = request.fileName().trim();
        if (filename.contains("/") || filename.contains("\\") || filename.indexOf('\0') >= 0) {
            invalid("fileName", "文件名不能包含路径");
        }
        int dot = filename.lastIndexOf('.');
        String extension = dot > 0 && dot < filename.length() - 1
                ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime = request.mimeType().trim().toLowerCase(Locale.ROOT);
        if (!policy.extensions().contains(extension)) invalid("fileName", "文件扩展名与用途不匹配");
        if (!policy.mimeTypes().contains(mime)) invalid("mimeType", "MIME 类型与用途不匹配");
        if (request.size() > policy.maxBytes()) invalid("size", "文件大小超过该用途限制");
        UploadMode mode = policy.multipartCapable() && request.size() >= multipartThreshold
                ? UploadMode.MULTIPART : UploadMode.SINGLE_PUT;
        return new ValidatedUpload(filename, extension, mime, mode, policy.maxBytes());
    }

    private static void invalid(String field, String reason) {
        throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED", "请求参数不合法",
                java.util.List.of(new FieldError(field, reason)));
    }

    public record ValidatedUpload(String filename, String extension, String mimeType, UploadMode mode, long maxBytes) {}

    private record Policy(Set<String> roles, Set<String> mimeTypes, Set<String> extensions,
            long maxBytes, boolean multipartCapable) {}
}
