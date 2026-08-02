package com.eduplatform.media.processing;

import java.util.List;

/** Worker 一次处理产生的派生资产列表，不包含临时签名 URL。 */
public record ProcessingResult(List<VariantResult> variants) {
    public ProcessingResult {
        variants = List.copyOf(variants);
    }

    /** 一个已上传派生对象及其私有 HLS 组件的内部元数据。 */
    public record VariantResult(String name, String objectKey, String mimeType, long size, String etag,
            List<ComponentResult> components) {
        public VariantResult {
            components = List.copyOf(components);
        }
    }

    /** HLS 播放清单引用的一个切片对象；组件名必须与清单中的相对 URI 完全一致。 */
    public record ComponentResult(String name, String objectKey, String mimeType, long size, String etag) {}
}
