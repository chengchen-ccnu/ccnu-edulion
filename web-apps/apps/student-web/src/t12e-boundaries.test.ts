import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/** T12E 的静态边界测试防止后续页面把秘密持久化，或在教师/管理端复制统一登录表单。 */
describe("T12E account security boundaries", () => {
  it("keeps reset challenges and access credentials out of browser persistence", () => {
    const sources = [
      "./reset-challenge.ts",
      "./views/LoginPage.vue",
      "./views/RegisterPage.vue",
      "./views/ForgotPasswordPage.vue",
      "./views/ResetPasswordPage.vue"
    ].map(path => readFileSync(new URL(path, import.meta.url), "utf8")).join("\n");

    expect(sources).not.toMatch(/\b(?:localStorage|sessionStorage)\s*\.|document\.cookie/);
    expect(sources).not.toMatch(/remember[- ]?me|记住我.*input/i);
  });

  it("keeps every account-security HTTP path in generated-client adapters", () => {
    const pageSources = [
      "./views/LoginPage.vue",
      "./views/RegisterPage.vue",
      "./views/ForgotPasswordPage.vue",
      "./views/ResetPasswordPage.vue",
      "./views/ProfileSecurityPage.vue",
      "./views/ApplyTeacherPage.vue"
    ].map(path => readFileSync(new URL(path, import.meta.url), "utf8")).join("\n");

    expect(pageSources).not.toContain("/api/v1/");
    expect(pageSources).not.toMatch(/\bfetch\s*\(/);
  });

  it("does not add a teacher or administrator login form", () => {
    const teacherRoutes = readFileSync(new URL("../../teacher-web/src/router.ts", import.meta.url), "utf8");
    const adminRoutes = readFileSync(new URL("../../admin-web/src/router.ts", import.meta.url), "utf8");

    expect(`${teacherRoutes}\n${adminRoutes}`).not.toMatch(/AuthLoginForm|LoginPage\.vue/);
  });

  it("keeps prohibited unfinished account actions out of T12E pages", () => {
    const security = readFileSync(new URL("./views/ProfileSecurityPage.vue", import.meta.url), "utf8");
    const help = readFileSync(new URL("./views/HelpPage.vue", import.meta.url), "utf8");

    expect(security).not.toMatch(/注销账号|删除账号|人工申诉.*button/);
    expect(help).not.toMatch(/提交反馈|发送反馈/);
  });
});
