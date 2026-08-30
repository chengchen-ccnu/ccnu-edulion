package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 生成和消费 Redis 算式验证码。
 *
 * <p>响应只包含 PNG 图像；答案经 HMAC 后写 Redis 且约两分钟过期。正确答案一次消费，
 * 错误与刷新次数分别有界，不写日志或 MySQL。</p>
 */
@Service
public class ArithmeticCaptchaService {
    private final IdentitySecurityStore store;
    private final SecurityDigests digests;
    private final IamProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ArithmeticCaptchaService(IdentitySecurityStore store, SecurityDigests digests,
            IamProperties properties) {
        this.store = store;
        this.digests = digests;
        this.properties = properties;
    }

    /** @return 新挑战 ID、PNG Base64、剩余刷新次数和有效秒数。 */
    public Captcha create() {
        return create(null);
    }

    /**
     * 原子废弃旧挑战并生成新图；旧挑战不存在或刷新次数耗尽时明确失败。
     * @param previousId 待废弃的旧挑战 ID
     * @return 新挑战，答案仍不会进入返回值
     */
    public Captcha refresh(String previousId) {
        return create(previousId);
    }

    /** 受保护操作调用本方法一次消费答案；失败不会触发密码校验或密码错误计数。 */
    public void consume(String captchaId, String answer) {
        if (captchaId == null || answer == null) throw invalid();
        boolean valid = security(() -> store.consumeCaptcha(captchaId,
                digests.digest("captcha:" + captchaId, answer.trim()), properties.captcha().maxAttempts()));
        if (!valid) throw invalid();
    }

    private Captcha create(String previousId) {
        int left = random.nextInt(9) + 1;
        int right = random.nextInt(9) + 1;
        boolean addition = random.nextBoolean();
        if (!addition && right > left) { int value = left; left = right; right = value; }
        int answer = addition ? left + right : left - right;
        String expression = left + (addition ? " + " : " - ") + right + " = ?";
        String id = UUID.randomUUID().toString();
        String digest = digests.digest("captcha:" + id, Integer.toString(answer));
        int initialRefreshes = properties.captcha().maxRefreshes();
        int remaining = initialRefreshes;
        if (previousId == null) {
            security(() -> { store.createCaptcha(id, digest, properties.captcha().ttl(), initialRefreshes); return null; });
        } else {
            IdentitySecurityStore.CaptchaRefresh result = security(() -> store.refreshCaptcha(previousId, id,
                    digest, properties.captcha().ttl(), properties.captcha().maxRefreshes()));
            if (!result.refreshed()) throw new AuthException(result.exhausted() ? HttpStatus.TOO_MANY_REQUESTS
                    : HttpStatus.NOT_FOUND, result.exhausted() ? "IAM.CAPTCHA_REFRESH_LIMITED" : "IAM.CAPTCHA_NOT_FOUND",
                    result.exhausted() ? "算式验证码刷新过于频繁，请重新获取" : "算式验证码已失效，请重新获取",
                    result.exhausted() ? 2L : null);
            remaining = result.refreshesRemaining();
        }
        return new Captcha(id, "image/png", render(expression), properties.captcha().ttl().toSeconds(), remaining);
    }

    private String render(String expression) {
        try {
            BufferedImage image = new BufferedImage(180, 56, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(new Color(248, 250, 252));
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int index = 0; index < 7; index++) {
                    graphics.setColor(new Color(148, 163, 184, 120));
                    graphics.drawLine(random.nextInt(180), random.nextInt(56), random.nextInt(180), random.nextInt(56));
                }
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
                graphics.setColor(new Color(30, 41, 59));
                graphics.drawString(expression, 18, 37);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成算式验证码图片", exception);
        }
    }

    private static AuthException invalid() {
        return new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.CAPTCHA_INVALID", "算式验证码无效或已过期");
    }

    private <T> T security(java.util.concurrent.Callable<T> action) {
        try { return action.call(); }
        catch (AuthException exception) { throw exception; }
        catch (Exception exception) { throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM.SECURITY_STATE_UNAVAILABLE", "身份安全服务暂时不可用，请稍后重试", 2L); }
    }

    public record Captcha(String captchaId, String imageMimeType, String imageBase64,
            long expiresIn, int refreshesRemaining) {}
}
