package com.eduplatform.media.web;

import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.service.MediaAssetService;
import com.eduplatform.media.service.ExternalImageTransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAPI v0.13 媒体 HTTP 适配器。只接收 Gateway 或受信领域服务传递的身份头与 JSON 元数据，
 * 不接收或转发文件字节；文件 I/O 由浏览器使用短期 OSS 授权完成。
 */
@RestController
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
@Validated
@RequestMapping("/api/v1/media")
public class MediaController {
    private final MediaAssetService service;
    private final ExternalImageTransferService externalImages;

    public MediaController(MediaAssetService service, ExternalImageTransferService externalImages) {
        this.service = service; this.externalImages = externalImages;
    }

    @PostMapping("/external-image-transfers")
    /** 受限转存一张历史题目 HTTPS 图片；响应和日志均不回显来源 URL。 */
    ResponseEntity<MediaDtos.Asset> transferExternalImage(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody MediaDtos.ExternalImageTransferRequest request,
            HttpServletRequest servletRequest) {
        MediaIdentity identity = MediaIdentity.require(userId, roles, permissions);
        ExternalImageTransferService.Result result = externalImages.transfer(identity.userId(), identity.roles(),
                identity.permissions(), request, trace(servletRequest));
        return result.created() ? ResponseEntity.status(201).body(result.asset()) : ResponseEntity.ok(result.asset());
    }

    @PostMapping("/uploads")
    /** 创建/重放上传会话；首次返回 201，幂等重放返回 200。 */
    ResponseEntity<MediaDtos.UploadSession> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody MediaDtos.CreateUploadRequest request,
            HttpServletRequest servletRequest) {
        MediaIdentity identity = MediaIdentity.require(userId, roles, permissions);
        MediaAssetService.CreateResult result = service.create(identity.userId(), identity.roles(),
                identity.permissions(), request, trace(servletRequest));
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/media/uploads/" + result.session().uploadId()))
                    .body(result.session());
        }
        return ResponseEntity.ok(result.session());
    }

    @GetMapping("/uploads/{uploadId}")
    /** 返回当前用户的会话、分片和过期状态。 */
    MediaDtos.UploadSession getUpload(@PathVariable String uploadId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return service.getUpload(uploadId, MediaIdentity.require(userId, roles).userId());
    }

    @PostMapping("/uploads/{uploadId}/parts/authorize")
    /** 为一个 Multipart 分片签发短期 PUT 授权。 */
    MediaDtos.UploadGrant authorizePart(@PathVariable String uploadId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody MediaDtos.AuthorizePartRequest request) {
        return service.authorizePart(uploadId, MediaIdentity.require(userId, roles).userId(), request);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    /** 提交分片/校验 OSS 对象并创建 PROCESSING 资产。 */
    ResponseEntity<MediaDtos.Asset> complete(@PathVariable String uploadId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody MediaDtos.CompleteUploadRequest request,
            HttpServletRequest servletRequest) {
        MediaAssetService.CompleteResult result = service.complete(uploadId,
                MediaIdentity.require(userId, roles).userId(), request, trace(servletRequest));
        return result.accepted() ? ResponseEntity.accepted().body(result.asset()) : ResponseEntity.ok(result.asset());
    }

    @DeleteMapping("/uploads/{uploadId}")
    /** 幂等取消尚未转换为资产的会话，成功返回 204。 */
    ResponseEntity<Void> cancel(@PathVariable String uploadId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            HttpServletRequest servletRequest) {
        service.cancel(uploadId, MediaIdentity.require(userId, roles).userId(), trace(servletRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    /** 分页查询当前用户的媒体资产及处理状态。 */
    MediaDtos.AssetPage list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestParam(required = false) MediaPurpose purpose,
            @RequestParam(required = false) MediaStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listAssets(MediaIdentity.require(userId, roles).userId(), purpose, status, page, size);
    }

    @GetMapping("/{mediaId}")
    /** 查询当前用户拥有的一个资产，不返回签名 URL。 */
    MediaDtos.Asset getAsset(@PathVariable String mediaId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        return service.getAsset(mediaId, MediaIdentity.require(userId, roles).userId());
    }

    @PostMapping("/{mediaId}/access-grants")
    /** 为当前用户的 READY 原始/派生对象签发短期读授权。 */
    ResponseEntity<MediaDtos.AccessGrant> access(@PathVariable String mediaId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody MediaDtos.CreateAccessGrantRequest request,
            HttpServletRequest servletRequest) {
        MediaIdentity identity = MediaIdentity.require(userId, roles, permissions);
        MediaDtos.AccessGrant grant = service.access(mediaId, identity.userId(), identity.roles(), identity.permissions(),
                request, trace(servletRequest));
        return ResponseEntity.status(201).body(grant);
    }

    @PostMapping("/{mediaId}/binding-validations")
    /** 供领域服务绑定前验证当前账号拥有、用途匹配且 READY；成功不直接创建引用。 */
    MediaDtos.BindingValidation validateBinding(@PathVariable String mediaId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @Valid @RequestBody MediaDtos.BindingValidationRequest request) {
        return service.validateBinding(mediaId, MediaIdentity.require(userId, roles).userId(), request);
    }

    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(com.eduplatform.media.config.TraceFilter.ATTRIBUTE);
    }
}
