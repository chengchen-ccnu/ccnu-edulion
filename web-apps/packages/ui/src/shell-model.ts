/** 全局应用壳可渲染的导航状态；禁用状态永远不携带可跳转地址。 */
export type ShellNavigationState = "enabled" | "coming-soon" | "permission-required";

/**
 * 主导航稳定槽位模型。
 * 输入由各应用按页面规格构造；共享壳只按顺序渲染，不解释学生、教师或管理员身份。
 */
export interface ShellNavigationItem {
  id: string;
  label: string;
  state: ShellNavigationState;
  current: boolean;
  kind?: "router" | "external";
  href?: string;
  statusLabel?: string;
}

/** 账号下拉菜单项只描述可用动作或建设中状态，不携带尚未实现页面的地址。 */
export interface ShellAccountMenuItem {
  id: "profile" | "preferences" | "logout" | "notifications";
  label: string;
  kind: "action" | "link" | "disabled";
  href?: string;
  action?: "logout";
  statusLabel?: string;
  badge?: string;
}

/** 账号工具区按模型顺序渲染；工作台工具仅在有真实切换地址时提供。 */
export interface ShellAccountTool {
  id: "workspace" | "account";
  label: string;
  kind: "link" | "menu" | "status" | "disabled";
  href?: string;
  menuItems?: readonly ShellAccountMenuItem[];
  statusLabel: string;
}

export interface AccountToolInput {
  authenticated: boolean;
  accountLabel?: string;
  loginHref: string;
  /** 应用确认存在多个可用工作台后提供安全切换地址；缺省时不展示工作台工具。 */
  workspaceHref?: string;
  profileHref?: string;
  notificationsHref?: string;
  unreadCount?: number | null;
  notificationsUnavailable?: boolean;
}

/**
 * 构造可用的工作台切换和账号工具；缺少切换地址时仅返回账号，消息与未读数放在头像菜单。
 * 输入由各应用提供当前会话、安全地址及真实计数，故障用未知标识；输出不包含角色判断或只读工作台占位。
 */
export function buildAccountTools(input: AccountToolInput): ShellAccountTool[] {
  const account: ShellAccountTool = input.authenticated
    ? {
        id: "account",
        label: "账号",
        kind: "menu",
        statusLabel: input.accountLabel ?? "已登录",
        menuItems: [
          ...(input.notificationsHref ? [{ id: "notifications" as const, label: "消息", kind: "link" as const,
            href: input.notificationsHref, badge: input.notificationsUnavailable ? "暂不可用"
              : input.unreadCount == null ? "…" : input.unreadCount > 99 ? "99+" : String(input.unreadCount) }] : []),
          input.profileHref ? { id: "profile", label: "个人中心", kind: "link", href: input.profileHref }
            : { id: "profile", label: "个人中心", kind: "disabled", statusLabel: "建设中" },
          { id: "preferences", label: "布局设置", kind: "disabled", statusLabel: "建设中" },
          { id: "logout", label: "退出登录", kind: "action", action: "logout" }
        ]
      }
    : { id: "account", label: "账号", kind: "link", href: input.loginHref, statusLabel: "登录" };
  return input.workspaceHref
    ? [{ id: "workspace", label: "工作台", kind: "link", href: input.workspaceHref, statusLabel: "切换" }, account]
    : [account];
}
