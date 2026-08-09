# 前端工程与页面规格

三个应用位于 `web-apps/apps`，共享技术包位于 `web-apps/packages`。路由、页面职责、访问能力、状态要求和后续 Txx 归属以 [`page-specs.md`](./page-specs.md) 为唯一事实来源；接口字段以 [`../api/openapi.yaml`](../api/openapi.yaml) 为唯一事实来源。

截至 2026-08-09，`main` 已包含统一登录、工作台选择、公共课程列表/详情、教师课程创建/编辑/目录编排，以及课程媒体绑定和服务端播放授权。学生首页、独立课程学习页、我的学习、题库/测评、AI 答疑、运营首页和完整管理端仍按 page specs 分阶段交付。存在路由不代表对应产品页面已完成，`PlaceholderPage` 不能作为业务验收证据。

- 学生端：`http://localhost:5173`
- 教师端：`http://localhost:5174`
- 管理端：`http://localhost:5175`
- OpenAPI Mock：`http://127.0.0.1:4010`

先运行 `pnpm.cmd --dir web-apps run mock:api`，再运行对应 `dev:*` 命令。真实环境仅允许把 `VITE_GATEWAY_BASE_URL` 指向 Gateway；禁止直连领域服务或 Python Agent。

所有前端任务开始前都要检查：

1. 当前路由是否已在 `page-specs.md` 分配给本任务；
2. 页面使用的字段是否来自当前 OpenAPI 生成客户端；
3. 未实现能力是否被隐藏或明确标记，而不是用假数据/无效按钮占位；
4. 课程详情、课程学习、我的学习、教学管理和管理端是否仍保持各自职责；
5. loading、empty、partial-error、权限、离线、深链接、响应式和资源清理是否有验证。
