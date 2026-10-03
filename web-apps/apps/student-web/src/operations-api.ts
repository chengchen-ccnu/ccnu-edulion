import { createOperationsApi } from "@edu/api-client";
import { authStore } from "./auth";

/** 学生门户、公告及唯一消息页共用真实 Gateway 适配器，不维护重复 DTO。 */
export const operationsApi = createOperationsApi(authStore.authorizedRequest);
