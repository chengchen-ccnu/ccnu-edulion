/** 跨学生、教师、管理应用共享的登录恢复、工作台授权和安全跳转策略。 */
import { createGatewayClient } from "@edu/api-client";
import type { ApiError, CurrentUser, GatewayClient } from "@edu/api-client";
import { reactive, readonly } from "vue";
import type { Router } from "vue-router";

export { default as AccessDeniedPage } from "./AccessDeniedPage.vue";
export { default as AuthLoginForm } from "./AuthLoginForm.vue";
export { default as WorkspaceChooser } from "./WorkspaceChooser.vue";

export const LOGIN_ROUTE_NAME = "login";
export const CURRENT_USER_QUERY_KEY = ["current-user"] as const;

export type PlatformRole = CurrentUser["roles"][number];
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
  /** 只读认证快照；访问令牌不会暴露给组件或持久化存储。 */
  state: Readonly<AuthState>;
  login(account: string, password: string): Promise<boolean>;
  restore(): Promise<boolean>;
  ensureCurrentUser(): Promise<boolean>;
  logout(): Promise<void>;
  authorizedRequest<T extends { response: Response }>(request: (authorization: string) => Promise<T>): Promise<T>;
  hasRole(role: PlatformRole): boolean;
  hasPermission(permission: string): boolean;
  canAccess(role: PlatformRole): boolean;
}

export interface PlatformUrls {
  student: string;
  teacher: string;
  admin: string;
}

export interface WorkspaceLink {
  id: "student" | "teacher" | "admin";
  label: string;
  role: PlatformRole;
  permission: string;
  path: string;
  href: string;
}

export interface InternalRedirect extends WorkspaceLink {
  requestedPath: string;
}

export type PostLoginDestination =
  | { kind: "target"; target: InternalRedirect | WorkspaceLink }
  | { kind: "choose"; workspaces: WorkspaceLink[] }
  | { kind: "forbidden"; workspaces: WorkspaceLink[] };

interface WorkspaceDefinition extends Omit<WorkspaceLink, "href"> {
  accepts(path: string): boolean;
}

const runtimeEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;

const WORKSPACES: readonly WorkspaceDefinition[] = [
  {
    id: "student",
    label: "学生学习中心",
    role: "STUDENT",
    permission: "learning:access",
    path: "/",
    accepts: path => path === "/" || path === "/courses" || /^\/courses\/[0-9A-Za-z-]+$/.test(path)
  },
  {
    id: "teacher",
    label: "教师工作台",
    role: "TEACHER",
    permission: "teaching:access",
    path: "/teacher/dashboard",
    accepts: path => path === "/teacher/dashboard"
      || path === "/teacher/courses"
      || path === "/teacher/courses/new"
      || /^\/teacher\/courses\/[0-9A-Za-z-]+\/(edit|curriculum)$/.test(path)
  },
  {
    id: "admin",
    label: "管理工作台",
    role: "ADMIN",
    permission: "administration:access",
    path: "/admin/dashboard",
    accepts: path => path === "/admin/dashboard" || path === "/admin/reviews"
  }
];

export function getPlatformUrls(): PlatformUrls {
  return {
    student: normalizeOrigin(runtimeEnv?.VITE_STUDENT_WEB_URL ?? "http://localhost:5173"),
    teacher: normalizeOrigin(runtimeEnv?.VITE_TEACHER_WEB_URL ?? "http://localhost:5174"),
    admin: normalizeOrigin(runtimeEnv?.VITE_ADMIN_WEB_URL ?? "http://localhost:5175")
  };
}

export function availableWorkspaces(user: CurrentUser | null, urls: PlatformUrls = getPlatformUrls()): WorkspaceLink[] {
  if (!user) return [];
  return WORKSPACES.filter(workspace => userCanAccess(user, workspace))
    .map(workspace => toWorkspaceLink(workspace, urls));
}

/**
 * 校验登录后的站内 redirect，并映射到负责该路径的独立前端应用。
 * @returns 安全的同平台目标；外部 URL、协议相对 URL、控制字符和未知路径返回 null。
 */
export function resolveInternalRedirect(candidate: string | null | undefined,
    urls: PlatformUrls = getPlatformUrls()): InternalRedirect | null {
  if (!candidate || candidate.length > 2048 || !candidate.startsWith("/") || candidate.startsWith("//")
      || candidate.includes("\\") || /[\u0000-\u001f\u007f]/.test(candidate)) return null;
  let parsed: URL;
  try {
    parsed = new URL(candidate, "https://platform.invalid");
  } catch {
    return null;
  }
  if (parsed.origin !== "https://platform.invalid") return null;
  const workspace = WORKSPACES.find(item => item.accepts(parsed.pathname));
  if (!workspace) return null;
  const requestedPath = `${parsed.pathname}${parsed.search}${parsed.hash}`;
  return { ...toWorkspaceLink(workspace, urls), path: requestedPath, requestedPath,
    href: new URL(requestedPath, `${originFor(workspace.role, urls)}/`).toString() };
}

/** 生成统一学生端登录地址，只携带通过白名单校验的站内 redirect。 */
export function buildUnifiedLoginUrl(redirect?: string, urls: PlatformUrls = getPlatformUrls()): string {
  const loginUrl = new URL("/login", `${urls.student}/`);
  const safeRedirect = resolveInternalRedirect(redirect, urls);
  if (safeRedirect) loginUrl.searchParams.set("redirect", safeRedirect.requestedPath);
  return loginUrl.toString();
}

export function buildWorkspaceChooserUrl(urls: PlatformUrls = getPlatformUrls()): string {
  return new URL("/workspaces", `${urls.student}/`).toString();
}

/**
 * 根据实时角色与权限选择登录后的唯一目标、工作台选择页或无权限状态。
 * 指定 redirect 但无权访问时不会静默降级到其他应用，避免权限意图被掩盖。
 */
export function selectPostLoginDestination(user: CurrentUser, redirect?: string,
    urls: PlatformUrls = getPlatformUrls()): PostLoginDestination {
  const workspaces = availableWorkspaces(user, urls);
  const safeRedirect = resolveInternalRedirect(redirect, urls);
  if (safeRedirect) {
    return userCanAccess(user, safeRedirect)
      ? { kind: "target", target: safeRedirect }
      : { kind: "forbidden", workspaces };
  }
  if (workspaces.length === 1) return { kind: "target", target: workspaces[0] };
  if (workspaces.length > 1) return { kind: "choose", workspaces };
  return { kind: "forbidden", workspaces };
}

/**
 * 创建浏览器认证状态机。
 *
 * 访问令牌仅保存在闭包内存；HttpOnly 刷新 Cookie 由 IAM 设置。并发刷新共享同一 Promise，
 * 受保护请求遇到 401 最多轮换一次并重放一次，失败后清空本地身份，不做无界重试。
 */
export function createAuthStore(client: GatewayClient = createGatewayClient()): AuthStore {
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

  async function authorizedRequest<T extends { response: Response }>(
      request: (authorization: string) => Promise<T>): Promise<T> {
    if (!accessToken && !(await refreshAccessToken())) {
      throw new Error("AUTHENTICATION_REQUIRED");
    }
    let result = await request(`Bearer ${accessToken}`);
    if (result.response.status === 401) {
      if (!(await refreshAccessToken())) throw new Error("AUTHENTICATION_REQUIRED");
      result = await request(`Bearer ${accessToken}`);
    }
    return result;
  }

  function hasRole(role: PlatformRole): boolean {
    return state.currentUser?.roles.includes(role) ?? false;
  }

  function hasPermission(permission: string): boolean {
    return state.currentUser?.permissions.includes(permission) ?? false;
  }

  function canAccess(role: PlatformRole): boolean {
    const workspace = WORKSPACES.find(item => item.role === role);
    return workspace ? hasRole(role) && hasPermission(workspace.permission) : false;
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

  return { state: readonly(state) as Readonly<AuthState>, login, restore, ensureCurrentUser, logout, authorizedRequest,
    hasRole, hasPermission, canAccess };
}

export interface AuthGuardOptions {
  onLoginRedirect?: (url: string) => void;
}

/**
 * 安装路由守卫：恢复当前用户、校验目标工作台角色与权限，并安全跳转到统一登录页。
 * 未要求认证的公开路由不触发刷新请求；权限不足进入 forbidden 页面。
 */
export function installAuthGuard(router: Router, store: AuthStore, options: AuthGuardOptions = {}): void {
  router.beforeEach(async to => {
    if (!to.meta.requiresAuth) return true;
    if (!(await store.ensureCurrentUser())) {
      const loginUrl = buildUnifiedLoginUrl(to.fullPath);
      if (options.onLoginRedirect) {
        options.onLoginRedirect(loginUrl);
        return false;
      }
      const safeRedirect = resolveInternalRedirect(to.fullPath);
      return { name: LOGIN_ROUTE_NAME, query: safeRedirect ? { redirect: safeRedirect.requestedPath } : {} };
    }
    const requiredRole = to.meta.requiredRole as PlatformRole | undefined;
    if (requiredRole && !store.canAccess(requiredRole)) return { name: "forbidden" };
    return true;
  });
}

function userCanAccess(user: CurrentUser, workspace: Pick<WorkspaceDefinition, "role" | "permission">): boolean {
  return user.roles.includes(workspace.role) && user.permissions.includes(workspace.permission);
}

function toWorkspaceLink(workspace: WorkspaceDefinition, urls: PlatformUrls): WorkspaceLink {
  return { id: workspace.id, label: workspace.label, role: workspace.role, permission: workspace.permission,
    path: workspace.path, href: new URL(workspace.path, `${originFor(workspace.role, urls)}/`).toString() };
}

function originFor(role: PlatformRole, urls: PlatformUrls): string {
  if (role === "TEACHER") return urls.teacher;
  if (role === "ADMIN") return urls.admin;
  return urls.student;
}

function normalizeOrigin(value: string): string {
  try {
    return new URL(value).origin;
  } catch {
    throw new Error(`Invalid platform application URL: ${value}`);
  }
}

function networkFailure(): AuthFailure {
  return { kind: "network", message: "网络连接失败，请检查 Gateway 是否可用", fieldErrors: {} };
}
