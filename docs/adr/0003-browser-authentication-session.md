# ADR-0003: Browser authentication session

- 状态：Accepted
- 日期：2026-07-17

## 背景

学生、教师和管理端都通过 Gateway 访问平台。浏览器需要在降低长期凭据泄露风险的同时，支持页面刷新、短期 Access Token 过期后的无感续期以及可靠退出。

## 决策

- IAM 签发 15 分钟有效的 JWT Access Token，前端只保存在内存中，不写入 `localStorage`、`sessionStorage` 或持久化状态。
- IAM 签发 7 天有效的不可预测随机 Refresh Token。浏览器只通过名为 `EDU_REFRESH` 的 `HttpOnly`、`SameSite=Strict` Cookie 持有它；Cookie 路径限制为 `/api/v1/auth`，非 local 环境必须启用 `Secure`。
- 数据库只保存 Refresh Token 的 SHA-256 哈希。刷新时在同一事务内将旧会话标记为已轮换并创建新会话；再次使用旧 Token 返回 `409 IAM.REFRESH_REUSED` 并撤销该 Token family。
- 登录与刷新响应体只返回 Access Token 及其有效期。刷新 Cookie 通过 `Set-Cookie` 返回，前端 JavaScript 不可读取。
- 前端收到受保护请求的 `401` 时最多刷新一次，然后只重放原请求一次；刷新失败则清空内存状态并跳转登录页，避免无限循环。
- 平台只提供一个用户可见登录入口 `/login`，由兼具公开门户职责的 `student-web` 承载。`teacher-web` 和 `admin-web` 不提供登录表单，未登录时跳转统一入口并携带原目标路径。
- 登录回跳仅接受前端维护的站内路由白名单；拒绝绝对 URL、协议相对 URL、反斜杠、控制字符和未知路径，避免开放重定向。合法目标还必须同时满足对应角色与最小权限。
- 登录后以 `/api/v1/users/me` 返回的角色和权限决定去向：单角色直接进入对应工作台，多角色进入统一“选择工作台”页面；访问无权应用时显示 403 并列出有权进入的工作台。
- 三个前端应用可部署在不同 Origin。跨应用跳转会丢失内存 Access Token，目标应用必须使用同一 Gateway 域下的 HttpOnly Refresh Cookie 恢复会话，不得因此把 Access Token 改存浏览器持久化存储。
- Gateway 验证 JWT 的签名、发行者、受众和有效期，并在转发前移除所有外部传入的用户上下文头，再写入可信的 `X-User-Id`、`X-User-Roles`、`X-User-Permissions`。IAM 仍按认证主体查询当前用户，不信任浏览器提供的身份头。
- 退出撤销当前 Refresh Session、清除 Cookie。Access Token 保持短期自然过期；本轮不维护 Access Token 黑名单。

## 后果

- 页面硬刷新后需要先调用刷新接口恢复内存中的 Access Token，再查询当前用户。
- Cookie 模式要求前端请求使用同源 Gateway 或显式 `credentials: include`；生产环境需要 TLS。
- 本地开发固定 student、teacher、admin 的端口为 5173、5174、5175，并通过 `VITE_*_WEB_URL` 显式配置跨应用地址；端口被占用时启动失败，避免静默换端口后产生错误回跳或 CORS 问题。
- Access Token 在退出后最多仍可使用 15 分钟，因此高风险操作后续应增加更强的即时撤销或二次认证机制。
