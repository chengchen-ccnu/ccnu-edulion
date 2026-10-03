# T17A 学生首页、课程公告与消息前端前置审计

2026-10-03，总控基于 `main@0343d6fb569ef589b62c1ad9c28d08ccc629c7aa`（PR #85）审计。实施分支 `codex/t17a-student-home-notifications`。依据 AGENTS、完整 page-specs、backlog、OpenAPI 0.31.0、实际 Java/前端代码及 T16 系列交付记录。

## 开工结论

**核心范围前置满足，可以新开任务实施。** T12E、T16.2～T16.4、T16A 均已合并；这不是前端已经完成的声明。总控只验收/合并/准备文档，不写业务代码。

T16A 最终 PR 八项 CI 与合并后 [CI #37085921484](https://github.com/chengchen-ccnu/ccnu-edulion/actions/runs/37085921484) 四项均成功；总控独立 Java401项、前端374项/三构建及真实依赖两项验收通过。临时数据精确清理，main已与origin/main一致，远端T16A分支保留。

本轮范围：公开首页（分类入口、真实最多六门热点/新课卡、登录学习账号的有界继续学习）、ACTIVE 报名课程公告、三端头像菜单链接到唯一学生端消息页、未读与确认、教师申请结果和真实会话能力恢复。无需等待 T17B/T17C Vue；验收公告和审核结果可通过既有受保护 API 与精确临时 Fixture 产生，不能为方便测试绕过所有权或审核事实。

## 契约与代码核对

| 页面/能力 | 现成事实与消费要求 |
|---|---|
| `/` 与首页第一槽 | 旧实现是 STUDENT 限制的 PlaceholderPage、导航 coming-soon。T17A 按既定规格改为公共角色中立首页，只启用原槽，不增删/重排顶栏 |
| 热点卡 | `GET /api/v1/operations/hot-courses` 与生成类型已交付；READY/EMPTY/UNINITIALIZED/UNAVAILABLE、最多6项、HOT/NEW、封面独立状态和真实 expiresAt。仅经 Gateway，不从普通列表同步重算热榜，不持久保存签名，不静默展示已过期榜单 |
| 分类/继续学习 | 复用 `/courses/categories`、本人 enrollment 和 course progress、既有 resume 规则；只加载有界数量，部分失败独立处理，不一次遍历全部报名。无学习能力账号不请求学习域 |
| ACTIVE 公告区 | 课程公告 list/detail/confirm 已由 Operation 实现并经 Gateway 保持独立授权；放在介绍之后、目录之前，不改公共 CourseDetail 投影。确认携带 requestId+expectedRevision，409 不静默确认新正文 |
| `/notifications` 与角标 | list/unread-count/read-all/target/read 全部已有 REST/Java/生成契约。只显示四类消息，不删、不设7天TTL；read-all跳过公告并展示剩余确认数，故障不能假零 |
| 消息目标 | target 只返回固定枚举及稳定ID，不返回任意URL。公告到课程详情定位，任务到本课程任务，审核到申请页，暂停/恢复到通用课程状态；普通消息仅目标成功加载后标已读，公告只在“我知道了”确认 |
| 能力恢复 | `GET /users/me`、refresh 与 authStore.ensureCurrentUser/authorizedRequest 已存在。T16.2 已验证批准同事务增权和会话刷新；前端最多一次401刷新，读取最新申请/真实权限后决定教学入口，不复制登录或用按钮授予权限 |
| 三端壳 | AppShell只渲染结构，各应用提供固定导航/账号模型。T17A可最小修改教师/管理端头像工具以跨应用链接唯一 `/notifications`；不实现其工作台/管理页面，不能让管理员获得学习/教学能力 |

## 旧规划隔离，不能伪装完成

- 头像/昵称资料编辑：当前 `/profile` 仅摘要/申请/安全入口，`CurrentUser.avatarUrl` 是读取字段；无完整更新及稳定 mediaId 绑定/释放契约。后移到独立账号资料契约与页面任务，继续遵循 Media 生命周期，不让前端用任意URL“更新头像”。
- 正式协议重新同意：当前仅公开正文/版本/requiresReconsent与注册时同意。缺已有用户待同意查询、准确版本写命令及访问限制边界；不能只弹勾选框或覆盖注册历史。后续先设计契约，再做页面，不属于本次 T17A。
- 取消免费报名：现有 DELETE owned enrollment 契约真实存在，但当前前端无该适配/交互；不作为消息首页闭环依赖，不并入本轮。后续学习维护任务确认取消影响及 UI 后接入。公告/消息退课安全验收仍可调用既有 API。
- 教师公开主页、Banner/人工运营位、收藏/评价、营销消息、管理员审核/邀请、教师公告编辑、账号注销均不扩入本任务。后移事项仍记于 page-specs/backlog，不由 T18 集成时首次开发。

## 环境核对与启动边界

- JDK17、Node20.19.5、pnpm10.22.0可用；Java全仓及前端类型/测试/三端构建已独立通过，无需新增中间件。
- 共享加载器只读认证确认现有 dev 迁移：IAM V5、Course V7、Learning V21、Operation V2、Media V6。MySQL3306、Redis6379、Rabbit5672可达；真实 T16A 长链已证明认证和领域 HTTP/Media→OSS。Operation只读秘密、IAM JWT、验证码秘密、Course→Media秘密和Rabbit/OSS配置存在，不打印值、不改 local.env。
- 四个固定测试用户仍 ACTIVE：学生为 STUDENT，两个教师为 STUDENT+TEACHER，管理员仅ADMIN；未修改账号/密码。`IAM_SEED_PASSWORD` 当前未注入，但 Seed 默认关闭，已有账号不需重新 Seed。禁止擅自开启初始化器重置这些账号；浏览器验收使用操作者控制的现有登录或专用临时 Fixture，密码只留进程/安全加载器。
- 实施时启动现有 IAM/Gateway/Course/Learning/Operation 和封面需要的 Media，使用仓库安全加载器/现有启动流程；标准端口8080～8085、student5173/teacher5174/admin5175以实际配置为准。Operation消息/热点后台、领域可靠发布器需正常开启。运行发布器前检查存量 PENDING，不能重放历史 FAILED。
- 无需 Python模型、Qdrant、Neo4j、FFmpeg转码或真实SMTP发送。审核结果来自已可靠决定的IAM事实，不靠发邮件；既有封面/资质若读取仍需Media/OSS。服务未常驻运行不等于缺少安装环境，启动后再验证真实页面。

## 前端验收与数据规则

- 首页：匿名/登录/多能力、分类/继续学习、HOT/NEW/少于6项、四状态、封面失效及热点故障隔离；手机/平板/桌面卡片美观，不以内部字段直接堆砌。
- 公告：未报名/试看/退课不能获取正文；报名后旧公告不置顶、最新未确认及其他数量、逐条确认、刷新/深链、改版409、撤回安全404；纯文本不执行HTML或自动链接。
- 消息：四类及真实目标、只在目标成功后读、公告快捷操作不可跳过、未读/部分故障、分页、路由恢复、账号切换清理；暂停显示通用状态/邮箱而不是私有内容或审核原因。
- 申请：批准/驳回状态、真实权限刷新、进入教学空间、材料过期需重上传及409；T17A不实现管理员审核器或第二个上传器。
- 覆盖loading/empty/partial-error、401/403/404/409/422/429/offline/503、360/768/1440宽度、键盘/菜单焦点及离页清理。有限重试/尊重Retry-After，不持续轮询不可见页面，不把一次网络失败当注销。
- 仅明确dev/test数据；按UUID/eventId/队列/namespace精确finally清理。保护手工《线性代数》及其版本/媒体，不写历史 Outbox、不purge/FLUSHDB。需要演示Seed须另外显式local/test、幂等并有Cleanup/Rebuild；本轮总控无常驻Seed。
- 同任务更新 `docs/delivery/T17A-student-home-notifications.md`、backlog、verification及必要环境说明，记录中文输入输出注释、实际命令/真实浏览器结果和未通过项。先提交推送Ready PR，待总控合并；不得自行合并。
