import { createGatewayClient, gatewayRequestError } from "@edu/api-client";
import type {
  AuthorizeMediaPartRequest,
  CompleteMediaUploadRequest,
  CreateMediaUploadRequest,
  GatewayClient,
  MediaAsset,
  MediaUploadGrant,
  MediaUploadSession
} from "@edu/api-client";
import type { AuthStore } from "@edu/auth";

import { authStore } from "./auth";

/** 教师媒体 API 边界；只传输 OpenAPI JSON 元数据，文件字节由浏览器使用短期授权直传 OSS。 */
export interface TeacherMediaApi {
  create(body: CreateMediaUploadRequest, signal?: AbortSignal): Promise<MediaUploadSession>;
  getUpload(uploadId: string, signal?: AbortSignal): Promise<MediaUploadSession>;
  cancel(uploadId: string): Promise<void>;
  authorizePart(uploadId: string, body: AuthorizeMediaPartRequest, signal?: AbortSignal): Promise<MediaUploadGrant>;
  complete(uploadId: string, body: CompleteMediaUploadRequest, signal?: AbortSignal): Promise<MediaAsset>;
  getMedia(mediaId: string, signal?: AbortSignal): Promise<MediaAsset>;
}

/** 构造经 Gateway 和统一认证状态机调用的媒体适配器；401 最多由 AuthStore 刷新并重放一次。 */
export function createTeacherMediaApi(
    store: AuthStore = authStore,
    client: GatewayClient = createGatewayClient()): TeacherMediaApi {
  const authorized = <T extends { response: Response }>(request: (authorization: string) => Promise<T>) =>
    store.authorizedRequest(request);
  return {
    async create(body, signal) {
      const result = await authorized(Authorization => client.POST("/api/v1/media/uploads", {
        headers: { Authorization }, body, signal
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async getUpload(uploadId, signal) {
      const result = await authorized(Authorization => client.GET("/api/v1/media/uploads/{uploadId}", {
        headers: { Authorization }, params: { path: { uploadId } }, signal
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async cancel(uploadId) {
      const result = await authorized(Authorization => client.DELETE("/api/v1/media/uploads/{uploadId}", {
        headers: { Authorization }, params: { path: { uploadId } }
      }));
      if (result.response.status !== 204) throw gatewayRequestError(result.response, result.error);
    },
    async authorizePart(uploadId, body, signal) {
      const result = await authorized(Authorization => client.POST("/api/v1/media/uploads/{uploadId}/parts/authorize", {
        headers: { Authorization }, params: { path: { uploadId } }, body, signal
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async complete(uploadId, body, signal) {
      const result = await authorized(Authorization => client.POST("/api/v1/media/uploads/{uploadId}/complete", {
        headers: { Authorization }, params: { path: { uploadId } }, body, signal
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    },
    async getMedia(mediaId, signal) {
      const result = await authorized(Authorization => client.GET("/api/v1/media/{mediaId}", {
        headers: { Authorization }, params: { path: { mediaId } }, signal
      }));
      if (!result.data) throw gatewayRequestError(result.response, result.error);
      return result.data;
    }
  };
}

export const teacherMediaApi = createTeacherMediaApi();
