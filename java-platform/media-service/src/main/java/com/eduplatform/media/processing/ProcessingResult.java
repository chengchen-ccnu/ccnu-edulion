package com.eduplatform.media.processing;

import java.util.List;

/** Worker 一次处理产生的派生资产列表，不包含临时签名 URL。 */
public record ProcessingResult(List<VariantResult> variants) {
    public ProcessingResult {
        variants = List.copyOf(variants);
    }

    /** 一个已上传派生对象的内部元数据。 */
    public record VariantResult(String name, String objectKey, String mimeType, long size, String etag) {}
}
