package com.eduplatform.media.service;

import java.net.URI;

/** media-service 历史图片下载端口；实现必须执行主机、地址、重定向、MIME、大小和超时限制。 */
public interface ExternalImageDownloader {
    /** 下载一张有界图片；返回字节、可信响应 MIME 和最终 URI，调用方不得持久化 URI。 */
    DownloadedImage download(URI source);

    record DownloadedImage(byte[] bytes, String mimeType, URI finalUri) {}
}
