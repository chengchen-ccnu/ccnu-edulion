package com.eduplatform.media.service;

import com.eduplatform.media.config.MediaProperties;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 受限 HTTPS 图片下载器：每次请求和重定向都重新校验白名单与 DNS 地址，防止 SSRF。
 * 响应以固定缓冲区流式读取并在 maxBytes 后立即失败；URL 不进入日志或异常消息。
 */
@Component
public class HttpExternalImageDownloader implements ExternalImageDownloader {
    private static final Set<String> MIME = Set.of("image/jpeg", "image/png", "image/webp");
    private final MediaProperties.ExternalImageImport properties;
    private final HttpClient client;

    public HttpExternalImageDownloader(MediaProperties properties) {
        this.properties = properties.externalImageImport();
        Duration timeout = this.properties == null || this.properties.requestTimeout() == null
                ? Duration.ofSeconds(10) : this.properties.requestTimeout();
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 下载并验证图片；任何网络或策略失败只返回安全业务错误，不包含外部地址。 */
    @Override
    public DownloadedImage download(URI source) {
        if (properties == null || !properties.enabled()) throw disabled();
        URI current = source;
        try {
            for (int redirects = 0; redirects <= properties.maxRedirects(); redirects++) {
                validate(current);
                HttpRequest request = HttpRequest.newBuilder(current).timeout(properties.requestTimeout())
                        .header("Accept", "image/jpeg,image/png,image/webp").GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    response.body().close();
                    String location = response.headers().firstValue("Location").orElseThrow();
                    current = current.resolve(location); continue;
                }
                if (status < 200 || status >= 300) { response.body().close(); throw invalid(); }
                String mime = response.headers().firstValue("Content-Type").orElse("")
                        .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!MIME.contains(mime)) { response.body().close(); throw invalid(); }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > properties.maxBytes()) { response.body().close(); throw invalid(); }
                return new DownloadedImage(readLimited(response.body(), properties.maxBytes()), mime, current);
            }
            throw invalid();
        } catch (MediaException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private void validate(URI value) throws Exception {
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getUserInfo() != null || value.getHost() == null
                || value.getPort() != -1 && value.getPort() != 443) throw invalid();
        String host = value.getHost().toLowerCase(Locale.ROOT);
        if (properties.allowedHosts() == null || properties.allowedHosts().stream()
                .map(item -> item.toLowerCase(Locale.ROOT)).noneMatch(host::equals)) throw invalid();
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) throw invalid();
        }
    }

    private static byte[] readLimited(InputStream input, long maxBytes) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; long total = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                total += count; if (total > maxBytes) throw invalid(); output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static MediaException disabled() { return new MediaException(HttpStatus.UNPROCESSABLE_CONTENT,
            "MEDIA.EXTERNAL_IMAGE_IMPORT_DISABLED", "历史图片转存未启用"); }
    private static MediaException invalid() { return new MediaException(HttpStatus.UNPROCESSABLE_CONTENT,
            "MEDIA.EXTERNAL_IMAGE_INVALID", "外部图片不符合安全转存策略"); }
    private static MediaException unavailable() { return new MediaException(HttpStatus.SERVICE_UNAVAILABLE,
            "MEDIA.EXTERNAL_IMAGE_UNAVAILABLE", "外部图片暂时无法获取，请稍后重试"); }
}
