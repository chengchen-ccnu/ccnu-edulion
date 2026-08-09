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
  id: "profile" | "preferences" | "logout";
  label: string;
  kind: "action" | "disabled";
  action?: "logout";
  statusLabel?: string;
}

/** 账号工具区固定槽位支持链接、动作、只读状态和明确禁用四种表现。 */
export interface ShellAccountTool {
  id: "notifications" | "workspace" | "account";
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
  workspaceHref?: string;
  workspaceLabel?: string;
}

/**
 * 构造通知、工作台、账号三个固定工具槽位。
 * 调用方只提供当前会话投影和安全地址；返回模型不会包含角色判断，也不会为建设中通知生成 href。
 */
export function buildAccountTools(input: AccountToolInput): ShellAccountTool[] {
  const workspace: ShellAccountTool = input.workspaceHref
    ? { id: "workspace", label: "工作台", kind: "link", href: input.workspaceHref, statusLabel: "切换" }
    : { id: "workspace", label: "工作台", kind: "status",
        statusLabel: input.workspaceLabel ?? (input.authenticated ? "当前" : "登录后可用") };
  const account: ShellAccountTool = input.authenticated
    ? {
        id: "account",
        label: "账号",
        kind: "menu",
        statusLabel: input.accountLabel ?? "已登录",
        menuItems: [
          { id: "profile", label: "个人中心", kind: "disabled", statusLabel: "建设中" },
          { id: "preferences", label: "布局设置", kind: "disabled", statusLabel: "建设中" },
          { id: "logout", label: "退出登录", kind: "action", action: "logout" }
        ]
      }
    : { id: "account", label: "账号", kind: "link", href: input.loginHref, statusLabel: "登录" };
  return [
    { id: "notifications", label: "通知", kind: "disabled", statusLabel: "建设中" },
    workspace,
    account
  ];
}
