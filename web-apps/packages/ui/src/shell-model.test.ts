import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { buildAccountTools } from "./shell-model";

describe("shared application shell contract", () => {
  it.each([
    [{ authenticated: false, loginHref: "/login" }, ["account"]],
    [{ authenticated: true, accountLabel: "测试账号", loginHref: "/login" }, ["account"]],
    [{ authenticated: true, accountLabel: "多工作台账号", loginHref: "/login", workspaceHref: "/workspaces" }, ["workspace", "account"]]
  ] as const)("shows workspace switching only when it has an actionable target", (input, expected) => {
    expect(buildAccountTools(input).map(item => item.id)).toEqual(expected);
  });

  it("places messages only in the authenticated avatar menu with unknown counts preserved", () => {
    const account = buildAccountTools({ authenticated: true, loginHref: "/login", notificationsHref: "http://localhost:5173/notifications", unreadCount: null }).find(item => item.id === "account")!;
    expect(account.menuItems?.[0]).toMatchObject({ id: "notifications", kind: "link", href: "http://localhost:5173/notifications", badge: "…" });
  });

  it("uses the same account slot for anonymous login and an authenticated account menu", () => {
    const anonymous = buildAccountTools({ authenticated: false, loginHref: "/login" }).find(item => item.id === "account")!;
    const authenticated = buildAccountTools({ authenticated: true, accountLabel: "测试账号", loginHref: "/login" }).find(item => item.id === "account")!;

    expect(anonymous).toMatchObject({ id: "account", kind: "link", href: "/login", statusLabel: "登录" });
    expect(authenticated).toMatchObject({ id: "account", kind: "menu", statusLabel: "测试账号" });
    expect(authenticated.menuItems).toEqual([
      { id: "profile", label: "个人中心", kind: "disabled", statusLabel: "建设中" },
      { id: "preferences", label: "布局设置", kind: "disabled", statusLabel: "建设中" },
      { id: "logout", label: "退出登录", kind: "action", action: "logout" }
    ]);
    expect(authenticated.menuItems?.slice(0, 2).every(item => !("href" in item))).toBe(true);
  });

  it("enables the existing profile menu item without changing account-tool order", () => {
    const account = buildAccountTools({ authenticated: true, accountLabel: "测试账号",
      loginHref: "/login", profileHref: "/profile" }).find(item => item.id === "account")!;

    expect(account.menuItems?.[0]).toEqual({ id: "profile", label: "个人中心", kind: "link", href: "/profile" });
    expect(account.menuItems?.map(item => item.id)).toEqual(["profile", "preferences", "logout"]);
  });

  it("keeps the account popover outside the scroll container", () => {
    const styles = readFileSync(new URL("./shell.css", import.meta.url), "utf8");

    expect(styles).toContain(".app-shell__account-tools { min-width: 0; overflow: visible; }");
    expect(styles).not.toMatch(/\.app-shell__main-navigation,\s*\.app-shell__account-tools\s*\{[^}]*overflow-x/);
  });

  it("owns only fixed brand and structural model rendering, without role-specific wording", () => {
    const source = readFileSync(new URL("./AppShell.vue", import.meta.url), "utf8");

    expect(source).toContain("<h1>Edu Lion</h1>");
    expect(source).not.toMatch(/STUDENT|TEACHER|ADMIN|学生学习中心|教师工作台|平台运营中心/);
    expect(source).toContain("aria-label=\"主导航\"");
    expect(source).toContain("aria-label=\"账号工具\"");
  });

  it("renders disabled navigation slots as non-focusable spans instead of links", () => {
    const source = readFileSync(new URL("./ShellNavigation.vue", import.meta.url), "utf8");

    expect(source).toContain("aria-disabled=\"true\"");
    expect(source).toContain("v-else\n        class=\"shell-navigation__disabled\"");
    expect(source).not.toContain("tabindex");
  });
});
