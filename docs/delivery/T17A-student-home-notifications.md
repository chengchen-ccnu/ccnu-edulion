# T17A 学生首页、课程公告与消息前端

2026-10-03。实施分支 `codex/t17a-student-home-notifications`，保留准备提交 `412709e`，未新建/重置分支。状态：实现及本机验收完成，待总控验收；不自行合并。PR与实现提交在创建后补入本记录。

## 目标与边界

启用学生端原首页槽位：匿名分类入口、服务端最多六张热点/新课卡、学习账号最多三条继续学习。READY/EMPTY/UNINITIALIZED/UNAVAILABLE独立展示；封面缺失、不可用、解码失败或过期只影响该卡。榜单到期停止展示，后台不以普通课程列表重算热榜。

课程详情在介绍后、目录前显示ACTIVE报名学生公告。最新未确认来自服务端，其他公告可展开及分页，深链接独立读取并定位焦点。正文只作文本输出；只有点击“我知道了”提交requestId和眼前revision，409刷新后要求再次明确确认，撤回/退课/授权失败清除旧正文。

三端固定导航保持标签及顺序；账号工具只有工作台和账号两个槽。头像菜单“消息”及角标进入唯一 `student-web /notifications`。共享AppShell不判断角色，只处理结构、菜单外部点击及Escape焦点。角标读取当前授权数量，故障显示未知，身份/权限变化、消息访问、确认、已读和恢复可见触发有限刷新；不轮询。

唯一消息页展示四类真实安全摘要并分页。打开前重新读取固定目标，公告到课程详情、任务到本课程任务、审核到申请页、可用性到通用课程状态。目标真实加载并渲染后才提交普通已读；公告不调用普通已读。全部已读以服务端结果提示剩余待确认数。暂停只显示通用文案及支持邮箱。

教师申请/个人中心读取最新申请，申请页刷新真实 `/users/me` 能力；只有真实账号具有教学能力才出现教学空间入口。审批状态不会在本地赋角色。离页取消，账号快照替换但同ID不重复重读；离线不伪装未申请或注销。驳回重提需重新上传有效资质，沿用既有媒体上传器，提交与处理等待均可取消；隐藏页面暂停处理查询。

本轮未改Java、OpenAPI、事件或迁移；未实现头像/资料编辑、协议重新同意、退课UI、教师公告编辑器、管理员审核/邀请、收藏评价、人工运营位。不存在新增契约扩展；未用Mock/假课程作为生产数据。

## 架构、接口和主要文件

浏览器 → 唯一生成Gateway客户端 → 已有Gateway → Operation/Course/Learning/IAM/Media。领域授权、热点计算、公告版本、收件资格、审核决定和角色事实均由服务端负责。前端不跨服务查表，不接OSS SDK，不持久化JWT、签名地址、通知正文或计数。

- `packages/api-client/src/operations.ts`：生成契约约束的hot、notification、announcement适配器；`packages/auth/src/notification-counts.ts`负责身份绑定计数。
- `student-web/src/views/HomePage.vue`、`NotificationsPage.vue`、`components/CourseAnnouncements.vue`、`CourseStatusNotice.vue`及`notification-target.ts`：门户、公告及目标落地。
- `teacher-application-status.ts`与既有申请/个人中心、课程详情/任务页接入最新状态及真实能力。
- 三端`App.vue`提供账号菜单模型，`packages/ui`只渲染结构；生产页面类型全部来自生成文件。

消费现成接口：`GET /operations/hot-courses`、分类/公开课程、本人enrollments/course-progress；`GET /courses/{courseId}/announcements`及单条、`POST .../{announcementId}/confirm`；notifications list/unread-count/target/read/read-all；本人teacher-applications、`GET /users/me`、refresh及既有资质上传/申请提交。所有外部路径均带 `/api/v1`。消息目标只接受固定枚举及UUID，拒绝任意URL。

## 环境、启动与测试数据

无需新增数据库、表或迁移。沿用IAM V5、Course V7、Learning V21、Operation V2、Media V6。运行时配置沿用现有Gateway/三端URL及加密本地配置；个人`local.env`未修改、未增加秘密或临时配置，因此没有本轮需删除的条目。生产配置仍由部署环境/密钥管理提供，不能复制开发凭据。

`scripts/Invoke-T17ABrowserEnvironment.ps1 START`通过`Invoke-WithLocalEnvironment.ps1`启动六服务和三端；强制Seed关闭，启动发布器前检查IAM/Course/Learning/Operation/Media既有PENDING，发现存量即停止。`STOP`只停止manifest记录且启动时间匹配的本轮PID。要求现成jar；构建jar仍按仓库Java命令执行。本轮使用准备阶段已构建jar，未重跑未改动Java/Python Agent测试。

Fixture为`python scripts/t17a_browser_fixture.py create/cleanup/edit/withdraw/unenroll/pause`，必须放在安全加载器ScriptBlock内执行。create/业务动作需要调用者只在进程中提供`T17A_FIXTURE_PASSWORD`，禁止保存进local.env或Git。Python需已有bcrypt、mysql及redis-cli；密码、Token、签名不输出。manifest只有随机UUID；已存在时拒绝重复创建，cleanup成功后删除manifest，重复cleanup无操作。

本轮只在明确的`edu_platform_iam_dev`、`edu_platform_course_dev`、`edu_platform_learning_dev`、`edu_platform_operation_dev`已有表创建临时前置：三个`*_test_t17a`账号/身份/角色、一个发布课程/版本/章节/视频元数据、一次报名及区间、21条公告（20条分页需越界定位）、一个课程任务、一个待审申请轮次。公告/报名/任务发布/驳回/撤权都走真实受保护API，未伪造批准事实；任务仅引用已有题目版本，未写题库。消息、Outbox、消费、热榜、会话及审计为这些命令正常生成的本輪派生记录。

清理按UUID/eventId/精确聚合匹配，不按标题、不purge队列、不FLUSHDB。服务停止后精确删除本轮业务/投影/消费/命令/审计及账号记录；三条临时会话缓存精确DEL后EXISTS为0。未创建媒体/OSS对象、未发邮件、未创建私有验收MQ拓扑；服务按既有配置声明的共享业务队列保留。既有短期限流键遵循原TTL，不删除共享IP安全键。

清理后实际复核：账号4；临时course/assessment/messages/hot投影均0；手工《线性代数》仍1；题库聚合1017；历史Learning Outbox为PUBLISHED5734、FAILED6896、PENDING0。SQL均限定本轮ID，没有修改历史Outbox状态/正文或人工课程。8080～8085与5173～5175本轮监听均结束；浏览器测试页关闭、viewport恢复。

## 实际验证结果

| 命令/验证 | 本轮实际结果 |
|---|---|
| `pnpm.cmd --dir web-apps run check:api` | 成功，0.31.0生成文件与HEAD无差异 |
| `pnpm.cmd --dir web-apps run typecheck` | 八workspace成功 |
| `pnpm.cmd --dir web-apps run test` | 58文件403项成功，0失败；新增29项覆盖门户、公告、消息、身份与能力生命周期 |
| `pnpm.cmd --dir web-apps run build` | student/teacher/admin三构建成功；既有大chunk警告非阻断 |
| `pnpm.cmd --dir web-apps run mock:api` | Prism读取唯一OpenAPI；热点、messages及announcement查询通过请求验证并返回200。默认静态示例不是业务分页验收 |
| `git diff --check`、准备提交祖先检查 | 成功 |
| PowerShell AST、Fixture compileall、cleanup重入 | 成功 |

真实In-app Browser使用真实Gateway、六域服务、MySQL、Redis和Rabbit：匿名首页真实NEW《线性代数》卡及分类；登录回首页和有界学习部分故障；23条消息分页/深链刷新；任务真实单条详情成功后未读23→22，申请真实驳回与能力刷新后→21；公告定位/文本/介绍与目录顺序；真实编辑使旧确认409、新正文仍未确认，再次点击才确认；全部已读保留20条公告；管理员空消息及教师/管理头像跨应用唯一链接；真实撤权预览/决定→Course协调APPLIED→暂停消息，安全状态不泄露私有内容。

门户与消息页检查360/768/1440，document宽度不超过viewport，网格自动变为1/2/3列，固定导航保留横向滚动。键盘Enter打开头像、Escape关闭并恢复summary焦点，公告深链定位article，确认后焦点返回公告标题。

实际Redis故障仅令本轮Operation连接未监听端口，不停止共享Redis：返回UNAVAILABLE，分类/学习独立；恢复正常连接后暂停本轮Learning：消息503/未知角标/无空成功；暂停本轮Gateway：显示离线且提供手动重试。所有故障预算有限，无自动写重试或后台轮询。

401/403/404/409/422/429/503的组件错误矩阵、429 Retry-After阻止立即重发、目标失败不已读、公告撤回/权限变化清正文、身份切换/离页中止、未知计数、APPROVED有/无真实权限、退出后旧响应丢弃通过自动化。申请页快照替换循环首次真实浏览器发现后修复，并加入真实对象替换回归测试。

## 人工演示与后续依赖

1. 启动真实服务，匿名访问`/`及分类，刷新推荐查看真实卡片。
2. 使用自有学习测试会话进入`/notifications`；点任务/申请后返回，观察普通已读；点公告看课程中定位，阅读后明确确认。
3. 服务端改版后尝试旧确认，检查409要求重新阅读；撤回或退课后重读应不可见。界面没有退课操作。
4. 从三端头像进入同一消息页；全部已读后若还有公告，显示实际待确认数量。
5. 最新申请通过后刷新状态，教学入口必须同时依赖真实账号权限；仅改变显示状态不能授能。

本轮真实浏览器未重复OSS资质上传、真实批准和课程恢复长链；这些路径的前端权限/状态分支通过组件测试，后端批准/恢复保障沿用已合并T16验收，总控可按现有受保护API补充复验。未以人工页面批准或假材料绕过审核。真实EMPTY/UNINITIALIZED与封面到期/失败的分支由组件测试覆盖；真实门户观察READY及Redis UNAVAILABLE。本轮视觉检查不是完整屏幕阅读器审计。T17B/T17C仍负责公告编辑和管理员审核，头像/协议/退课维护保持后移。

## 注释与可读性

人工检查新增Vue职责、生成客户端边界、分页与授权目标、确认revision/幂等请求、计数身份绑定、有限重试、到期/离页清理、真实能力刷新、上传等待及验证脚本，均有中文JSDoc/docstring/说明。生成文件不手工修改注释；简单格式化/赋值不重复逐句注释。保留原导航与组件分层，无角色业务判断进入AppShell。
