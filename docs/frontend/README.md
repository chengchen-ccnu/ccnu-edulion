# Frontend baseline

三个应用位于 `web-apps/apps`，共享包位于 `web-apps/packages`。当前只实现应用初始化、响应式基础布局、路由壳、登录占位、设计令牌和 OpenAPI Mock 接入。

- 学生端：`http://localhost:5173`
- 教师端：`http://localhost:5174`
- 管理端：`http://localhost:5175`
- OpenAPI Mock：`http://127.0.0.1:4010`

先运行 `pnpm.cmd --dir web-apps run mock:api`，再运行对应 `dev:*` 命令。真实环境仅允许把 `VITE_GATEWAY_BASE_URL` 指向 Gateway；禁止直连领域服务或 Python Agent。
