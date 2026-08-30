package com.eduplatform.iam.service;

import org.springframework.stereotype.Service;

/**
 * 汇总当前运行环境可公开的认证渠道。密码登录始终支持邮箱和手机；验证码相关入口只有在
 * 对应发送适配器已配置时才公开，生产未配置短信时因此不会暴露手机注册或手机验证码登录。
 */
@Service
public class AuthenticationChannelService {
    private final VerificationChallengeService challenges;

    public AuthenticationChannelService(VerificationChallengeService challenges) {
        this.challenges = challenges;
    }

    /** @return 前端可据此展示的邮箱、手机密码登录及验证码能力，不包含任何供应商秘密。 */
    public Capabilities current() {
        return new Capabilities(true, true, challenges.supports("EMAIL"), challenges.supports("PHONE"));
    }

    public record Capabilities(boolean emailPasswordLogin, boolean phonePasswordLogin,
            boolean emailVerification, boolean phoneVerification) {}
}
