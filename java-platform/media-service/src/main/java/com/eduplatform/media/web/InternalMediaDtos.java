package com.eduplatform.media.web;

import com.eduplatform.media.domain.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** media-service 内部服务授权请求；该契约不经过 Gateway，也不向浏览器开放。 */
public final class InternalMediaDtos {
    private InternalMediaDtos() {}

    /**
     * 请求字段必须与服务令牌逐项一致，防止令牌被替换到其他访问者、资产、用途或变体。
     */
    public record ServiceAccessGrantRequest(
            @NotNull UUID visitorId,
            @NotNull MediaPurpose purpose,
            @NotBlank @Size(max = 40) String accessType,
            @NotBlank @Size(max = 80) String variant,
            @NotNull MediaDtos.Disposition disposition) {}
}
