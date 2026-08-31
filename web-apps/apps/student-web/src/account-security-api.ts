/**
 * student-web 的身份与账号安全适配层。
 * 所有输入/输出类型都来自 OpenAPI 生成客户端；本文件只负责 Gateway 调用、认证重试和统一错误转换。
 * 文件上传也只申请 media-service 的短期授权并回写稳定 mediaId，不保存对象地址或访问令牌。
 */
import {
  createGatewayClient,
  gatewayRequestError
} from "@edu/api-client";
import type {
  AccountSession,
  ArithmeticCaptcha,
  ArithmeticCaptchaAnswer,
  AuthenticationChannelCapabilities,
  BindIdentityRequest,
  ChangePasswordRequest,
  CompleteMediaUploadRequest,
  CreateIdentityVerificationChallengeRequest,
  CreateMediaUploadRequest,
  CurrentProtocolDocuments,
  GatewayClient,
  IdentityMutationResult,
  IdentityType,
  MediaAsset,
  MediaUploadGrant,
  MediaUploadSession,
  RegisterStudentRequest,
  RegistrationResult,
  ReplaceIdentityRequest,
  ResetPasswordRequest,
  SubmitTeacherApplicationRequest,
  TeacherApplication,
  UnbindIdentityRequest,
  VerifiedIdentityCollection,
  VerificationChallenge
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

export interface AccountSecurityApi {
  createCaptcha(): Promise<ArithmeticCaptcha>;
  refreshCaptcha(captchaId: string): Promise<ArithmeticCaptcha>;
  capabilities(): Promise<AuthenticationChannelCapabilities>;
  protocols(): Promise<CurrentProtocolDocuments>;
  createVerificationLoginChallenge(identityType: IdentityType, target: string, captcha: ArithmeticCaptchaAnswer): Promise<VerificationChallenge>;
  registrationChallenge(identityType: IdentityType, target: string, captcha: ArithmeticCaptchaAnswer): Promise<VerificationChallenge>;
  register(body: RegisterStudentRequest): Promise<RegistrationResult>;
  passwordResetChallenge(identityType: IdentityType, target: string, captcha: ArithmeticCaptchaAnswer): Promise<VerificationChallenge>;
  resetPassword(body: ResetPasswordRequest): Promise<void>;
  changePassword(body: ChangePasswordRequest): Promise<void>;
  logoutAll(): Promise<void>;
  sessions(): Promise<AccountSession[]>;
  identities(): Promise<VerifiedIdentityCollection>;
  identityChallenge(body: CreateIdentityVerificationChallengeRequest): Promise<VerificationChallenge>;
  bindIdentity(body: BindIdentityRequest): Promise<IdentityMutationResult>;
  replaceIdentity(identityType: IdentityType, body: ReplaceIdentityRequest): Promise<IdentityMutationResult>;
  unbindIdentity(identityType: IdentityType, body: UnbindIdentityRequest): Promise<IdentityMutationResult>;
  teacherApplication(): Promise<TeacherApplication | null>;
  submitTeacherApplication(body: SubmitTeacherApplicationRequest): Promise<TeacherApplication>;
  createQualificationUpload(body: CreateMediaUploadRequest): Promise<MediaUploadSession>;
  getUpload(uploadId: string, signal?: AbortSignal): Promise<MediaUploadSession>;
  getMedia(mediaId: string, signal?: AbortSignal): Promise<MediaAsset>;
  cancelUpload(uploadId: string): Promise<void>;
  completeUpload(uploadId: string, body: CompleteMediaUploadRequest): Promise<MediaAsset>;
}

export function createAccountSecurityApi(
    client: GatewayClient = createGatewayClient(), store: AuthStore = authStore): AccountSecurityApi {
  const authorized = <T extends { response: Response }>(request: (authorization: string) => Promise<T>) =>
    store.authorizedRequest(request);

  return {
    async createCaptcha() {
      const result = await client.POST("/api/v1/auth/captchas");
      return required(result);
    },
    async refreshCaptcha(captchaId) {
      const result = await client.POST("/api/v1/auth/captchas/{captchaId}/refresh", {
        params: { path: { captchaId } }
      });
      return required(result);
    },
    async capabilities() {
      const result = await client.GET("/api/v1/auth/channel-capabilities");
      return required(result);
    },
    async protocols() {
      const result = await client.GET("/api/v1/protocols/current");
      return required(result);
    },
    async createVerificationLoginChallenge(identityType, target, captcha) {
      const result = await client.POST("/api/v1/auth/verification-login-challenges", {
        body: { identityType, target, captcha }
      });
      return required(result);
    },
    async registrationChallenge(identityType, target, captcha) {
      const result = await client.POST("/api/v1/auth/registration-challenges", {
        body: { identityType, target, captcha }
      });
      return required(result);
    },
    async register(body) {
      const result = await client.POST("/api/v1/auth/register", { body });
      return required(result);
    },
    async passwordResetChallenge(identityType, target, captcha) {
      const result = await client.POST("/api/v1/auth/password-reset-challenges", {
        body: { identityType, target, captcha }
      });
      return required(result);
    },
    async resetPassword(body) {
      const result = await client.POST("/api/v1/auth/password-reset", { body });
      expectNoContent(result);
    },
    async changePassword(body) {
      const result = await authorized(Authorization => client.POST("/api/v1/auth/password/change", {
        headers: { Authorization }, body
      }));
      expectNoContent(result);
    },
    async logoutAll() {
      const result = await authorized(Authorization => client.POST("/api/v1/auth/logout-all", {
        headers: { Authorization }
      }));
      expectNoContent(result);
    },
    async sessions() {
      const result = await authorized(Authorization => client.GET("/api/v1/users/me/sessions", {
        headers: { Authorization }
      }));
      return required(result);
    },
    async identities() {
      const result = await authorized(Authorization => client.GET("/api/v1/users/me/identities", {
        headers: { Authorization }
      }));
      return required(result);
    },
    async identityChallenge(body) {
      const result = await authorized(Authorization => client.POST("/api/v1/users/me/identity-verification-challenges", {
        headers: { Authorization }, body
      }));
      return required(result);
    },
    async bindIdentity(body) {
      const result = await authorized(Authorization => client.POST("/api/v1/users/me/identities", {
        headers: { Authorization }, body
      }));
      return required(result);
    },
    async replaceIdentity(identityType, body) {
      const result = await authorized(Authorization => client.PUT("/api/v1/users/me/identities/{identityType}", {
        headers: { Authorization }, params: { path: { identityType } }, body
      }));
      return required(result);
    },
    async unbindIdentity(identityType, body) {
      const result = await authorized(Authorization => client.DELETE("/api/v1/users/me/identities/{identityType}", {
        headers: { Authorization }, params: { path: { identityType } }, body
      }));
      return required(result);
    },
    async teacherApplication() {
      const result = await authorized(Authorization => client.GET("/api/v1/teacher-applications/me", {
        headers: { Authorization }
      }));
      if (result.response.status === 404) return null;
      return required(result);
    },
    async submitTeacherApplication(body) {
      const result = await authorized(Authorization => client.POST("/api/v1/teacher-applications", {
        headers: { Authorization }, body
      }));
      return required(result);
    },
    async createQualificationUpload(body) {
      const result = await authorized(Authorization => client.POST("/api/v1/media/uploads", {
        headers: { Authorization }, body
      }));
      return required(result);
    },
    async getUpload(uploadId, signal) {
      const result = await authorized(Authorization => client.GET("/api/v1/media/uploads/{uploadId}", {
        headers: { Authorization }, params: { path: { uploadId } }, signal
      }));
      return required(result);
    },
    async getMedia(mediaId, signal) {
      const result = await authorized(Authorization => client.GET("/api/v1/media/{mediaId}", {
        headers: { Authorization }, params: { path: { mediaId } }, signal
      }));
      return required(result);
    },
    async cancelUpload(uploadId) {
      const result = await authorized(Authorization => client.DELETE("/api/v1/media/uploads/{uploadId}", {
        headers: { Authorization }, params: { path: { uploadId } }
      }));
      expectNoContent(result);
    },
    async completeUpload(uploadId, body) {
      const result = await authorized(Authorization => client.POST("/api/v1/media/uploads/{uploadId}/complete", {
        headers: { Authorization }, params: { path: { uploadId } }, body
      }));
      return required(result);
    }
  };
}

function required<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error || !result.data) throw gatewayRequestError(result.response, result.error as never);
  return result.data;
}

function expectNoContent(result: { error?: unknown; response: Response }): void {
  if (result.error || result.response.status !== 204) {
    throw gatewayRequestError(result.response, result.error as never);
  }
}

export const accountSecurityApi = createAccountSecurityApi();
