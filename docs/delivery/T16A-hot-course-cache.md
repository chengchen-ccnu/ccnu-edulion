# T16A Redis 热点与门户派生缓存

分支 `codex/t16a-hot-course-cache`；准备祖先 `30000d5` 保留，实现提交 `df0e22c`。已用独立命令`git push origin HEAD`推送并创建Ready [PR #85](https://github.com/chengchen-ccnu/ccnu-edulion/pull/85)（OPEN、非Draft），交由总控验收；未合并。

## 目标与边界

最多六门当前可公开发现的课程卡片。最近七个上海自然日（今天及前六日）的稳定学生去重人数为主，当前ACTIVE报名为辅；同分按发布时间降序、课程ID升序。无学习人数的课程只在最近30天发布时补位，明确标为NEW（前端应展示“新课”），没有课程或不足六门如实返回。

真实学习只计已持久化VIDEO有效正观看增量、ARTICLE明确完成及课程主动正式提交；打开页面、报名活动日、AI、草稿、未落库心跳及自动结算不计。跨日/跨课时/内容修订均按同人去重，不把每日人数相加。历史只读取有真实日期的日事实/正式提交，不从累计进度伪造七日历史。取消报名不抹除已经发生的学习，辅助信号只计当前有效报名。

只做后端、契约、生成客户端与验收；没有Vue/T17A、导航、个性化推荐、通用缓存框架或未读数缓存。T16.4授权和通知事实继续实时读取。

## 架构和流程

1. Course发布/下架在原事务写CoursePortalChanged v1 Outbox；暂停/恢复复用CourseAvailabilityChanged v1。Learning落库首次日资格写hot_learning_day及CourseLearningDayQualified v1 Outbox；报名复用EnrollmentAccessChanged v1。
2. 复用原生产者mandatory、confirm/return、有限退避及FAILED事实。Operation独立热点队列/DLQ，消费总计三次，eventId inbox及dirty同事务登记。事件只作失效信号，重复和乱序不做加减计数。
3. 后台每5秒最多50个dirty课程＋50个源卡片（总量100），空闲完整扫描每30秒重新开始。Course所有稳定ID以keyset分页，非公开tombstone删除投影；Learning在本域一致快照COUNT DISTINCT真学习及当前ACTIVE。失败回滚派生批次并保留dirty/游标。
4. 同一Operation数据库协调行串行化源读取/投影替换/Redis发布和事件登记。新事件如果在旧源扫描期间提交，随后登记dirty再读当前源，不能被旧扫描清除。此协调只涉及派生任务，不锁课程、报名或成绩核心事务。重建分批释放锁；持续增量不会挤占重建预算。
5. 单Redis JSON key原子保存精确排序的完整最多六项结果，避免浮点加权损坏主辅排序。TTL75～90秒随机抖动，validUntil不晚于下个上海零点或最老源刷新时点＋90秒。任何派生行跨日或超过90秒未刷新时停止续命榜单，首轮未完成不输出假完整榜单。Redis丢失靠后台源重建。
6. GET仅Redis GET＋到期字段裁剪，无SQL、领域HTTP或请求内重建。返回READY/EMPTY/UNINITIALIZED/UNAVAILABLE，Cache-Control:no-store。课程搜索/列表独立可用；热点缓存不授正文、报名、作答或媒体权限。跨域失效仍为最终一致，事件消费会尽力立即删榜单。

## API、事件、表和迁移

- OpenAPI0.31.0：匿名 `GET /api/v1/operations/hot-courses`，Gateway精确放行该GET，其他Operation写入仍受保护。客户端由同一OpenAPI生成，Mock也读取此源。
- 内部1.0.0：Course `GET /internal/v1/hot-course-source?after=&size=100`（上限100）、单课读取、`POST /internal/v1/hot-course-cover/{courseId}`；Learning `GET /internal/v1/hot-learning-source/{courseId}`。固定operation-service与现有独立加密秘密；不经Gateway，不提供领域写权限。
- 新事件规范 `docs/events/hot-course-events.v1.yaml`，未修改旧事件版本；评审记录 `docs/api/T16A-contract-review.md`。
- Learning V21：`hot_learning_day`，主键(course_id,learner_id,activity_date)，event_id用于首次资格Outbox身份；来源事实不含签名地址。
- Operation V2：`hot_course_refresh_state`、`hot_course_projection`、`hot_course_consumed_event`、`hot_course_dirty`。派生表只含稳定ID、公开元数据、人数和窗口，禁止URL/学生名单/OSS字段。迁移只向前，未改已合并迁移。

## 封面有效期

Course在公开且非暂停的当前发布版本上取得coverMediaId，再用既有Course→Media一次性授权链请求ORIGINAL/INLINE短期封面。既有String封面接口保留，热点新增带真实expiresAt的授权投影。Operation不接OSS SDK；旧coverUrl永久地址不用于热点。

URL只进入Redis短期响应投影，不写MySQL/事件/日志。后台刷新前六项授权，授权失败单卡coverStatus=UNAVAILABLE；无mediaId为ABSENT。距真实期限不足5秒时GET移除URL/期限并标UNAVAILABLE，客户端可重新GET等待下一轮新授权。既有授权无法承诺暂停瞬间吊销；公共发现卡片不改变课程内容授权。

## 启动、环境与受控重建

标准命令从仓库根执行，秘密命令必须经过 `scripts/Invoke-WithLocalEnvironment.ps1`。local需要既有Course/Learning/Operation/Media dev Schema和RabbitMQ/Redis，Flyway只新增本域V21/V2。默认非local/test没有热点后台；local开启Worker，MQ随RABBITMQ_ENABLED开启。

| 配置 | 默认/说明 |
|---|---|
| OPERATION_HOT_WORKER_ENABLED | local true，是否开启后台刷新/重建 |
| OPERATION_HOT_MESSAGING_ENABLED | 跟随RABBITMQ_ENABLED，独立热点消费 |
| OPERATION_HOT_QUEUE | edu.operation.hot-courses.v1；独立durable主队列及同名.dlq/.dlx |
| OPERATION_HOT_NAMESPACE | edu:operation:hot:v1；Redis单key后缀:board，不与安全状态共用 |
| OPERATION_HOT_POLL_DELAY | 5s，后台轮询间隔；超过90秒刷新预算会明确降级 |
| OPERATION_REDIS_DATABASE | 既有5；生产可淘汰缓存与安全/进度缓冲隔离资源池 |
| OPERATION_HOT_TRACE_PREFIX | hot；非敏感短前缀，验收以随机前缀精确关联Media授权审计，长度应不超过27 |
| OPERATION_INTERNAL_SERVICE_TOKEN | 复用DPAPI独立只读秘密，不写local.env |
| COURSE_SERVICE_BASE_URL / LEARNING_SERVICE_BASE_URL | 后台领域HTTP地址，3秒连接/请求超时 |

初始、每轮源巡检及丢key自动重建。运维代码入口 `HotCourseWorker.requestRebuild()` 只复位本域游标和缓存，随后以同一Worker路径补齐；没有开放浏览器重建写API。可在停止本任务Worker的维护窗口内只更新该派生协调行（将last_completed_at置NULL、rebuild_after置空），恢复Worker后观察成功计数与榜单；不要清空核心表或重放历史FAILED。

指标：operation.hot.refresh.success/failure/duration、operation.hot.cover.failure；数据库保留last_failure_at/last_completed_at、dirty及游标。监控Outbox FAILED、独立DLQ、刷新超过90秒、Redis失败及API降级比例。每个源批次有明确上限；大规模高并发尚需生产容量与资源隔离评估。

## 测试与实际结果

已执行：OpenAPI生成、前端typecheck、56文件374项测试、三端build通过（原有大chunk警告）；真实Prism新GET返回200并通过请求验证。最终Java全仓clean verify九个Reactor模块成功，401项测试（344执行/57条件跳过）、0失败/错误；真实依赖专项由显式脚本单独运行，不将跳过算通过。

热点H2事务与源专项覆盖字典序排名、NEW补位、零/不足六门、tombstone、跨日同人去重、7日边界、取消重报、内部身份、重复/乱序、源失败保留dirty、并发重建与到期封面。既有通知测试修正H2微秒舍入造成的偶发“尚未到期”前置条件，生产通知逻辑不变。

最终资格锁定读修订后再次执行Learning全模块verify：144项、116执行/28条件跳过，0失败/错误；最终真实脚本两项再次通过。提交后`check:api`重新生成与HEAD一致。GitHub CI以PR当前head检查为准，不将本地真实依赖结果替代总控独立验收。

2026-10-02最终真实脚本通过：Operation长链1项、Learning生产者1项，均0失败/0跳过。109门合成课分页初次重建1457ms（上一通过轮1536ms）；同人第0/6日仍1人，第7日排除，报名活动日不计；取消/重报/旧消息、同eventId重投、草稿/归档/下架、108门批量暂停恢复全部通过。十次Gateway GET未调用源适配；实际Redis坏端口只热点UNAVAILABLE，课程API仍200；丢keyUNINITIALIZED→重建READY。真实坏HTTP保留dirty，旧扫描时的新发布随后追平；毒消息3次后独立DLQ，mandatory return耗尽3次FAILED。20秒封面原签名真实OSS GET200，到期响应先裁掉URL，服务端跨过到期后原签名403，后台重新授权READY。本机比OSS HTTP Date快约6秒，验收按服务端期限额外等待，没有修改签名或期限。Learning真实持久进度重复/同日/跨日仅2条日资格Outbox，四线程同时认领第三日只新增1条；3条confirm发布到独立队列且七日去重人数仍1。唯一键竞争后用FOR UPDATE当前读，不沿用旧事务快照。

失败探索轮不计通过：监听器配置循环已分离、分页验收等待条件与数字断言类型已修正。清理初始100条上限小于批量暂停消息，修订为本轮eventId数；旧轮656条已记录精确eventId/课程/暂停周期并在确认核心Fixture已删除且无消费者后精确ACK，没有purge。最终复查派生三表、学习资格表、合成课程均零，私有Rabbit队列不存在，两条共享队列零消息；历史Outbox全字段和人工课程前后相同。

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl learning-service -am verify
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
pnpm.cmd --dir web-apps run mock:api
pwsh -NoProfile -File scripts/Invoke-T16AHotCoursesRealAcceptance.ps1
```

## 数据、清理与演示

没有新建数据库、账号、题目、媒体对象或长期Seed。真实验收在既有四个dev Schema创建109门合成课程/版本（验证超过100项分页）、两个合成学生UUID、最小报名/学习日、随机Outbox/Inbox/dirty/投影、暂停区间、独立Rabbit队列和Redis namespace。封面只读复用既有稳定媒体ID，新增授权审计以随机trace精确删除，不改对象或绑定。Learning生产者专项另外创建随机进度/日事实/三条资格事件，finally精确清理。

脚本每轮先确认历史PENDING=0，正常完成或断言失败在finally按课程UUID、eventId、授权trace、Redis单key和独立队列清理；共享队列只ACK本轮eventId，外来消息requeue，不purge。历史Course/Learning Outbox全字段及手工《线性代数》比较不变。禁止FLUSHDB、范围模糊删除和历史FAILED重放。结构V21/V2与协调初始行按预期保留；重新运行脚本用新UUID覆盖同一验收，未保留永久伪数据。

人工演示：用local后台运行域服务/Gateway，GET热点确认最多六项；ACTIVE报名与可靠学习后观察HOT与人数，退课观察辅助数改变；暂停/下架观察移除，恢复重新进入；隔离本热点Redis连接验证UNAVAILABLE与课程列表独立；删除仅热点board key并观察UNINITIALIZED→后台READY；等封面授权过期且后台暂停刷新，确认URL清除，再恢复后台获得新授权。

个人local.env本轮未修改、没有需要删除的新增条目；上表服务地址/namespace/间隔是非敏感运行配置，可按环境保留。验收20秒Media TTL、随机队列/trace/namespace只在进程作用域，finally恢复；秘密仍在本机DPAPI，正式部署由Secret Manager注入，不迁移为明文。

## 可读性与后续风险

新控制器、源适配、Worker状态变化、消费者幂等、队列重试、Redis过期及生产者事务均有中文功能/输入输出/副作用注释；新迁移、协议与配置说明边界，自动生成代码未手补注释。派生后台串行批次避免旧快照覆盖新刷新，但不宣称跨域瞬时一致。未有日期的历史不伪造；生产高并发吞吐、完整浏览器首页及多实例容量专项留后续，T17A消费真实生成契约而不是假卡片。
