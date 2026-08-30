package com.eduplatform.iam.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** T12D 账号生命周期 HTTP DTO；敏感字段只用于请求并且不得写日志。 */
public final class AccountLifecycleDtos {
    private AccountLifecycleDtos() {}

    public enum IdentityType { PHONE, EMAIL }
    public enum IdentityChangeOperation { BIND, REPLACE }
    public enum VerificationPurpose { REGISTRATION, PASSWORD_RESET, VERIFICATION_LOGIN, IDENTITY_BIND, IDENTITY_REPLACE }
    public enum AccountStatus { ACTIVE, DISABLED }

    public record ArithmeticCaptchaAnswer(@NotNull UUID captchaId,
            @NotBlank @Pattern(regexp = "^-?[0-9]{1,4}$") String answer) {}
    public record ChallengeRequest(@NotNull IdentityType identityType,
            @NotBlank @Size(max = 254) String target,
            @NotNull @Valid ArithmeticCaptchaAnswer captcha) {}
    public record ChallengeResponse(UUID challengeId, VerificationPurpose purpose,
            long expiresIn, long resendAfter, String deliveryHint) {}
    public record Agreement(@NotBlank String documentType,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,40}$") String version) {}
    public record RegisterRequest(@NotNull UUID clientRequestId, @NotNull UUID challengeId,
            @NotNull IdentityType identityType, @NotBlank @Size(max = 254) String target,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
            @NotBlank String nickname,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotNull @Size(min = 2, max = 2) List<@Valid Agreement> agreements) {}
    public record RegistrationResult(UUID userId, String nickname,
            List<String> roles, List<String> permissions, Instant createdAt) {}
    public record VerificationCodeLoginRequest(@NotNull UUID challengeId, @NotNull IdentityType identityType,
            @NotBlank @Size(max = 254) String target,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
            UUID deviceId, @Size(max = 120) String deviceName) {}
    public record ArithmeticCaptchaResponse(UUID captchaId, String imageMimeType, String imageBase64,
            long expiresIn, int refreshesRemaining) {}
    public record AuthenticationChannelCapability(IdentityType identityType, boolean passwordLoginAvailable,
            boolean verificationLoginAvailable, boolean registrationAvailable,
            boolean verificationDeliveryAvailable) {}
    public record AuthenticationChannelCapabilities(List<AuthenticationChannelCapability> channels) {}
    public record ProtocolDocumentResponse(String documentType, String version, String body,
            Instant effectiveAt, boolean current, boolean requiresReconsent) {}
    public record CurrentProtocolDocuments(List<ProtocolDocumentResponse> documents) {}
    public record VerifiedIdentity(IdentityType identityType, String maskedValue, Instant verifiedAt) {}
    public record VerifiedIdentityCollection(List<VerifiedIdentity> identities, long revision) {}
    public record IdentityChallengeRequest(@NotNull IdentityChangeOperation operation,
            @NotNull IdentityType identityType, @NotBlank @Size(max = 254) String target,
            @NotBlank @Size(min = 8, max = 128) String currentPassword,
            long expectedRevision) {}
    public record BindIdentityRequest(@NotNull UUID clientRequestId, long expectedRevision,
            @NotNull UUID challengeId, @NotNull IdentityType identityType,
            @NotBlank @Size(max = 254) String target,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
            @NotBlank @Size(min = 8, max = 128) String currentPassword) {}
    public record UnbindIdentityRequest(@NotNull UUID clientRequestId, long expectedRevision,
            @NotBlank @Size(min = 8, max = 128) String currentPassword) {}
    public record IdentityMutationResult(List<VerifiedIdentity> identities, long revision,
            long sessionVersion, String revokedSessionScope, boolean reauthenticationRequired,
            LoginResponse session) {}
    public record ResetPasswordRequest(@NotNull UUID challengeId, @NotNull IdentityType identityType,
            @NotBlank @Size(max = 254) String target,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    public record ChangePasswordRequest(@NotBlank @Size(min = 8, max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    public record AccountSession(UUID id, String deviceName, boolean current,
            Instant createdAt, Instant lastUsedAt, Instant expiresAt) {}

    public record SubmitTeacherApplicationRequest(@NotNull UUID clientRequestId, Long expectedRevision,
            @NotNull UUID qualificationMediaId, @NotBlank @Size(min = 20, max = 2000) String statement) {}
    public record TeacherApplicationResponse(UUID id, UUID userId, UUID qualificationMediaId,
            String statement, String status, String rejectionReason, long revision,
            Instant submittedAt, Instant updatedAt) {}

    public record AdminCommandRequest(@NotNull UUID clientRequestId, long expectedRevision,
            @NotBlank @Size(min = 10, max = 500) String reason) {}
    public record AdminUserResponse(UUID id, String account, String displayName,
            List<String> maskedIdentities, String status, boolean loginLocked,
            List<String> roles, List<String> permissions, long sessionVersion, long revision,
            Instant createdAt, Instant updatedAt) {}
    public record AdminUserPage(List<AdminUserResponse> items, int page, int size, long total) {}
    public record AdminCommandResult(AdminUserResponse user, boolean replayed) {}
}
