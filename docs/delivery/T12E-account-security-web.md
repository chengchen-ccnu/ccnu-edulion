# T12E unified registration and account security frontend delivery

## 目标与边界

T12E 在 `student-web` 的统一身份壳内交付密码/验证码登录、默认邮箱注册、找回与重置密码、协议弹窗及公共协议深链、静态帮助、个人中心、账号安全、设备会话管理和教师申请页面。教师端与管理端继续跳转学生端统一登录，没有复制登录表单；前端所有请求和类型都消费 OpenAPI 0.18.0 生成客户端，没有手写 REST DTO 或平行 Mock Schema。

手机入口严格读取服务端渠道能力：当前真实环境没有短信供应商时，手机注册、手机找回和手机验证码登录均明确禁用。任务没有实现真实短信供应商、反馈提交、“记住我”、账号注销、人工申诉、教师审核决定或 T17A 完整个人资料；教师申请只提交事实，不授予教学能力。

## 页面与调用流程

- `/login`：同一表单承载密码和一次性验证码登录；密码失败达到服务端阈值时按稳定错误码显示算式 CAPTCHA。Access Token 只保存在认证状态机闭包内存，Refresh Token 仍只由 HttpOnly Cookie 持有。
- `/register`：默认邮箱注册，展示服务端当前 TERMS/PRIVACY 版本和正文弹窗；注册挑战先消费算式 CAPTCHA，提交成功后尝试自动登录，失败时安全回到登录页。
- `/forgot-password` 与 `/reset-password`：挑战 ID、目标和身份类型只在当前标签页的模块内存中传递，不写 URL、`localStorage` 或 `sessionStorage`；刷新重置页会明确要求重新发起找回。
- `/terms`、`/privacy`、`/help`：协议正文和版本来自 Gateway，帮助页是静态说明且没有反馈提交按钮。
- `/profile`：只展示当前用户的最小账号摘要、教师申请状态和安全入口，不承担头像或完整资料维护。
- `/profile/security`：读取脱敏身份及 revision，支持绑定/换绑/解绑、修改密码、设备会话查询和全部设备退出；高风险操作继续由 IAM 校验当前密码、验证码、revision 和会话版本。
- `/apply-teacher`：无申请或已拒绝时可提交说明与 `TEACHER_QUALIFICATION` 媒体；已提交、审核中、已批准或已有 `teaching:access` 时只读展示，不提供审核决定。

共享 `@edu/auth` 继续是三端唯一认证状态机，新增验证码登录、登录 CAPTCHA 和服务端退出确认语义。`@edu/ui` 只增加由调用方提供的个人中心链接，不内置角色判断。页面数据访问集中在 `student-web/src/account-security-api.ts`，组件不直接调用 `fetch` 或维护 DTO。

## 消费的 API

本任务没有修改 OpenAPI，消费 0.18.0 已生成路径：

- `POST /api/v1/auth/login`、`POST /api/v1/auth/verification-login`
- `POST /api/v1/auth/captchas`、`POST /api/v1/auth/captchas/{captchaId}/refresh`
- `GET /api/v1/auth/channel-capabilities`
- `POST /api/v1/auth/registration-challenges`、`POST /api/v1/auth/register`
- `POST /api/v1/auth/password-reset-challenges`、`POST /api/v1/auth/password-reset`
- `POST /api/v1/auth/verification-login-challenges`
- `GET /api/v1/protocols/current`
- `GET /api/v1/users/me`、`GET /api/v1/users/me/identities`
- `POST /api/v1/users/me/identity-verification-challenges`
- `POST /api/v1/users/me/identities`、`PUT/DELETE /api/v1/users/me/identities/{identityType}`
- `PUT /api/v1/users/me/password`
- `GET /api/v1/users/me/sessions`、`POST /api/v1/users/me/sessions/logout-all`
- `GET/POST /api/v1/teacher-applications/me`
- 教师资质上传使用既有 `/api/v1/media/uploads`、分片授权、完成上传和临时访问授权契约。

## 数据库、迁移与临时数据

本任务是纯前端交付，没有新增或修改数据库迁移、业务表、事件 Schema、RabbitMQ 消息、OSS 对象或 Seed。浏览器验收只使用明确的专用 `edu_platform_iam_dev`：临时创建一个邮箱学习账号及其 `user_identity`、`registration_request`、两条 `protocol_agreement`、`user_role`、`refresh_session`、登录/安全审计事实；为无当前协议的专用 Schema 临时创建两条固定 ID 的 TERMS/PRIVACY 测试正文。

验收结束后按临时 user ID 逆外键顺序删除教师申请命令/申请、身份变更命令、管理员命令、安全审计、协议同意、注册幂等事实、身份、登录审计、全部 Refresh Session、角色关联和用户；两条协议正文按固定 ID 删除。Redis 按本轮 CAPTCHA ID 和临时 user ID 删除并核验不存在，短 TTL Gateway 登录限流键已自然过期；既有 Seed 用户、角色、权限、迁移表和预置会话键均未删除。MySQL 清理后用户、Refresh Session、身份和临时协议计数均为 0。重新验收时应重新执行页面注册流程，不提交固定测试密码或真实邮箱到仓库。

## 环境与启动

本地秘密只通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入；真实 QQ SMTP 使用当前用户 DPAPI 加密凭据，邮箱地址、密码、授权码、验证码和临时账号密码均未写入仓库或文档。验收时以 `local` profile 启动 IAM/Gateway，Gateway 与 IAM 必须读取同一 Redis logical database 才能验证 `sessionVersion`。

普通开发可执行：

```powershell
pnpm.cmd --dir web-apps --filter @edu/student-web run dev
```

本机浏览器已有同名 `EDU_REFRESH` Cookie，且跨端口验收浏览器不接收新的 HttpOnly Cookie；因此刷新恢复专项使用临时 `t12e.localhost` 同源反向代理隔离 Cookie 命名空间。代理、Vite、Gateway 和 IAM 均已在验收结束后停止，该启动差异没有形成仓库代码或配置变更。

## 自动化与构建结果

实际完成：

- `pnpm.cmd --dir web-apps run check:api`：通过，OpenAPI 生成客户端无漂移。
- `pnpm.cmd --dir web-apps run typecheck`：8 个 workspace 全部通过。
- `pnpm.cmd --dir web-apps run test`：31 个 Vitest 文件、212 项测试全部通过。
- `pnpm.cmd --dir web-apps run build`：admin、student、teacher 三端生产构建全部通过；仅有既有 chunk size 提示。
- `mvn.cmd --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl gateway-service -am -DskipTests package`：Gateway 及依赖模块构建成功，用于真实浏览器验收当前代码。
- `git diff --check`：通过。

新增专项自动化覆盖生成客户端边界、登录 CAPTCHA/验证码登录、注册与渠道能力、重置挑战内存边界、身份 revision、会话/教师申请适配、路由访问声明、无 Web Storage/Cookie 读取、页面不直调 API、教师/管理端不复制登录表单，以及共享账号工具模型。

## 真实 QQ SMTP 浏览器验收

2026-08-31 在真实 IAM、Gateway、MySQL、Redis 和 QQ SMTP 下完成：

1. 浏览器读取渠道能力和服务端协议；手机能力关闭、邮箱为默认注册方式，协议弹窗支持 Escape 关闭，`/terms`、`/privacy` 深链接及刷新可用。
2. 对受控 QQ 邮箱完成算式 CAPTCHA、真实注册邮件收取、一次性验证码提交和临时账号创建；邮件发件账号仅作为 SMTP 发送方，没有被当作测试收件账号。
3. 临时账号密码登录成功；经独立同源验收域完成 HttpOnly Refresh Cookie 轮换、`/profile` 深链接刷新恢复、脱敏邮箱、revision、设备会话当前标记和多设备列表验证。
4. `/apply-teacher` 在无申请时只显示资质上传与申请说明，未上传文件、未提交教师申请、未执行审核决定。
5. `/forgot-password`、受控 `/reset-password` 刷新缺失挑战状态、验证码登录表单、静态 `/help`、404、协议弹窗键盘操作及 390×844 响应式布局均通过。找回和验证码登录没有再次发送邮件或改变账号密码，避免产生额外高风险状态。
6. 临时账号、协议、会话和 Redis Key 已按上一节精确清理并复核为零。

学习页请求出现的 500 来自未启动的 `learning-service`，不属于 T12E 身份页面；账号与安全 API 均通过真实 Gateway。

## 人工演示

1. 打开 `/register`，确认邮箱默认、手机禁用原因、两份当前协议及键盘弹窗；完成 CAPTCHA、邮件验证码和注册。
2. 使用密码登录，打开账号菜单进入 `/profile`，刷新页面确认 Refresh Cookie 恢复；再进入 `/profile/security` 检查脱敏身份和当前设备。
3. 演示绑定/换绑/解绑表单的能力控制和 revision 提示，但正式演示账号不要提交高风险变更；修改密码和全部设备退出同理。
4. 打开 `/apply-teacher`，选择合规图片/PDF 后由 media-service 上传，再提交申请；审核决定必须到后续管理流程演示。
5. 打开 `/forgot-password` 和验证码登录，确认生产短信未接入时手机入口禁用；完成邮箱挑战后，重置挑战不出现在 URL 或持久存储。

## 风险与后续依赖

- 本地默认配置中 Gateway Redis DB 与 IAM local Redis DB 不同；真实会话验收必须显式对齐，否则 Gateway 会安全地返回 `GATEWAY.SECURITY_STATE_UNAVAILABLE`。正式部署也必须保证二者共享会话版本缓存。
- 真实短信供应商未接入，前端只能按渠道能力关闭手机相关发送入口；不得用固定码模拟生产能力。
- 教师资质真实上传依赖 media-service、OSS 和 `TEACHER_QUALIFICATION` 授权；本轮浏览器未创建 OSS 对象，自动化已覆盖生成契约调用和失败状态。
- T16 再实现教师审核决定，T17A 再实现头像与完整资料，T12H 再设计账号注销；T12E 页面不提前展示这些入口。
- 分支为 `codex/t12e-account-security-web`，关键实现提交为 `57b3643`；Ready PR 为 [#41](https://github.com/chengchen-ccnu/ccnu-edulion/pull/41)，本任务不自行合并。

## 注释与可读性检查

人工检查了本任务新增和修改的 Vue、TypeScript 生产代码：页面、组件、认证状态机扩展、API 适配器、内存挑战和复杂错误映射均有通俗中文 TSDoc/JSDoc 或职责注释，说明数据来源、输入、结果与安全边界。生成客户端文件未手工修改或补注释。
