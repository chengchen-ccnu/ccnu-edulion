/** 跨学生、教师、管理应用共享的登录恢复、工作台授权和安全跳转策略。 */
import { createGatewayClient } from "@edu/api-client";
import type { ApiError, ArithmeticCaptchaAnswer, CurrentUser, GatewayClient, IdentityType, LoginResponse } from "@edu/api-client";
import { reactive, readonly } from "vue";
import type { Router } from "vue-router";

export { default as AccessDeniedPage } from "./AccessDeniedPage.vue";
export { default as AuthLoginForm } from "./AuthLoginForm.vue";
export { default as WorkspaceChooser } from "./WorkspaceChooser.vue";
export { useNotificationCounts, invalidateNotificationCounts } from "./notification-counts";

export const LOGIN_ROUTE_NAME = "login";
export const CURRENT_USER_QUERY_KEY = ["current-user"] as const;

export type PlatformRole = CurrentUser["roles"][number];
export type AuthStatus = "unknown" | "anonymous" | "authenticated";
export type AuthFailureKind = "validation" | "unauthorized" | "forbidden" | "rate-limited" | "network" | "unexpected";

export interface AuthFailure {
  kind: AuthFailureKind;
  code?: string;
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
  /** 使用已验证邮箱或中国大陆手机号登录；昵称和内部 account 都不会发送为登录标识。 */
  login(identity: string, password: string, captcha?: ArithmeticCaptchaAnswer): Promise<boolean>;
  /** 消费服务端一次性验证码登录；未知身份不会注册账号。 */
  loginWithVerificationCode(challengeId: string, identityType: IdentityType, target: string,
    verificationCode: string): Promise<boolean>;
  /** 接受身份绑定后服务端签发的新访问令牌，不把令牌暴露给页面组件。 */
  acceptLoginResponse(response: LoginResponse): Promise<boolean>;
  restore(): Promise<boolean>;
  /** 重新读取真实账号能力；离页取消不替换快照，网络/服务故障不误报注销。 */
  ensureCurrentUser(signal?: AbortSignal): Promise<boolean>;
  /** 始终清理本地内存；返回值说明服务端是否确认撤销当前 Refresh Session。 */
  logout(): Promise<boolean>;
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

/** 通过安全白名单解析出的公共目标或受保护工作台目标。 */
export type InternalRedirect = PublicInternalRedirect | WorkspaceInternalRedirect | AccountInternalRedirect;

/** 登录账号公共工具页不绑定学习或教学能力，管理员消息也进入同一学生端物理页面。 */
export interface AccountInternalRedirect {
  access: "account";
  id: "student";
  label: "账号";
  path: string;
  requestedPath: string;
  href: string;
}

/** 无需任何角色或权限即可到达的站内 redirect。 */
export interface PublicInternalRedirect {
  access: "public";
  id: "student";
  label: "课程中心";
  path: string;
  href: string;
  requestedPath: string;
}

/** 只有匹配角色和最小权限才能到达的工作台 redirect。 */
export interface WorkspaceInternalRedirect extends WorkspaceLink {
  access: "workspace";
  requestedPath: string;
}

export type PostLoginDestination =
  | { kind: "target"; target: InternalRedirect | WorkspaceLink }
  | { kind: "choose"; workspaces: WorkspaceLink[] }
  | { kind: "forbidden"; workspaces: WorkspaceLink[] };

interface WorkspaceDefinition extends Omit<WorkspaceLink, "href"> {
  accepts(path: string): boolean;
}

interface PublicTargetDefinition {
  id: "student";
  label: "课程中心";
  path: "/courses";
  accepts(path: string): boolean;
}

const runtimeEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;

const PUBLIC_TARGETS: readonly PublicTargetDefinition[] = [
  {
    id: "student",
    label: "课程中心",
    path: "/courses",
    accepts: path => path === "/" || path === "/courses" || /^\/courses\/[0-9A-Za-z-]+$/.test(path)
  }
];

const WORKSPACES: readonly WorkspaceDefinition[] = [
  {
    id: "student",
    label: "学生学习中心",
    role: "STUDENT",
    permission: "learning:access",
    path: "/my-learning",
    accepts: path => path === "/my-learning" || path === "/practice" || path === "/wrong-questions" || path === "/ai"
      || /^\/ai\/conversations\/[0-9A-Za-z-]+$/.test(path)
      || /^\/learn\/[0-9A-Za-z-]+\/[0-9A-Za-z-]+$/.test(path)
      || /^\/practice\/[0-9A-Za-z-]+$/.test(path)
      || /^\/attempt\/[0-9A-Za-z-]+\/result$/.test(path)
  },
  {
    id: "teacher",
    label: "教师工作台",
    role: "TEACHER",
    permission: "teaching:access",
    path: "/teacher/courses",
    accepts: path => path === "/teacher/dashboard"
      || path === "/teacher/courses"
      || path === "/teacher/courses/new"
      || path === "/teacher/assessments"
      || /^\/teacher\/courses\/[0-9A-Za-z-]+\/(edit|curriculum|announcements)$/.test(path)
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

/**
 * 使用可选的平台 URL 配置构造 student-web 承载的公共课程中心地址。
 * @returns 可直接用于跨应用导航的绝对 `/courses` URL。
 */
export function buildCourseCenterUrl(urls: PlatformUrls = getPlatformUrls()): string {
  return new URL("/courses", `${urls.student}/`).toString();
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
  const requestedPath = `${parsed.pathname}${parsed.search}${parsed.hash}`;
  if (["/notifications", "/apply-teacher", "/profile", "/profile/security"].includes(parsed.pathname)) {
    return { access: "account", id: "student", label: "账号", path: requestedPath, requestedPath,
      href: new URL(requestedPath, `${urls.student}/`).toString() };
  }
  const publicTarget = PUBLIC_TARGETS.find(item => item.accepts(parsed.pathname));
  if (publicTarget) {
    return { access: "public", id: publicTarget.id, label: publicTarget.label, path: requestedPath, requestedPath,
      href: new URL(requestedPath, `${urls.student}/`).toString() };
  }
  const workspace = WORKSPACES.find(item => item.accepts(parsed.pathname));
  if (!workspace) return null;
  return { ...toWorkspaceLink(workspace, urls), access: "workspace", path: requestedPath, requestedPath,
    href: new URL(requestedPath, `${originFor(workspace.role, urls)}/`).toString() };
}

/** 生成由 student-web 物理承载的统一登录地址，只携带通过白名单校验的站内 redirect。 */
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
    return safeRedirect.access === "public" || safeRedirect.access === "account" || userCanAccess(user, safeRedirect)
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

  async function login(identity: string, password: string, captcha?: ArithmeticCaptchaAnswer): Promise<boolean> {
    state.loading = true;
    state.failure = null;
    try {
      const result = await client.POST("/api/v1/auth/login", {
        body: captcha ? { identity, password, captcha } : { identity, password }
      });
      if (result.error || !result.data) {
        failFromResponse(result.response.status, result.error, result.response.headers);
        return false;
      }
      return await acceptLoginResponse(result.data);
    } catch {
      setAnonymous(networkFailure());
      return false;
    } finally {
      state.loading = false;
    }
  }

  async function loginWithVerificationCode(challengeId: string, identityType: IdentityType, target: string,
      verificationCode: string): Promise<boolean> {
    state.loading = true;
    state.failure = null;
    try {
      const result = await client.POST("/api/v1/auth/verification-login", {
        body: { challengeId, identityType, target, verificationCode }
      });
      if (result.error || !result.data) {
        failFromResponse(result.response.status, result.error, result.response.headers);
        return false;
      }
      return await acceptLoginResponse(result.data);
    } catch {
      setAnonymous(networkFailure());
      return false;
    } finally {
      state.loading = false;
    }
  }

  async function acceptLoginResponse(response: LoginResponse): Promise<boolean> {
    accessToken = response.accessToken;
    return await fetchCurrentUser(false);
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

  async function ensureCurrentUser(signal?: AbortSignal): Promise<boolean> {
    state.loading = true;
    state.failure = null;
    try {
      if (!accessToken && !(await refreshAccessToken())) return false;
      if (signal?.aborted) return false;
      return await fetchCurrentUser(true, signal);
    } finally {
      state.loading = false;
    }
  }

  /** 查询当前权限，401最多刷新一次；取消、退出或换令牌后丢弃旧响应，避免恢复上一账号。 */
  async function fetchCurrentUser(allowOneRefresh: boolean, signal?: AbortSignal): Promise<boolean> {
    if (!accessToken) return false;
    let requestToken = accessToken;
    try {
      let result = await client.GET("/api/v1/users/me", {
        headers: { Authorization: `Bearer ${requestToken}` }, signal
      });
      if (signal?.aborted || accessToken !== requestToken) return false;
      if (result.response.status === 401 && allowOneRefresh && await refreshAccessToken()) {
        requestToken = accessToken!;
        result = await client.GET("/api/v1/users/me", {
          headers: { Authorization: `Bearer ${requestToken}` }, signal
        });
      }
      if (signal?.aborted || accessToken !== requestToken) return false;
      if (result.error || !result.data) {
        failFromResponse(result.response.status, result.error, result.response.headers);
        return false;
      }
      state.currentUser = result.data;
      state.status = "authenticated";
      return true;
    } catch {
      if (!signal?.aborted) state.failure = networkFailure();
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
        state.failure = networkFailure();
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
    return refreshInFlight;
  }

  async function logout(): Promise<boolean> {
    let confirmed = false;
    try {
      const result = await client.POST("/api/v1/auth/logout");
      confirmed = !result.error && result.response.status === 204;
    } catch {
      confirmed = false;
    } finally {
      accessToken = null;
      state.status = "anonymous";
      state.currentUser = null;
      state.failure = null;
    }
    return confirmed;
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
    const failure = { kind, code: error?.code, message: error?.message ?? "请求失败，请稍后重试", fieldErrors,
      retryAfter: headers.get("Retry-After") ?? undefined };
    if (status >= 500 || status === 429) state.failure = failure;
    else setAnonymous(failure);
  }

  function setAnonymous(failure: AuthFailure): void {
    accessToken = null;
    state.status = "anonymous";
    state.currentUser = null;
    state.failure = failure;
  }

  return { state: readonly(state) as Readonly<AuthState>, login, loginWithVerificationCode, acceptLoginResponse,
    restore, ensureCurrentUser, logout, authorizedRequest, hasRole, hasPermission, canAccess };
}

export interface AuthGuardOptions {
  onLoginRedirect?: (url: string) => void;
}

/**
 * 安装路由守卫：恢复当前用户、校验目标页面声明的能力或旧工作台角色，并安全跳转到统一登录页。
 * 标记 restoreSession 的公开路由只在后台尝试恢复一次会话，不等待结果也不阻断匿名访问；
 * 受保护路由在没有已认证快照时必须完成身份恢复，权限不足进入 forbidden 页面。
 * 应用内导航复用内存中的用户与能力，避免临时断网被误判为退出登录并触发整页登录跳转；
 * 后续业务请求如遇真实 401，仍由 authorizedRequest 负责刷新或清除失效会话。
 */
export function installAuthGuard(router: Router, store: AuthStore, options: AuthGuardOptions = {}): void {
  let publicRestoreStarted = false;
  router.beforeEach(async to => {
    if (!to.meta.requiresAuth) {
      if (to.meta.restoreSession && store.state.status === "unknown" && !publicRestoreStarted) {
        publicRestoreStarted = true;
        void store.restore();
      }
      return true;
    }
    const hasAuthenticatedSnapshot = store.state.status === "authenticated" && store.state.currentUser !== null;
    if (!hasAuthenticatedSnapshot && !(await store.ensureCurrentUser())) {
      const loginUrl = buildUnifiedLoginUrl(to.fullPath);
      if (options.onLoginRedirect) {
        options.onLoginRedirect(loginUrl);
        return false;
      }
      const safeRedirect = resolveInternalRedirect(to.fullPath);
      return { name: LOGIN_ROUTE_NAME, query: safeRedirect ? { redirect: safeRedirect.requestedPath } : {} };
    }
    const requiredPermission = to.meta.requiredPermission as string | undefined;
    if (requiredPermission && !store.hasPermission(requiredPermission)) return { name: "forbidden" };
    const requiredRole = to.meta.requiredRole as PlatformRole | undefined;
    if (requiredRole && !store.canAccess(requiredRole)) return { name: "forbidden" };
    return true;
  });
}

function userCanAccess(user: CurrentUser, workspace: Pick<WorkspaceDefinition, "permission">): boolean {
  return user.permissions.includes(workspace.permission);
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
