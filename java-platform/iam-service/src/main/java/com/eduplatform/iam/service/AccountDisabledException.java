package com.eduplatform.iam.service;

import org.springframework.http.HttpStatus;

/** 正确密码后的通用封禁提示；只附部署支持邮箱，不包含审核原因或其他身份信息。 */
public class AccountDisabledException extends AuthException {
    private final String supportEmail;
    public AccountDisabledException(String supportEmail) {
        super(HttpStatus.FORBIDDEN,"IAM.ACCOUNT_DISABLED","账号已封禁，请联系管理员");this.supportEmail=supportEmail;
    }
    public String supportEmail() { return supportEmail; }
}
