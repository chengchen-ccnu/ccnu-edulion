# T17A 账号工具区工作台显示调整

日期：2026-10-14。分支：`codex/t17c2-account-permissions-web`。保留此前课程数据维护、结果页图片与排版改动；按用户提交合并要求一并归入 [PR #90](https://github.com/chengchen-ccnu/ccnu-edulion/pull/90)，CI 与合并状态以 PR 记录为准。

## 目标与范围

用户要求处理顶栏账号旁不可点击的“工作台／学习端”，并明确由用户自行验收。此处实际是已实现的多工作台切换工具：三端 App 按 `availableWorkspaces` 的真实能力数量提供切换地址；单工作台账号原先没有地址，却仍由共享模型生成只读状态。

按用户最新要求先更新 `docs/frontend/page-specs.md`：匿名及单工作台不显示该工具，不留空白槽；多工作台仍在账号左侧显示真实切换入口。品牌副标题继续说明当前应用。此调整不涉及教师主导航中的后续工作台摘要页面。

## 实现

- `web-apps/packages/ui/src/shell-model.ts`：没有 `workspaceHref` 时只返回账号工具；有地址时按工作台、账号顺序返回。不新增角色判断或权限规则。
- 三端 `App.vue`：继续以已有能力计算可用工作台数量，只移除不再使用的只读标签参数。共享 `AppShell` 渲染结构无需修改。
- `shell-model.test.ts`：更新现有匿名、单工作台、多工作台用例的预期；账号断言改按 id 查找，保持消息、登录与头像菜单覆盖。未增加重复的样式测试。

调用流程仍为既有认证状态 → 应用提供安全地址 → `buildAccountTools` → `AppShell`。没有 API、生成类型、事件或数据库迁移变化，服务端授权保持原链路。

## 配置、数据与检查

未创建或修改数据库、表、伪账号、题目、作答或其他持久测试数据，无清理/重新生成步骤。未使用或修改 `local.env` 和加密凭据，无新增需要删除或迁移的配置。继续使用现有三端 dev 命令，例如 `pnpm.cmd --dir web-apps --filter @edu/student-web run dev`；未重启服务。

- `pnpm.cmd --dir web-apps run test`：66 文件、522 测试通过，包含现有共享壳 9 项测试及前一轮结果页回归。
- `pnpm.cmd --dir web-apps run typecheck`：API 重新生成及八个 workspace 类型检查通过；生成文件无差异。
- `pnpm.cmd --dir web-apps run build`：三端构建通过；保留已有的大于 500 kB 的 chunk 提示。
- `git diff --check`：通过。未修改 Java/Python，未运行其构建。
- 已检查业务函数中文注释、参数缺省含义与三端调用一致性；自动生成代码没有手工编辑。

按用户要求，本轮不操作浏览器、不切换账号、不执行页面或响应式人工验收；不能将代码检查写成用户验收通过。用户刷新当前页面即可查看单工作台状态，具有多个工作台能力时应仍可点击切换。无新增外部依赖，页面观感由用户最终确认。
