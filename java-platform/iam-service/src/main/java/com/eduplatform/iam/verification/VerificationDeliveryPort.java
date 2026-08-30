package com.eduplatform.iam.verification;

/**
 * 一次性验证码发送端口；业务编排只依赖身份类型和用途，不依赖具体邮件或短信供应商。
 */
public interface VerificationDeliveryPort {
    /** @return 当前运行环境是否真实支持该身份渠道。 */
    boolean supports(String identityType);

    /**
     * 发送一次性验证码。
     * @param message 已规范化目标、用途和明文验证码；实现只能用于发送，不得记录或持久化明文
     * @throws RuntimeException 供应商失败时抛出，调用方会补偿删除 Redis 挑战与冷却占位
     */
    void deliver(Message message);

    record Message(String identityType, String target, String purpose, String code) {}
}
