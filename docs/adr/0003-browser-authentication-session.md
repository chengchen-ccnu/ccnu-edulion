# ADR-0003: Browser authentication session

- 状态：Accepted
- 日期：2026-07-17
- 前向补充：2026-08-30（T12D.1 已实现，合并后成为主线事实）

## 背景

学生、教师和管理端都通过 Gateway 访问平台。浏览器需要在降低长期凭据泄露风险的同时，支持页面刷新、短期 Access Token 过期后的无感续期以及可靠退出。

## 决策

- IAM 签发 15 分钟有效的 JWT Access Token，前端只保存在内存中，不写入 `localStorage`、`sessionStorage` 或持久化状态。
- IAM 签发 7 天有效的不可预测随机 Refresh Token。浏览器只通过名为 `EDU_REFRESH` 的 `HttpOnly`、`SameSite=Strict` Cookie 持有它；Cookie 路径限制为 `/api/v1`，让身份变更端点能够识别当前 Refresh Session。Gateway 在转发非 IAM 路由前精确移除该 Cookie，避免把刷新凭据扩散给其他领域服务；非 local 环境必须启用 `Secure`。
- 数据库只保存 Refresh Token 的 SHA-256 哈希。刷新时在同一事务内将旧会话标记为已轮换并创建新会话；再次使用旧 Token 返回 `409 IAM.REFRESH_REUSED` 并撤销该 Token family。
- 登录与刷新响应体只返回 Access Token 及其有效期。刷新 Cookie 通过 `Set-Cookie` 返回，前端 JavaScript 不可读取。
- 前端收到受保护请求的 `401` 时最多刷新一次，然后只重放原请求一次；刷新失败则清空内存状态并跳转登录页，避免无限循环。
- 平台只提供一个用户可见登录入口 `/login`，由兼具公开门户职责的 `student-web` 物理承载；这不代表登录入口属于学生角色。登录页及外层壳统一使用“Edu Lion 在线教育平台”“统一登录门户”等角色中立文案，`teacher-web` 和 `admin-web` 不提供第二套登录表单。
- 登录回跳仅接受前端维护的站内路由白名单；拒绝绝对 URL、协议相对 URL、反斜杠、控制字符和未知路径，避免开放重定向。白名单进一步区分公共目标和工作台目标：`/courses`、`/courses/{courseId}` 对任意身份开放，不要求 `STUDENT` 或 `learning:access`；教师端、管理端和学生学习空间仍必须同时满足对应角色与最小权限。
- 登录后以 `/api/v1/users/me` 返回的角色和权限决定去向：单角色直接进入对应工作台，多角色进入统一“选择工作台”页面；访问无权应用时显示 403 并列出有权进入的工作台。
- 三个前端应用可部署在不同 Origin。跨应用跳转会丢失内存 Access Token，目标应用必须使用同一 Gateway 域下的 HttpOnly Refresh Cookie 恢复会话，不得因此把 Access Token 改存浏览器持久化存储。
- 公共课程页允许匿名立即渲染；首次进入时可在后台非阻塞地尝试一次 Refresh Cookie 会话恢复，用于显示工作台入口和退出按钮。恢复失败只保持匿名状态，不阻断课程请求、不自动跳转登录，也不重复刷新形成循环。
- 角色描述身份，权限描述能力，两者不应被视为同一个概念。当前学生专属首页仍保留既有 `STUDENT + learning:access` 守卫；T11/T12 引入真实学习域后，学习工作区优先按 `learning:access` 授权。教师可在同一账号同时拥有 `teaching:access` 和 `learning:access`，无需注册第二个账号，并可在学习中心与教师工作台间切换。
- Gateway 验证 JWT 的签名、发行者、受众和有效期，并在转发前移除所有外部传入的用户上下文头，再写入可信的 `X-User-Id`、`X-User-Roles`、`X-User-Permissions`。IAM 仍按认证主体查询当前用户，不信任浏览器提供的身份头。
- 退出撤销当前 Refresh Session、清除 Cookie。Access Token 保持短期自然过期；本轮不维护 Access Token 黑名单。

## T12D.1 前向补充决策

以下规则已经由总控产品与安全审计确认，并由 T12D.1 在功能分支实现；合并前仍不得把它描述成主线已发布能力：

- 正式和测试环境统一只允许已验证邮箱或手机号登录；昵称允许重名且不参与鉴权。既有 `account` 仅保留为兼容内部技术标识，新注册由服务端生成，不提供用户名登录后门。
- 密码登录接受邮箱或手机号；验证码登录使用邮箱或手机号、Redis 算式验证码和一次性验证码，只登录已有账号且保持未知身份不可枚举。密码第 4～5 次尝试需要算式验证码，第 5 次仍失败后锁定 15 分钟。
- 邮箱通过供应商无关端口接入标准 SMTP；手机在 local/test 使用固定码，生产未配置短信供应商时由服务端渠道能力隐藏手机注册和验证码登录。Seed 使用 `example.invalid` 已验证身份，绝不向其发送邮件。
- 每个账号最多绑定一个邮箱和一个手机号。新增第二身份撤销其他设备，换绑或解绑撤销全部设备；至少保留一个已验证身份，所有变更使用 revision、审计和持久 sessionVersion。
- 用户协议和隐私政策按不可变版本保存，IAM 公开当前正文、版本、生效时间和重新同意标记，注册只接受当前有效版本；旧正文和历史同意不可覆盖。
- 不增加“记住我”，继续使用 15 分钟内存 Access JWT 和 7 天 HttpOnly Refresh Cookie。注册成功后前端使用仍在组件内存中的邮箱/手机号与密码调用既有登录接口；若登录失败，账号创建结果仍然有效且不得重复注册。
- 完整 JWT 不写 Redis。MySQL 保存 Refresh Session、设备、Token 摘要、撤销和审计权威事实；Redis 只保存或缓存验证码、冷却、失败计数、短期锁定、限流和当前 sessionVersion。只有在压测证明 Refresh Session 查询成为具体瓶颈后，才允许以 MySQL 为事实源增加 Cache Aside 活跃会话缓存。
- 退出网络失败时，前端不得把“本地清空内存”描述成“服务端会话已安全撤销”；应锁定本地界面、显示未确认状态并提供有限重试。

## 后果

- 受保护页面硬刷新后需要先调用刷新接口恢复内存中的 Access Token，再查询当前用户；公共课程页不等待该过程。
- Cookie 模式要求前端请求使用同源 Gateway 或显式 `credentials: include`；生产环境需要 TLS。
- 本地开发固定 student、teacher、admin 的端口为 5173、5174、5175，并通过 `VITE_*_WEB_URL` 显式配置跨应用地址；端口被占用时启动失败，避免静默换端口后产生错误回跳或 CORS 问题。
- Access Token 在退出后最多仍可使用 15 分钟，因此高风险操作后续应增加更强的即时撤销或二次认证机制。
- Redis 集中式 Session 与本 ADR 的混合方案都是有效工业架构。本平台选择混合方案是因为普通请求可由任意 Gateway 实例验证 JWT，Refresh Session 仅在登录、刷新、退出和设备管理时访问；MySQL 提供设备与撤销审计的可靠事实，Redis 承担高频短期安全状态。不得把“多实例”误解为必须在 Redis 保存完整用户 Session。
