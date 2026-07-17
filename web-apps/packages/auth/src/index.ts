import { createGatewayClient } from "@edu/api-client";
import type { ApiError, CurrentUser, GatewayClient } from "@edu/api-client";
import { reactive, readonly } from "vue";
import type { Router } from "vue-router";

export { default as AuthLoginForm } from "./AuthLoginForm.vue";

export const LOGIN_ROUTE_NAME = "login";
export const CURRENT_USER_QUERY_KEY = ["current-user"] as const;

export type AuthStatus = "unknown" | "anonymous" | "authenticated";
export type AuthFailureKind = "validation" | "unauthorized" | "forbidden" | "rate-limited" | "network" | "unexpected";

export interface AuthFailure {
  kind: AuthFailureKind;
  message: string;
  fieldErrors: Record<string, string>;
  retryAfter?: string;
}

export interface AuthState {
  status: AuthStatus;
  loading: boolean;
  currentUser: CurrentUser | null;
  failure: AuthFailure | null;
}

export interface AuthStore {
  state: Readonly<AuthState>;
  login(account: string, password: string): Promise<boolean>;
  restore(): Promise<boolean>;
  ensureCurrentUser(): Promise<boolean>;
  logout(): Promise<void>;
  hasRequiredRole(): boolean;
}

export function createAuthStore(requiredRole: CurrentUser["roles"][number],
    client: GatewayClient = createGatewayClient()): AuthStore {
  const state = reactive<AuthState>({ status: "unknown", loading: false, currentUser: null, failure: null });
  let accessToken: string | null = null;
  let refreshInFlight: Promise<boolean> | null = null;

  async function login(account: string, password: string): Promise<boolean> {
    state.loading = true;
    state.failure = null;
    try {
      const result = await client.POST("/api/v1/auth/login", { body: { account, password } });
      if (result.error || !result.data) {
        failFromResponse(result.response.status, result.error, result.response.headers);
        return false;
      }
      accessToken = result.data.accessToken;
      return await fetchCurrentUser(false);
    } catch {
      setAnonymous(networkFailure());
      return false;
    } finally {
      state.loading = false;
    }
  }

  async function restore(): Promise<boolean> {
    if (state.status === "authenticated" && state.currentUser) return true;
    state.loading = true;
    state.failure = null;
    try {
      if (!(await refreshAccessToken())) return false;
      return await fetchCurrentUser(false);
    } finally {
      state.loading = false;
    }
  }

  async function ensureCurrentUser(): Promise<boolean> {
    state.loading = true;
    state.failure = null;
    try {
      if (!accessToken && !(await refreshAccessToken())) return false;
      return await fetchCurrentUser(true);
    } finally {
      state.loading = false;
    }
  }

  async function fetchCurrentUser(allowOneRefresh: boolean): Promise<boolean> {
    if (!accessToken) return false;
    try {
      let result = await client.GET("/api/v1/users/me", {
        headers: { Authorization: `Bearer ${accessToken}` }
      });
      if (result.response.status === 401 && allowOneRefresh && await refreshAccessToken()) {
        result = await client.GET("/api/v1/users/me", {
          headers: { Authorization: `Bearer ${accessToken}` }
        });
      }
      if (result.error || !result.data) {
        failFromResponse(result.response.status, result.error, result.response.headers);
        return false;
      }
      state.currentUser = result.data;
      state.status = "authenticated";
      if (!hasRequiredRole()) {
        state.failure = { kind: "forbidden", message: "当前账号无权进入此应用", fieldErrors: {} };
        return false;
      }
      return true;
    } catch {
      setAnonymous(networkFailure());
      return false;
    }
  }

  async function refreshAccessToken(): Promise<boolean> {
    if (refreshInFlight) return refreshInFlight;
    refreshInFlight = (async () => {
      try {
        const result = await client.POST("/api/v1/auth/refresh");
        if (result.error || !result.data) {
          failFromResponse(result.response.status, result.error, result.response.headers);
          return false;
        }
        accessToken = result.data.accessToken;
        return true;
      } catch {
        setAnonymous(networkFailure());
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
    return refreshInFlight;
  }

  async function logout(): Promise<void> {
    try {
      await client.POST("/api/v1/auth/logout");
    } finally {
      accessToken = null;
      state.status = "anonymous";
      state.currentUser = null;
      state.failure = null;
    }
  }

  function hasRequiredRole(): boolean {
    return state.currentUser?.roles.includes(requiredRole) ?? false;
  }

  function failFromResponse(status: number, error: ApiError | undefined, headers: Headers): void {
    const kind: AuthFailureKind = status === 422 ? "validation"
      : status === 401 || status === 409 ? "unauthorized"
      : status === 403 ? "forbidden"
      : status === 429 ? "rate-limited" : "unexpected";
    const fieldErrors = Object.fromEntries((error?.fieldErrors ?? []).map(item => [item.field, item.reason]));
    setAnonymous({ kind, message: error?.message ?? "请求失败，请稍后重试", fieldErrors,
      retryAfter: headers.get("Retry-After") ?? undefined });
  }

  function setAnonymous(failure: AuthFailure): void {
    accessToken = null;
    state.status = "anonymous";
    state.currentUser = null;
    state.failure = failure;
  }

  return { state: readonly(state) as Readonly<AuthState>, login, restore, ensureCurrentUser, logout, hasRequiredRole };
}

function networkFailure(): AuthFailure {
  return { kind: "network", message: "网络连接失败，请检查 Gateway 是否可用", fieldErrors: {} };
}

export function installAuthGuard(router: Router, store: AuthStore): void {
  router.beforeEach(async to => {
    if (!to.meta.requiresAuth) return true;
    if (await store.ensureCurrentUser()) return true;
    if (store.state.failure?.kind === "forbidden") return { name: "forbidden" };
    return { name: LOGIN_ROUTE_NAME, query: { redirect: to.fullPath } };
  });
}
