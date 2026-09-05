package com.eduplatform.learning.progress;

/** Redis 缓冲不可用或积压保护触发时使用的内部异常，HTTP 层统一映射为 503。 */
public class ProgressBufferUnavailableException extends RuntimeException {
    public ProgressBufferUnavailableException(String message) { super(message); }
    public ProgressBufferUnavailableException(String message, Throwable cause) { super(message, cause); }
}
