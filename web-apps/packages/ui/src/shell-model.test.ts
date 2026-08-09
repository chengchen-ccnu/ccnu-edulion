import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { buildAccountTools } from "./shell-model";

describe("shared application shell contract", () => {
  it.each([
    { authenticated: false, loginHref: "/login" },
    { authenticated: true, accountLabel: "测试账号", loginHref: "/login", workspaceLabel: "学习端" },
    { authenticated: true, accountLabel: "多工作台账号", loginHref: "/login", workspaceHref: "/workspaces" }
  ])("keeps notification, workspace and account tools in a stable order", input => {
    expect(buildAccountTools(input).map(item => item.id)).toEqual(["notifications", "workspace", "account"]);
  });

  it("does not make the unfinished notification slot navigable", () => {
    const notification = buildAccountTools({ authenticated: false, loginHref: "/login" })[0];

    expect(notification).toMatchObject({ kind: "disabled", statusLabel: "建设中" });
    expect(notification).not.toHaveProperty("href");
  });

  it("uses the same account slot for anonymous login and an authenticated account menu", () => {
    const anonymous = buildAccountTools({ authenticated: false, loginHref: "/login" })[2];
    const authenticated = buildAccountTools({ authenticated: true, accountLabel: "测试账号", loginHref: "/login" })[2];

    expect(anonymous).toMatchObject({ id: "account", kind: "link", href: "/login", statusLabel: "登录" });
    expect(authenticated).toMatchObject({ id: "account", kind: "menu", statusLabel: "测试账号" });
    expect(authenticated.menuItems).toEqual([
      { id: "profile", label: "个人中心", kind: "disabled", statusLabel: "建设中" },
      { id: "preferences", label: "布局设置", kind: "disabled", statusLabel: "建设中" },
      { id: "logout", label: "退出登录", kind: "action", action: "logout" }
    ]);
    expect(authenticated.menuItems?.slice(0, 2).every(item => !("href" in item))).toBe(true);
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
