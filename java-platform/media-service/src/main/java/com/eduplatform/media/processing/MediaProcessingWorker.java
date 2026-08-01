package com.eduplatform.media.processing;

import com.eduplatform.media.domain.MediaAsset;

/**
 * 可替换的媒体处理 Worker 边界。实现可使用本机 FFmpeg 或云处理，但必须以稳定派生键保持幂等。
 */
public interface MediaProcessingWorker {
    /**
     * 处理一个已通过 OSS 元数据复核的私有原始资产。
     * @return 已写入对象存储的派生结果；空列表表示该用途无须派生
     * @throws RuntimeException 处理失败时抛出，由 MQ 有限重试和协调器记录终态
     */
    ProcessingResult process(MediaAsset asset);
}
