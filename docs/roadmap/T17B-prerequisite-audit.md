# T17B 拆分与 T17B.1 教师课程公告管理前置审计

2026-10-03，总控以 `main@1f6b971c40724efa471a06ab00b2e432a5dc6a1a`（PR #86）为基础。新分支 `codex/t17b1-teacher-course-announcements-web`；本次只提交规划与审计，不实现业务代码。主依据为 AGENTS、完整 page-specs、OpenAPI 0.31.0、T16.4/T17A 交付与实际 Java/前端代码。

## 1. 结论与实施顺序

**T17B.1 前置满足，可以新开任务实施教师课程公告管理。** T16.4 后端已合并，T17A 学生公告与消息已验收合并，合并后[CI #37103356361](https://github.com/chengchen-ccnu/ccnu-edulion/actions/runs/37103356361)四项成功。本轮生成客户端一致检查与git diff --check通过。优先做教师发布到学生阅读/确认的真实闭环，不等待旧规划所有页面一起开工。

| 切片 | 当前事实、缺口与安排 |
|---|---|
| T17B.1 教师课程公告管理前端 | 五类owner操作、领域授权及消息可靠投递已实现；前端适配器和管理页尚未实现。只消费已有契约，可立即实施 |
| T17B.2 教师工作台 | 有Course列表、Learning测评/学情和Media资产等事实，不等于有明确工作台摘要/待办投影。原“学生问题”没有可消费教师问题列表契约。先确认指标、时间范围、待办类型、聚合所属域及降级，再补最小契约/页面；不能遍历全列表计数或复制学情/成绩册 |
| T17B.3 教师媒体中心 | `GET /api/v1/media`已有owner分页及purpose/status筛选，单项资产/上传会话/授权已有；教师适配器已有单项上传流程。但没有owner上传任务总览或领域引用位置投影，MediaAsset也不返回课程引用。先确认收缩为真实资产中心，或由领域API/事件补投影后拆实施；不得自行列OSS/跨库查引用/增加无接口重试或删除 |
| T17B.4 账号及教师资料 | CurrentUser昵称/头像是读投影，身份绑定/资质查询已经存在；缺完整资料写入、头像稳定mediaId绑定/替换释放及公开资料边界。与普通账号资料统一先补契约，再给教师页消费，不重复教师申请/登录。协议重新同意另行规划 |

T17B.2～4均未宣称前置通过，本次不创建其实施分支，不强行把它们作为公告管理的依赖。首期发布前须另行交付或经总控明确后移，不交给T18首次开发。现有教师工作台Placeholder是历史残留，固定槽仍禁用且不算交付；后续对应任务消除，不借本轮扩范围。

## 2. T17B.1 契约与代码

| 操作 | 冻结路径与实际边界 |
|---|---|
| 本人课程上下文 | 现有 `GET /api/v1/teacher/courses` 及owner课程详情/草稿适配可复用；页面路径不是授权。仅当前teaching:access且owner，非owner安全404 |
| 列表 | `GET /api/v1/teacher/courses/{courseId}/announcements`，1-based分页、默认20/最大100，owner返回ACTIVE/WITHDRAWN。没有教师单公告GET，不发明端点或扫描全部页 |
| 立即发布 | 同路径POST，`AnnouncementWriteRequest`：clientRequestId/title/body。标题1～120、正文1～20,000，纯文本且遵循控制字符校验；事实保存返回201，不承诺已同步通知全体学生 |
| 改版 | `PUT .../announcements/{announcementId}`，稳定逻辑requestId＋expectedRevision；成功保存新审计并重置合资格学生同一消息的未读/确认。409保留本地输入，刷新后明确决定，不自动覆盖 |
| 撤回 | `POST .../{announcementId}/withdraw`，requestId＋revision，二次确认；WITHDRAWN仍供owner读，学生列表/旧链接立即不可读。没有恢复、物理删除或定时发布契约 |
| 审计 | `GET .../{announcementId}/revisions`，有界分页旧标题/正文/状态/时间，只读，不新增个人已读名单/催读 |

Java `AnnouncementController`、`AnnouncementService` 已真实实现以上操作；`requireOwner` 通过受控Course事实授权，写入仅PUBLISHED且未暂停。读历史不等于可写，前端不能按草稿编辑版本独自推断发布状态。公告/版本/命令/Outbox事务和幂等继续复用T16.4，不增加迁移或第二套消息实现。

Gateway已为教师/学生announcements独立路由至Operation，前端仅经Gateway和统一认证（401最多一次刷新）。现有生成schema含完整请求/页类型；只增加有消费者的教师适配函数，不手写第二套DTO、不改OpenAPI/事件以凑页面。发现新缺口时报告，不擅自补后端。

## 3. 页面实现边界

- 唯一管理页 `/teacher/courses/:courseId/announcements`，从本人课程卡片及既有编辑/编排上下文进入；导航沿用“课程管理”高亮，不增加独立公告顶栏或启用工作台/媒体槽。URL保存有界页码、公告/面板标识，正文不进URL；选中项不在已载分页时明确要求定位分页，不无限扫描。
- 列表/编辑/审计布局保持清晰，可在同页用面板切换；只有当前有效公告可改版/撤回。草稿/下架/暂停表达不可写状态，读取仍服从owner接口，管理员不得代发。
- 纯文本输入、字数提示、明确立即发布/改版/撤回动作。改版说明会重新提醒当前合资格学生，撤回说明学生不可再看；不显示个人已读名单。无附件/图片/Markdown/HTML、定时、恢复/删除、公告复制发布或邮件短信。
- 保存采用稳定逻辑requestId，不改变载荷后复用旧ID；修改载荷是新命令。网络未知结果只用原ID有限重试，不伪造成功/重复发布。409保留输入和新事实，422支持字段与request级错误，429尊重Retry-After；离页取消请求，有未保存内容才警告，不写第二套自动保存。
- T17A学生公告、消息和账号菜单保持原职责，仅作为真实验收消费端，不搬迁或复制。临时授权失败/暂停/非owner不能继续展示旧私有正文。

## 4. 环境审计与真实验收前置

- JDK17、Node20.19.5、pnpm10.22.0存在；安全加载器只读登录MySQL并检查dev迁移：IAM5、Course7、Learning21、Operation2。四域Outbox PENDING=0；没重放FAILED或启动发布器。
- Redis认证配置下PING=PONG；RabbitMQ一次实际连接认证通过并关闭，未声明/删除队列或发消息。IAM JWT、Operation独立内部令牌、Rabbit密码、Course-Media秘密仅检查存在，不打印值，不修改local.env。
- MySQL3306/Redis6379/Rabbit5672监听；当前8080/8081/8082/8084/8085及5173/5174未常驻。**安装/配置满足不等于本轮业务HTTP/浏览器已验收。** 新任务正常启动Gateway/IAM/Course/Learning/Operation及学生/教师端后验收；需课程封面才启动Media。无需Python模型、Qdrant、Neo4j、FFmpeg、资质上传或真实SMTP。
- 复用既有启动流程；`Invoke-T17ABrowserEnvironment.ps1`目前会启动六服务/三应用并检查五域PENDING，执行前完整阅读脚本并核对端口/已有进程。不得为方便浏览器测试开启Seed重置固定账号。
- 真实验收使用明确dev临时owner/学生和已发布课程，覆盖发布→学生消息及课程公告→确认→改版→旧revision409→新确认→撤回→旧链接安全失败；同时检验非owner/管理员、草稿/下架/暂停写拒绝及深链接/返回/刷新、分页/审计、加载/空态/部分失败、401/403/404/409/422/429/offline/503、360/768/1440与键盘/离页清理。未执行项如实记录，不用组件测试冒充真实链。
- 保护手工《线性代数》课程、版本、媒体、题库和历史Outbox。本任务不需改已有用户课程；使用最小标记Fixture并按UUID/eventId精确清理公告、确认、消息、命令、Outbox/inbox及临时领域事实，保留共享MQ拓扑，不purge或FLUSHDB。不新增大型Seed或二进制产物。

## 5. 交付要求

同任务新增 `docs/delivery/T17B.1-teacher-course-announcements.md`，更新backlog/verification，记录架构/接口、启动、测试、真实演示、清理与已知边界。新Vue/适配器/复杂测试中文说明职责、输入输出、授权、revision/幂等、有限重试及副作用；生成代码不手工注释。

至少执行check:api、八workspace类型检查、前端全量测试、三端构建及真实教师/学生公告消息闭环。新任务保留本规划提交，不重置main或搬旧分支业务代码；按仓库规则独立 `git push origin HEAD`、创建Ready PR并附加，等总控验收合并，不自行合并。

本次总控只读审计，没有创建库、表、伪数据、消息、缓存Key、媒体或OSS对象；无数据清理/重建步骤。规划提交仅保存在当前准备分支，是否推送/创建规划PR由用户另行授权；下一实施任务可正常沿用后续功能PR携带此提交。
