package com.eduplatform.iam.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** 统一规范化手机号/邮箱；数据库唯一键和 Redis 摘要必须使用同一结果。 */
public final class IdentityNormalizer {
    private static final Pattern MAINLAND_PHONE = Pattern.compile("^1[3-9][0-9]{9}$");
    private static final Pattern MAINLAND_E164 = Pattern.compile("^\\+861[3-9][0-9]{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,180}$");

    private IdentityNormalizer() {}

    /**
     * 统一用户输入，邮箱去首尾空格并转小写；首期手机号只接受中国大陆 11 位，保存为 +86 E.164。
     * 已规范化的 +86 值也允许内部查询复用，其他国家区号和历史 account 均不接受。
     *
     * @param type EMAIL 或 PHONE
     * @param target 用户输入或数据库中的身份值
     * @return +86 E.164 手机号或小写邮箱
     */
    public static String normalize(String type, String target) {
        String value = target == null ? "" : target.trim();
        boolean valid;
        if ("PHONE".equals(type)) {
            if (MAINLAND_PHONE.matcher(value).matches()) value = "+86" + value;
            valid = MAINLAND_E164.matcher(value).matches();
        }
        else if ("EMAIL".equals(type)) { value = value.toLowerCase(Locale.ROOT); valid = EMAIL.matcher(value).matches(); }
        else valid = false;
        if (!valid) throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED",
                "请求参数不合法");
        return value;
    }

    /** @return 不泄露完整身份值的投影。 */
    public static String mask(String type, String normalized) {
        if ("PHONE".equals(type)) return normalized.substring(0, Math.min(4, normalized.length()))
                + "****" + normalized.substring(Math.max(4, normalized.length() - 3));
        int at = normalized.indexOf('@');
        String local = normalized.substring(0, at);
        return local.substring(0, 1) + "***@" + normalized.substring(at + 1);
    }
}
