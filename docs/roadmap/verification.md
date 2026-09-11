# Skeleton verification

## 2026-09-08 独立规划 PR 交接审计

- 已获用户授权提交并合并文档 PR，再准备 T10C.1；本次不实现业务代码。
- 已 fetch 最新 origin/main（685afe8），GitHub 查询无开放 PR；独立文档分支为 `codex/docs-agent-redesign-course-practice`，保留此前 cda4cdd 的 T11C.2 状态记录。
- 下节“未提交、未 fetch、未切换”等描述是此前规划阶段的历史快照，不是此次授权后的实时状态。实际提交、CI、合并记录以文档 PR 为准。
- T10B/T12B/T11C.2 代码依赖已合并，T10C.1 的剩余交接条件是规划 PR 合并后创建与最新 main 一致的干净 `codex/t10c1-question-bank-entry-contract` 分支。真实环境验收在功能任务按需执行；本次没有连接数据库或创建测试数据。

## 2026-09-08 总控 Agent 重设计与课程练习入口纠偏

- 状态：仅规划，未实现新 Agent、页面、协议、数据库或索引。Q1～Q91 的产品决定与后续导航纠偏见 [Agent 设计](../architecture/agent-platform-design.md)，访谈补充见 [记录](agent-grill-decisions.md)。本节取代前一审计中“按旧 T13 直接开工”的准备结论，历史验证记录不改写。
- 起点：分支 codex/t13-agent-interface-stabilization，HEAD cda4cdd（已有 T11C.2 状态文档提交）；本地 main 与当前缓存的 origin/main 均为 685afe8。未 fetch/查询最新远端状态，本轮未提交/推送/改名/切换分支。
- 真实差异：PracticeCenterPage 自 71ff6b4 起读取测评列表并带 courseId，d974935 仅增加 PRACTICE 过滤。教师公共题列表和学习端 system-questions API 仍存在；本地历史未找到学生公共题页面被删除证据，未连接数据库，不猜当前库存。
- 最新产品：顶部第四槽改为“题库”，课程详情增加“课程练习”并仅列本课教师发布的 PRACTICE。作业/考试继续课程内；旧链接迁移需校验归属，不能删除数据或放宽公布规则。T10C.1/T10C.2 是优先纠偏任务。
- 开工条件：上述代码与历史足够启动 T10C.1 的只读审计/契约工作；新文档尚未提交评审，当前树有规划修改，不是干净的业务开发分支。规划保存评审后，从当时最新 main 创建独立 codex/t10c1-question-bank-entry-contract；不要在当前总控分支写业务代码。
- Agent 环境：现有 BGE 文件/768 维配置已核对，未测推理；重排目标目录不存在，Qdrant 尚无健康证据，Neo4j 仅前次服务/IPv4 端口成功，未认证查询。新 agent-access-service 尚不存在，模型/数据库/事件/媒体真实验收不能标通过。

### 分阶段必须取得的证据

| 任务 | 必测边界与真实验收 |
|---|---|
| T10C.1 | 公共 SYSTEM/ACTIVE 列表/单题/图片安全、教师私有不可见；本课程 PRACTICE 过滤/发布/报名/跨课 404；旧链接归属，HOMEWORK/EXAM 时间/成绩/答案回归；不以空响应冒充无题 |
| T10C.2 | 顶栏题库无课程汇总，具体课程按钮只列本课教师发布的练习；独立题/组合/图片/筛选恢复；旧链接跳转、权限/离线/三档视口/键盘；不靠删 Seed 隐藏错误归属 |
| T13.1 | 请求/事件/终态/取消/messageId/runId、工具范围、未授权数据、候选证据与索引版本契约；实际 Schema 示例与反例测试，不只文字说明 |
| T14.1 | 独立域表、并发唯一约束与重复请求、用户/会话/媒体/课程授权、分页源和 Outbox；协议替身流中断/超时验证，不虚报真实模型接入 |
| T13.2 | 真实 Qdrant dense/BM25/重排与阈值；中文、公式、相似但错误题/无命中；跨用户/退课/旧版本/未公布答案泄漏测试；Neo4j 有界查询；重复/乱序/删除事件、DLQ及检查点恢复/备份重建 |
| T13.3 | 规则否定/含糊槽位/两个子请求/专家工具白名单；不懂三次与所有预算耗尽；多模态低清晰度/模型超时/取消；直接流不多一次无意义汇总；旧 FAISS/诊断/人工转交不再必需 |
| T14.2.1 | 内部协议向前兼容；最近消息窗口边界/顺序/部分标记；课程候选逐项按 run owner、当前版本和当前访问再次授权；Learning 事实只读授权；用量/候选记忆上报不进入浏览器事件且重复上报幂等 |
| T14.2.2 | 证据版本纠正、强弱证据、偏好优先、主题等级、衰减与保留；摘要保留公式且不复制受限正文；无聊天自述伪造掌握；记忆 Outbox、重复/乱序/tombstone、Qdrant 用户隔离和事实源重建 |
| T14.2.3 | 全链路真实会话与部分保存/刷新/显式重生成；Redis 故障、租约过期与跨实例恢复；同会话唯一运行和用户总并发/分钟日额度；用量包含提取、视觉和失败调用；180 天消息清理与媒体引用释放 |
| T13.4 | 用户帮助版本/发布与授权路由，规则和复杂问答，内部文档不泄露，未知问题不猜 |
| T15A | 顶栏/独立会话历史/图片/公式/来源/偏好，401～429/offline、部分消息、停止、重生成；超过30分钟仍能继续历史，不展示画像或人工转交 |
| T15B | 跨独立题持续生成不丢内容；每条折叠题目快照/草稿原关联；30分钟只影响悬浮自动沿用；多标签冲突、关闭取消/转完整页；手机/桌面/键盘/资源清理 |
| T14.3/T15C | 1～20主问题/默认5/数量不足/组合完整/去重；学生明确确认/过期/参数改动；超时未知结果查询/重复确认同次创建；无自动提交或评分、无正式AI生成题 |

每项记录实际命令、结果、未执行部分与注释覆盖。性能至少分解首事件/首字、检索/重排/转发延迟、调用数、队列积压、内存与成本，不凭协议名称宣称快慢。纯模型测试集使用有界代表样本，不将数万 JSON 原文送入对话。真实数据仅操作明确开发范围；保留演示数据需幂等 Initialize/Cleanup/Rebuild，临时验收数据精确清理。

本轮验证为文档差异、任务依赖、旧规则残留与链接检查；没有执行 Java/Python/前端业务测试，也未创建或修改任何数据库、表、伪数据、消息、OSS 对象、向量或图谱数据。
在仓库根目录执行：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
python -m compileall -q edu_agent_app server.py
python -m unittest discover -s tests -v
python -m pip wheel --no-deps --wheel-dir .m2/python-wheels .
pnpm.cmd --dir web-apps install --frozen-lockfile
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run check:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
```

运行入口：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl gateway-service -am spring-boot:run
python -m edu_agent_app
pnpm.cmd --dir web-apps run mock:api
pnpm.cmd --dir web-apps run dev:student
pnpm.cmd --dir web-apps run dev:teacher
pnpm.cmd --dir web-apps run dev:admin
```

Java 领域服务将模块名替换为 `iam-service`、`course-service`、`media-service`、`learning-service` 或 `operation-service`。PowerShell 以外的 shell 去掉 Maven 命令中的 `--%`。Python 完整运行前必须先执行 `python -m pip install -e .`，再按 `.env.example` 配置并确保外部模型/索引资产存在。旧版 pip（21.3 以前）执行 wheel 命令时可加 `--use-feature=in-tree-build`，避免复制整个 Monorepo。

## 2026-07-16 验证记录

- Java：`clean verify` 通过，8 个 Reactor 项目全部成功，`shared-kernel` 1 个测试通过；Gateway 与五个领域服务分别启动后 `/actuator/health` 均返回 `UP`。
- Python：字节码编译、8 个既有单元测试及 wheel 构建通过；相邻 BGE/FAISS 资产路径已确认存在。当前系统 Python 未安装 `requirements.txt` 中的运行时依赖，因此未在该基础环境重复进行完整 HTTP 启动。
- Frontend：冻结锁文件校验、OpenAPI 类型生成、全部 workspace 类型检查、1 个 Vitest 测试及三个应用生产构建通过。
- Mock：Prism 成功加载同一 OpenAPI；登录与课程列表请求返回契约示例响应。

## T02 基础设施说明

- CI 使用 JDK 17、Python 3.9、Node 20.19.5 和 pnpm 10.22.0，分别缓存 Maven、pip 和 pnpm 依赖。
- Java 默认构建不激活 `local` profile；本地 MySQL/Redis 只读验证和 Actuator 检查见 [`docs/development/local-environment.md`](../development/local-environment.md)。
- `pnpm.cmd --dir web-apps run check:api` 会重新生成 OpenAPI 类型，并在生成文件相对 Git 存在差异时返回失败。
- Python CI 只编译、运行纯单元测试并构建 `--no-deps` wheel，不安装运行时依赖，也不下载模型或 FAISS 索引。

## 2026-07-16 T02 验证记录

- Java：JDK 17 下 `clean verify` 通过，8 个 Reactor 项目全部成功，`shared-kernel` 1 个测试通过；IAM 未启用 profile 时保持无外部依赖启动且健康状态为 `UP`，Gateway 以 `local` profile 启动后 `/actuator/health` 返回 `UP`，Redis 组件为 `UP`。
- Python：`compileall`、8 个既有单元测试和 wheel 构建通过。当前系统 pip 较旧，实际 wheel 命令增加了 `--use-feature=in-tree-build`；构建隔离只下载 setuptools，不安装运行时依赖，也未读取模型或索引资产。
- Frontend：pnpm 10.22.0 冻结安装、OpenAPI 生成一致性、全部 workspace 类型检查、1 个 Vitest 测试和学生/教师/管理三端生产构建通过；`esbuild` 原生脚本许可可执行。
- 本地基础设施：MySQL 3306 与 Redis 6379 TCP 连通，`redis-cli PING` 返回 `PONG`；Gateway Actuator 验证了 Redis。当前进程未提供 MySQL 凭据，因此没有执行认证后的 `SELECT 1` 或领域服务数据库健康检查，也没有读取或修改任何现有数据。
- CI 配置：`.github/workflows/ci.yml` 和全部 local YAML 已通过 YAML 解析，模块 POM 已通过 XML 解析；实际 GitHub-hosted runner 结果需在分支推送后观察。

## 2026-07-17 T04 验证记录

- 契约：OpenAPI 0.2.0 已定义登录、刷新、退出和当前用户，生成的 TypeScript 类型已更新；Prism 成功加载 6 条路由，登录 Mock 返回 200、Access Token 和 HttpOnly Refresh Cookie。
- Java：`clean verify` 的 8 个 Reactor 模块全部成功；Gateway 共 6 个测试通过（含 CORS 预检），IAM 共 9 个测试通过，覆盖 BCrypt 登录、错误密码、禁用账号、失败限流、Refresh 过期/轮换/复用、退出、Flyway/MyBatis 和 Seed 幂等清理。
- 数据库：测试进程继续使用 H2 MySQL 模式的 `iam_test` 与 `iam_seed_test`。本地人工验收另外创建了用途明确的 `edu_platform_iam_dev`，Flyway V1 成功且只创建 7 张 IAM 业务表；显式启用 local Seed 后幂等生成 `student_test_01`、`teacher_test_01`、`admin_test_01` 及三组最小角色权限。未读取、修改或删除本机其他既有数据库；清理方式为 IAM Seed 专用清理脚本。
- Frontend：pnpm 10.22.0 冻结安装、OpenAPI 生成一致性、全部 workspace 类型检查、15 个 Vitest 测试和学生/教师/管理三端生产构建通过。统一登录测试覆盖单角色自动分流、多角色工作台选择、安全 redirect、匿名教师/管理端回跳、403 工作台入口、单次 401 刷新和跨应用 Refresh Cookie 恢复。
- Python：源码字节码编译和 8 个既有单元测试通过；旧版 pip 使用 `--use-feature=in-tree-build` 后 wheel 构建成功，未修改 Python Agent 源码。

## 2026-07-30 T05 验证记录

- 契约：OpenAPI 0.3.0 已兼容扩展学生课程筛选，并新增教师课程创建、编辑、完整目录替换和发布状态接口；TypeScript 客户端成功重新生成。Prism 成功加载契约，学生课程列表和携带 Bearer 头的教师创建 Mock 均返回 200。
- Java：`clean verify` 的 8 个 Reactor 模块全部成功。Gateway 8 个测试通过，覆盖公开课程路由、教师角色拒绝/放行、可信身份头和 PUT CORS；Course 8 个测试通过，覆盖 Flyway/MyBatis、HTTP 契约、列表筛选分页、详情、所有权、乐观并发、幂等重放、不可变发布版本、上下架和 Seed 清理。
- 数据库：自动化测试只使用 H2 MySQL 模式的 `course_test` 与 `course_seed_test`，Flyway 创建 `category`、`course`、`course_version`、`chapter`、`lesson` 并验证固定课程 Fixture 可重复生成和定向清理。本机 MySQL 3306 可连通且客户端可用，但当前进程未配置 `MYSQL_USERNAME`、`MYSQL_PASSWORD` 或 `COURSE_MYSQL_DATABASE`，因此没有创建或修改 `edu_platform_course_dev`，也没有读取或触碰任何现有 Schema。
- Frontend：OpenAPI 客户端生成、全部 workspace 类型检查、15 个 Vitest 测试和学生/教师/管理三端生产构建通过。Vitest/Vite 的 esbuild 子进程在受限沙箱内首次返回 `EPERM`，改在获准的沙箱外执行后通过。
- 边界：本轮未创建媒体、题库、报名、进度、答题、热点运营或 Agent 表/接口；Course 只保存教师用户 ID 和课程讲师展示文案，不查询 IAM 表、不建立跨 Schema 外键。

## 2026-07-31 T05 真实 MySQL 人工验收记录

- 环境：用户确认目标连接严格为课程专用 `edu_platform_course_dev`，通过 Windows DPAPI 本地凭据工作流启动服务；Flyway V1 创建 `category`、`course`、`course_version`、`chapter`、`lesson` 以及技术表 `flyway_schema_history`，没有创建其他领域业务表或跨 Schema 外键。
- Seed：固定“初中数学”分类、“七年级代数基础”已发布 `v1`、一个“有理数”章节和一个可试看 `VIDEO` 课时生成成功；重复初始化未产生重复 Fixture，定向清理后仅移除固定课程数据，重新启用 Seed 可恢复。
- 学生链路：经 Gateway 匿名课程列表、关键词/分类/难度/访问类型筛选、分页和固定课程详情符合 OpenAPI；只展示已发布版本和课程目录，不返回媒体地址、题目、报名或学习进度数据。
- 教师链路：T04 `teacher_test_01` 经 Gateway 完成幂等创建、元数据编辑、完整目录替换、发布、已发布版本隔离、新版本再次发布、过期 revision 冲突和下架；相同 `clientRequestId` 重放返回同一课程且不增加记录。
- 权限：匿名教师接口返回 401，学生角色返回 403，教师角色正常放行；课程所有权失败返回不可枚举的 404。错误响应包含统一错误信封字段。
- 证据边界：以上真实 MySQL 和 API 结果由用户于 2026-07-31 按交付文档步骤人工验收并报告完成；Codex 未读取或输出本机凭据，也未在文档更新过程中重新访问数据库。自动化证据仍由 Maven、OpenAPI 和前端 CI 提供。
- 合并：PR [#4](https://github.com/chengchen-ccnu/ccnu-edulion/pull/4) 在最新 CI 全部通过后以 merge commit `0f2cf6ec800402c033d9b4fee33e18399e59afc7` 合入 `main`。

## 2026-08-01 T06 验证记录

- 契约：OpenAPI 0.4.0 新增启用课程分类和教师本人课程分页列表，修正学生权限示例；生成 TypeScript 客户端成功，`check:api` 确认生成结果与提交一致，前端没有维护手写重复 DTO。
- Mock：Prism 成功加载 0.4.0，启用分类和携带 Bearer 头的教师课程列表均返回 200。
- Java：完整 `clean verify` 的 8 个 Reactor 模块全部成功；Gateway 8 项、IAM 9 项、Course 8 项、shared-kernel 1 项测试通过。Course 覆盖启用分类、教师所有权分页、HTTP 契约以及既有课程创建/筛选/详情/编排/发布链路。
- Frontend：全部 workspace 类型检查通过；4 个 Vitest 文件共 20 项测试通过；学生、教师、管理三端生产构建通过。受限沙箱内 Vitest/Vite 首次因 `esbuild spawn EPERM` 失败，获准在沙箱外复跑后通过。
- 功能：学生端完成分类/关键词/难度/访问方式筛选、URL 状态、分页、详情与目录；教师端完成本人列表、幂等创建、元数据编辑、章节课时顺序编排、发布、重新发布和下架。
- 权限：教师领域请求复用 T04 内存 Access Token，并在 401 时通过统一认证层刷新和重试一次；路由继续校验角色和最小工作台权限，页面不保存 Token。
- 数据：没有新增迁移、持久数据库、表或 Seed；自动化测试只在 H2 内存 `course_test`/`course_seed_test` 中重建 T05 表和最小合成课程数据，进程退出后释放。
- 人工验收：用户于 2026-08-01 确认按 T06 交付文档完成学生课程列表/筛选/分页/详情目录、教师课程创建/编辑/编排/发布/重新发布/下架、统一登录权限以及桌面和窄屏布局验收；真实 Gateway/MySQL 端到端流程由用户确认通过。
- 证据边界：人工验收结果来自用户确认；Codex 未重新访问本机数据库、未读取或输出本地凭据，也未采集人工验收截图或浏览器日志。自动化证据仍由 Maven、OpenAPI、Vitest、类型检查、生产构建和 GitHub CI 提供。
- 发布：PR [#6](https://github.com/chengchen-ccnu/ccnu-edulion/pull/6) 已合并，`main` 中对应 merge commit 为 `c293d9c`。

## 2026-08-01 OSS 阶段性占位设计审计

- 范围：仅更新 README、架构、ADR、任务拆分、backlog、T06 交付说明、本地环境说明和仓库规则；未修改 OpenAPI 当前版本、生产代码、数据库迁移或生成客户端。
- 盘点结果：课程封面/视频/课件、头像/教师资质、题目图片/音频/附件与导入源文件、AI 提问图片、运营 Banner/专题封面和报表导出均已映射到 T07/T08/T09/T10/T14/T15/T16/T17/T18 的明确收口任务。
- 关键决策：T07 提供唯一通用 OSS 媒体后端；领域服务只保存稳定资产 ID，展示 URL 为授权投影；小文件单对象直传、大文件 Multipart 直传，私有对象短期授权，未绑定/过期对象延迟回收。
- 文档验证：执行 `git diff --check`，并使用 `rg` 检查 `coverUrl`、`avatarUrl`、上传、附件、导入/导出、Banner 和 OSS 相关描述是否存在未映射占位。文档任务没有运行 Java、Python 或前端构建，不能把本次结果作为 T07/T08 功能已实现的证据。
- 数据：没有连接、创建或修改任何数据库、表、OSS Bucket 或伪数据。

## 2026-08-01 T07 验证记录

- 契约：OpenAPI 0.5.0 定义上传会话、单对象与 Multipart 授权、完成/取消、资产分页与详情、处理状态和短期访问授权；`media-events.v1.yaml` 冻结媒体事件信封、版本、路由键和引用台账语义。TypeScript 客户端重新生成并通过全 workspace 类型检查。
- Java：完整 `clean verify` 的 8 个 Reactor 模块全部通过；Gateway 9 项测试包含媒体路由、认证头和 DELETE CORS 边界，media-service 的 5 项 H2 集成测试覆盖单对象、Multipart、用途/角色策略、请求幂等、引用事件台账和回收。真实环境测试显式启用后另有 1 项通过。
- RabbitMQ：在本机 RabbitMQ 4.3.4 默认 vhost 上通过 Spring AMQP 实际验证持久消息发布、publisher confirm、return、消费后确认、有限退避重试、超限死信、`eventId` 持久化幂等、未确认消息重投以及 Broker 不可用时 Outbox 保留。资源名为 `edu.media.events.v1`、`edu.media.dlx.v1`、`edu.media.processing.v1`、`edu.media.references.v1`、`edu.media.lifecycle.v1` 和 `edu.media.dlq.v1`。
- OSS：测试只在专用 Bucket 的唯一 `t07-acceptance/<runId>/` 前缀生成单对象、Multipart 原件和 FFmpeg 派生对象；验证真实预签名直传、HEAD 元数据复核、短期访问和处理链路后，按已知精确 Object Key 删除，未列举、覆盖或删除其他对象。
- FFmpeg：由测试运行时合成最小视频，实际经过可替换 Worker 边界执行 MP4 派生，验证 `PENDING/RUNNING/SUCCEEDED` 主链路及失败/取消状态模型；合成输入、临时目录和转码产物均未提交。
- 数据：创建且仅操作 `edu_platform_media_dev`。Flyway V1 创建 10 张 media-service 业务/技术表和 `flyway_schema_history`；真实验收使用固定测试 owner 与每次唯一请求/事件标识，结束后逐表断言 10 张数据表均为 0。H2 自动化数据库随测试进程销毁。
- Frontend：`generate:api`、全 workspace `typecheck`、4 个 Vitest 文件共 20 项测试和学生/教师/管理三端生产构建通过。Prism 成功加载完整 0.5.0 契约，媒体分页 GET 返回 200；媒体 POST 的 PowerShell 客户端请求在工具层超时且 Prism 未收到请求，因此不记录为 Mock 成功。
- Python：`compileall`、8 项既有单元测试和 `--no-deps` wheel 构建通过，未修改 Python Agent 实现。
- 安全与边界：没有读取或输出本地凭据，没有提交签名 URL、OSS 对象、转码产物或大型二进制文件；Gateway 和领域服务不代理文件字节，也没有接入 OSS SDK。当前首期只启用 `COURSE_COVER` 与 `LESSON_VIDEO`，外部恶意文件/内容审核服务尚未接入，生产开放前必须补齐。

## 2026-08-02 T01～T07 注释与人工可读性审计

- 范围：从与 `origin/main` 一致且包含注释规范提交与 T07 PR #9 的 `main` 创建 `codex/t01-t07-comment-audit`；使用 `rg --files` 排除 `target`、`dist`、`node_modules`、自动生成客户端和二进制资产后，按 Java、Python、TypeScript/Vue、SQL/OpenAPI/事件 Schema 和复杂测试审计。
- 行为边界：差异仅包含 Javadoc、中文 docstring、TSDoc/JSDoc、Vue 脚本职责说明、SQL/YAML/OpenAPI description、合法重新生成的 OpenAPI 类型和审计文档；没有修改接口字段、数据库结构、状态机、权限、算法、依赖或页面交互。
- Java：`mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 通过，8 个 Reactor 模块全部成功；自动化共运行 33 项，其中 32 项通过，显式依赖真实环境的 1 项媒体验收测试跳过。
- Python：`python -m compileall -q edu_agent_app server.py` 通过；`python -m unittest discover -s tests -v` 的 8 项检索和工作流策略测试全部通过。没有启动依赖外部模型、FAISS 或 Neo4j 的完整 Agent 服务。
- Frontend：`generate:api` 生成包含新增 operation description 的 `schema.d.ts`；提交后复跑 `check:api` 通过，全 workspace `typecheck` 通过。Vitest/Vite 在受限沙箱内因 `esbuild spawn EPERM` 失败，获准在沙箱外复跑后 4 个文件 20 项测试和学生/教师/管理三端生产构建全部通过。
- 数据与外部系统：没有连接或修改真实 MySQL、Redis、OSS、RabbitMQ、Neo4j 或外部模型；没有创建数据库、业务表或伪数据。Maven 测试仅使用随测试进程销毁的 H2 内存 Schema/Fixture。

## 2026-08-02 T06 信息架构与统一登录修复

- 门禁：开始时工作区干净、当前分支为 `main`；显式 fetch 后本地 `main` 与 `origin/main` 均为 `b099af8bac553b28f6ba599831714de06317136e`，双向差异 `0/0`，随后创建 `codex/t06-course-navigation-auth-fix`。
- 功能：`/courses` 与 `/courses/:courseId` 不再要求 `STUDENT` 或 `learning:access`；匿名、学生、教师、管理员均可浏览公开线上版本。公共页只后台尝试一次会话恢复，失败不阻塞、不跳登录、不循环刷新。
- 导航：student-web 将公共入口统一为“课程中心”，统一登录壳使用角色中立文案；teacher-web 使用共享平台 URL 配置跳转课程中心，并将本人课程入口命名为“教学管理”，学生身份不会看到该入口。`/api/v1/teacher/courses`、Gateway 教师鉴权和课程所有权边界未修改。
- 安全：共享 redirect 白名单区分公共目标与受保护工作台；教师登录可安全回跳 `/courses`，教师端/管理端仍校验角色权限，外部 URL、协议相对 URL、反斜杠、控制字符及未知路径继续拒绝。
- 自动化：`pnpm.cmd --dir web-apps run typecheck`、`check:api` 均直接通过；Vitest 在获准的沙箱外复跑后 6 个文件 34 项全部通过；三端生产构建在获准的沙箱外复跑后全部通过。沙箱内 Vitest/首次构建失败原因均为 Vite/esbuild 子进程 `spawn EPERM`，不是代码或测试失败。
- 契约与后端：OpenAPI 生成结果与 `HEAD` 一致，没有修改契约、生成 DTO、Java、Gateway 或 course-service，因此本次未运行 Maven/Prism。
- 数据：没有连接或修改数据库，没有创建数据库、业务表、迁移、Seed 或伪数据；T11 才实现报名/进度和教师作为学习者的能力模型，T12 才实现有真实数据支撑的“我的课程（我的学习）”。
- 发布：关键实现提交为 `a7b78173e5f244b8239e7d92c2fdef093c695fd8`；Ready for review PR [#12](https://github.com/chengchen-ccnu/ccnu-edulion/pull/12) 已创建，目标为 `main`，未自行合并。

## 2026-08-02 T08A 课程媒体契约、领域绑定与处理能力

- 门禁：开始时工作区干净且当前分支已经是 `codex/t08a-course-media-contract-binding`；显式 fetch 后 `HEAD`、本地 `main`、`origin/main` 均为 `eb17ed228efb1779ffc5761e18e43d1f2733f339`，双向差异 `0/0`。Git 历史和 GitHub 合并状态确认包含 T07 PR #9、注释审计 PR #11、导航修复 PR #12；没有重复创建或切换分支。
- 契约：先将 OpenAPI 更新到 0.6.0，再更新 `media-events.v1.yaml` 和两个向前 V2。课程写请求只接受稳定 `coverMediaId`/课时资源 ID，读取保留可空历史 `coverUrl`；新增 media-service 所有权、精确用途和 READY 绑定校验。TypeScript 客户端由 OpenAPI 成功重新生成，没有手写重复 DTO。
- Course：版本级封面和课时资源只保存稳定媒体 ID；绑定前调用 media-service JSON API，不跨库。创建、复制草稿、替换和解除在课程事务中写媒体引用 Outbox，mandatory + publisher confirm + 有限退避发布既有 Bound/Released v1。12 项课程测试全部通过，覆盖合法/非法绑定、幂等、历史 URL 和已发布版本隔离。
- Media：显式启用六种课程用途，其余冻结枚举保持关闭；视频 Worker 以默认单并发和单作业超时生成私有 `hls-v1` 清单/切片，失败删除已知部分产物且始终清理临时目录。授权动态重写切片短期 URL 到临时清单，到期按精确键回收；数据库不保存签名 URL。10 项 Fake 环境媒体测试通过，覆盖 HLS 成功/失败/重试/失败事件、授权、清理、用途策略和引用事件幂等。
- Java：`mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 的 8 个 Reactor 模块全部成功；13 个报告共 42 项，41 项通过、0 失败、0 错误，真实环境媒体用例 1 项按开关跳过。
- Frontend：`pnpm.cmd --dir web-apps run typecheck` 通过；Vitest 和 Vite 在受限沙箱内因 esbuild `spawn EPERM` 无法启动，批准在沙箱外复跑后 6 个文件 34 项测试和学生/教师/管理三端生产构建全部通过。主提交后复跑 `check:api`，生成文件与 OpenAPI 一致且已跟踪。
- 数据：只新增 Course V2（`cover_media_id`、`lesson_media_resource`、课程 `outbox_event`）与 Media V2（`media_variant_component`、`temporary_access_object`）。没有连接、创建或修改真实 MySQL/OSS/RabbitMQ，没有新增 Seed 或伪数据；自动化只使用随进程释放的 H2 `course_test`、`course_seed_test`、`media_test` 随机最小 Fixture。
- 真实环境：分支自动化默认跳过 `MediaRealEnvironmentAcceptanceTest`；用户随后于 2026-08-02 按教程完成并确认真实 OSS、RabbitMQ、FFmpeg HLS、RabbitMQ confirm/return/重试/DLQ/重投和精确清理通过。
- 注释审计：新增和修改的业务类、公共边界、复杂私有方法、Outbox、FFmpeg、授权/清理逻辑、V2 SQL、OpenAPI、事件 Schema 与复杂测试已补中文说明；生成客户端未手工修改注释。最终 `git diff --check` 通过。
- 迁移 checksum 修正：人工验收后发现 PR #11 曾向已执行的 IAM/课程 V1 增加说明性 SQL 注释。工作树已恢复为 T04 提交 `6462bc4a` 与 T05 提交 `323f89d7` 中的原始 blob，`git hash-object` 与原提交对象 ID 分别精确相等；被移除的业务说明迁移到对应持久化包 `package-info.java`，不改变表结构或运行行为。修正后重新执行 Java 完整 `clean verify`，8 个 Reactor 模块全部成功，真实环境媒体用例 1 项按开关跳过。
- 人工验收：用户于 2026-08-02 确认按 T08A 教程完成验收并报告通过；该结论包含用户执行的真实环境与业务链路结果，Codex 未读取或输出本地凭据、签名 URL 或真实业务数据。
- 发布：主提交为 `2f2697882394a5194a48e1756c8d05cd1a3780ea`；分支按规则使用 `git push origin HEAD` 推送。[PR #13](https://github.com/chengchen-ccnu/ccnu-edulion/pull/13) 已合并，`main` 的 merge commit 为 `55b30d52493e84038da59f7a6789e43b8de6e716`。

## 2026-08-05 T08A.1 课程媒体播放授权闭环

- 门禁：开始时分支为 `codex/t08a1-course-media-playback-auth` 且工作区干净；显式 fetch 后 `HEAD` 与最新 `origin/main` 都为 `55b30d52493e84038da59f7a6789e43b8de6e716`，即已合并 PR #13 的主线。T08A 真实 OSS/RabbitMQ/FFmpeg HLS 人工验收通过记录已核实并修正文档旧状态。
- 契约：OpenAPI 先更新到 0.7.0，新增发布课程资源播放授权与教师 owner 预览授权。外部请求只携带 courseId/lessonId/resourceId，不接受 mediaId、用途或 `allowAccess`；生成 TypeScript 客户端同步更新。
- Course：只从当前发布版本解析公开播放绑定，PUBLIC 或试看课时允许；报名制非试看在 T11 前返回 `COURSE.ENROLLMENT_REQUIRED` 403。教师接口只解析 owner 的当前草稿/发布版本。草稿、下架、归档/历史版本、错课时、错资源和非 `LESSON_VIDEO` 均不可见。
- 服务认证：course-service 签发 30 秒 HMAC JWT，绑定 issuer/audience、访问者、mediaId、用途、访问类型、变体、处置方式、traceId、iat/exp 与 jti。Gateway 不路由 `/internal/**`；media-service 验签并逐项比对，以 Media V3 `consumed_service_authorization` 唯一 jti 拒绝重放，再复核精确用途和 READY。密钥通过 DPAPI/CI Secret 注入，不复用 IAM JWT 密钥。
- Java：`mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 的 8 个 Reactor 模块全部成功；13 个报告共 46 项，45 项通过、1 项真实环境用例默认跳过，0 失败/错误。新增测试覆盖 owner 预览、公开/试看、T11 阶段性 403、草稿/历史/错绑定、学生 owner 直调拒绝、伪造/过期/错 audience/访问者/mediaId/用途/变体、重放、非 READY 和 Gateway 边界。
- 真实环境：通过 `Invoke-WithLocalEnvironment.ps1` 显式执行 `MediaRealEnvironmentAcceptanceTest`，1 项通过。`edu_platform_media_dev` 由 V2 向前迁移到 V3；真实 OSS 单对象/Multipart、FFmpeg HLS、owner 与服务令牌两条授权清单/分片读取、RabbitMQ confirm/return/有限重试/DLQ/重投通过。测试结束按唯一前缀/精确键删除 OSS 对象，并断言所有验收数据表（含防重放表）为 0。
- Frontend：`generate:api` 和全 workspace `typecheck` 通过；沙箱内 Vitest/构建因 Vite/esbuild `spawn EPERM` 无法启动，批准在沙箱外复跑后 6 文件 34 项测试和学生/教师/管理三端构建全部通过。`check:api` 提交前正确检测到生成文件相对 HEAD 的待提交差异，提交后复跑。
- 数据：没有创建数据库、Course 表、Seed 或正式报名数据。只在既有专用 `edu_platform_media_dev` 应用 Media V3；真实验收使用固定测试 owner/visitor 与唯一 OSS 前缀并精确清空。H2 `course_test`、`course_seed_test`、`media_test` Fixture 随测试进程释放。
- 边界：T08B 才实现上传/播放器/授权刷新；T11 才实现报名与学习权限。首期完整开放 `LESSON_VIDEO` 播放，封面维持展示投影，字幕/课件不提前堆积未评审下载授权。
- 发布：实现提交为 `f2e1d972e828759089d2192227366ddb4985059b`，已按规则使用 `git push origin HEAD` 推送；Ready [PR #14](https://github.com/chengchen-ccnu/ccnu-edulion/pull/14) 随后已合并，`main` 合并提交为 `2f40b762d41f780509ab388928aff84614d6b62c`。

## 2026-08-05 T08B 课程媒体上传与播放前端

- 门禁：开始时分支为 `codex/t08b-course-media-web`、工作区干净；显式 fetch 后 `HEAD` 与最新 `origin/main` 都为 PR #14 merge commit `2f40b762d41f780509ab388928aff84614d6b62c`，确认 T08A.1 已进入主线。
- 契约：继续使用 OpenAPI 0.7.0 和生成客户端，没有新增/猜测字段。教师上传只调用 Gateway 媒体会话 JSON API，字节直传短期 OSS URL；课程写只提交稳定媒体 ID。学生与教师分别只调用课程域发布资源/owner 预览授权接口。
- 上传：封面与视频共用教师端状态机，覆盖分块 SHA-256、SINGLE_PUT、Multipart partSize/逐片授权/实际 ETag、3 路并发、有限重试、取消、服务端会话恢复提示，以及 UPLOADED/VERIFYING/PROCESSING/READY/FAILED/REJECTED/超时反馈。通用处理状态按用途解释，只有课时视频显示 HLS 转码；会话/资产用途错配会在 OSS 传输或绑定前被拒绝。只有 READY 才回写课程表单或目录资源。
- 播放：原生 HLS 优先，其他浏览器按需加载 `hls.js 1.6.16`；按 expiresAt 到期前刷新，429 最多退避两次，失败后停止自动循环。切换与卸载清理 HLS、video source、定时器和请求；授权 URL 不进入 localStorage、Pinia、日志或业务请求。
- Frontend：冻结安装、`generate:api`、`check:api`、全 workspace `typecheck` 通过。2026-08-08 状态语义复核后，Vitest 沙箱内因已知 esbuild `spawn EPERM` 失败，获准沙箱外复跑后 8 文件 51 项通过；三端生产构建在沙箱外通过，hls.js 为按需 chunk。共享播放器的 403 保持角色中立，学生报名和教师 owner 提示分别留在各自页面。
- Java：受影响的 Gateway/Course/Media Reactor 验证通过，5 个模块 SUCCESS；shared-kernel 1 项、Gateway 10 项、Course 14 项、Media 12 项中仅默认关闭的真实环境 1 项跳过，0 失败/错误。
- 真实环境：首次调用被 Windows 执行策略阻止，未触达外部系统；随后使用一次性 `-ExecutionPolicy Bypass` 运行同一仓库包装脚本，`MediaRealEnvironmentAcceptanceTest` 1 项通过。既有 `edu_platform_media_dev` V3、真实 OSS 单传/Multipart、RabbitMQ confirm/return/重试/DLQ/重投与 FFmpeg HLS 通过，测试结束精确清理对象、消息和记录并逐表归零。
- 浏览器：OpenAPI Mock 下学生课程详情在 1440×900 与 390×844 无横向溢出，窄屏单列正常且控制台无错误。由于 Mock 详情不含视频资源、教师页需要真实登录，本次没有执行或宣称真实 Vue 上传—绑定—学生 HLS E2E。
- 数据：没有新增迁移、数据库、表、Seed、正式伪数据或报名记录。真实验收只复用既有专用 media dev Schema 和唯一 OSS 前缀并完成清理；H2 Fixture 随测试进程释放。
- 安全与可读性：新增人工维护代码已补中文说明；检查点不保存签名 URL/Token，源代码无 AccessKey/密码，课程目录不提交存储地址。最终差异、`git diff --check`、构建产物和敏感信息审计在提交前复核。
- 发布：分支 `codex/t08b-course-media-web`，关键实现提交 `4b6e524cae2fbeb2dac886147b7f9c342a72c393`；Ready [PR #15](https://github.com/chengchen-ccnu/ccnu-edulion/pull/15) 已创建，未自行合并。
- 2026-08-08 封面展示复核：教师列表改为固定封面容器与两列内容布局，无封面/加载失败不会再把课程信息挤入封面列；学生列表和详情同步提供稳定占位与单图片失败降级。course-service 对稳定 `coverMediaId` 通过一次性服务 JWT 申请 `COURSE_COVER_DISPLAY`/`ORIGINAL` 原图投影，media-service 精确校验用途、访问类型、变体、处置方式和防重放 jti，短期 URL 只进入响应。Course 14 项、Media 12 项、shared-kernel 1 项自动化通过，真实环境 1 项默认跳过；全 workspace `typecheck`、前端 8 文件 51 项和三端生产构建通过。浏览器控制运行时初始化失败，因此本轮没有宣称真实视觉或真实 OSS 页面链路已复验。
- 目录操作反馈复核：保存成功后用明确状态替代灰色保存按钮，继续编辑时重新显示保存操作；发布禁用原因在按钮旁可见。前端发布条件已与 course-service 对齐为“至少一章且每章至少一课时”，不再把 VIDEO 课时必须先绑定媒体作为额外发布门槛；未 READY 资产仍不能进入资源绑定。
- 发布操作状态复核：修复 `PUBLISHED + hasUnpublishedChanges` 被“下架课程”分支提前截获的问题。已发布课程替换/移除视频并保存后，同时提供“发布新版本”和“下架课程”；无草稿差异的线上版本只显示下架，未修改的已下架版本可实际触发“重新发布”。教师列表和目录页共用同一纯状态映射，新增 6 项测试覆盖线上新草稿、无差异线上版本、重新发布及目录完整性边界；全 workspace `typecheck`、9 文件 57 项测试和三端构建通过。教师 Vite 服务可启动，但浏览器控制运行时初始化失败，未宣称真实页面点击已复验。
- HLS 音频复核：此前 T07/T08 真实验收只用 lavfi 合成画面，没有生成或断言音频流，因此即使 Worker 使用 `-an` 也会假通过。本次删除 `-an`，改为必选 `0:v:0`、可选 `0:a:0?`，存在音轨时编码为 AAC 128 kbit/s、48 kHz 双声道，仍保持单清晰度 MPEG-TS HLS；命令测试明确断言可选映射、AAC 参数及不存在 `-an`。
- 带声音真实验收：通过安全环境包装脚本连接既有 `edu_platform_media_dev`、真实 OSS、RabbitMQ 与本机 FFmpeg/ffprobe，lavfi 输入同时包含 video 和 1 kHz 正弦波 audio；ffprobe 先确认输入双流，再确认授权下载的首个 TS 切片含 video 与 AAC audio。输入和输出均由 FFmpeg `volumedetect` 解码并断言平均音量高于 `-60 dB`，防止静音 AAC 假阳性。无音轨 MP4 也成功生成可探测的 m3u8/TS；原有 Multipart、短期授权、切片下载、confirm/return、有限重试、DLQ 和重投继续通过。
- 音频修复验证与清理：Java 完整 `clean verify` 的 8 个 Reactor 模块全部成功，media-service 14 项中真实环境 1 项默认跳过；显式真实用例 1 项通过。用例按精确 Object Key 删除并 HEAD 确认本轮 OSS 对象不存在，按 uploadId/mediaId/eventId 断言本轮 MySQL 记录为零，断言四个持久 RabbitMQ 队列消息数为零，并删除所有本地合成/下载/探测/无音轨转码临时文件。开发 Schema 中其他测试 owner 的既有记录不属于本轮，未修改；没有新增数据库、迁移、表、Seed、报名或业务伪数据。

## 2026-08-09 平台页面规格与后续任务边界审计

- 门禁：先确认 T08B 分支工作区干净，`HEAD` 与 `origin/codex/t08b-course-media-web` 均为 `a128dd07dca004df96ce25504673e98a8bec4bf2`，已有音轨修复和验收提交均已推送；随后保留该分支，从与 `origin/main` 一致的 PR #14 合并提交 `2f40b762d41f780509ab388928aff84614d6b62c` 创建独立文档分支 `codex/platform-page-architecture`。
- 审计发现：总体架构原本区分 `/courses/:courseId` 课程详情与 `/learn/:courseId/:lessonId` 学习空间，但仓库缺少实施指南中约定的 `docs/frontend/page-specs.md`；功能任务因没有页面事实源，曾把正式播放器内嵌到公共详情。学生首页、教师首页和管理端占位路由也没有统一的“计划/完成”判定。
- 页面事实源：新增 `docs/frontend/page-specs.md`，冻结公共课程门户、学习空间、教师工作台和管理端的路由、职责、能力授权、页面状态及 Txx 归属。课程详情不得承载正式播放器、进度、作答或 AI；学习页从 T08B 建立媒体壳，T12/T15 只在稳定路由中扩展。
- 任务治理：T12 拆为学习进度、测评闭环和教师学情，T15 拆为独立答疑和学习上下文答疑，T17 拆为学生门户、教师工作台和运营管理端；T09/T10/T11/T13/T14/T16 的领域边界保持不变。`AGENTS.md` 要求所有前端任务先读页面规格，禁止用假数据、无效按钮或永久 `PlaceholderPage` 冒充产品完成。
- 范围：本次只修改 Markdown 规格、仓库规则和路线图，不修改 OpenAPI、事件、Java/Python/TypeScript/Vue 生产代码、数据库迁移或生成客户端。文档只记录 PR #15 处于整改验收中，不把未合并的 T08B 实现描述成 `main` 能力。
- 验证：执行 Markdown 链接/关键术语审计与 `git diff --check`；由于没有修改代码、契约或构建配置，不运行 Maven、Python 或前端构建，不能把本次文档审计作为任何功能测试通过的证据。
- 数据与外部系统：没有连接或修改 MySQL、Redis、OSS、RabbitMQ、FFmpeg、Neo4j 或外部模型；没有创建数据库、表、迁移、Seed、伪数据或外部对象，因此没有数据清理步骤。

## 2026-08-09 T08B 页面规格收口

- 合并门禁：工作区最初干净但停在 `main`；确认 `main` 为 `a642d7ea98396ef1da6da98047556f1773c2e0ea` 后切回既有 `codex/t08b-course-media-web`，其本地/远端 HEAD 均为 `a128dd07dca004df96ce25504673e98a8bec4bf2`。使用 merge 而非 rebase 合入 `origin/main`，merge commit `8798eee0bbdf3167d6817857da8f4c71bb7ba0e4`；冲突保留了总控页面事实源及 T12A～C、T15A～B、T17A～C 设计，也保留 T08B 实际测试、音频与清理记录。
- 页面边界：实现提交 `9588409a79089407499a01edae6eee5917021744` 删除公共 `/courses/:courseId` 中的播放器和授权调用，只保留课程信息、目录预览和开始学习/试看入口；新增受保护 `/learn/:courseId/:lessonId`，包含播放器、当前课时、可折叠目录、上一课、下一课和退出学习。没有展示报名成功、进度、笔记、练习或 AI 占位。
- 权限与恢复：学习路由按 `learning:access` 能力而不是固定 `STUDENT` 角色保护；统一登录白名单保留合法 `/learn/...` 深链接，匿名访问先登录，能力不足进入 403。刷新和参数切换重新读取当前发布课程，未知课时或缺少 `LESSON_VIDEO` 返回明确 404；报名制课程在 T11 前只从详情提供试看入口，强行访问非试看时由课程域 403 决定。
- 播放生命周期：学生仍只调用课程域资源播放授权，教师 owner 路径未改；共享播放器继续原生 HLS 优先/hls.js 降级、按 expiresAt 提前刷新、429 最多退避两次，失败后不再自动循环。测试覆盖 401/403/404/409/429/503 文案；组件没有 `muted`，切换/卸载中止授权请求、清除定时器、销毁 HLS 并移除 video source。
- 前端：`pnpm.cmd --dir web-apps install --frozen-lockfile`、`generate:api`、`check:api`、全 workspace `typecheck` 均通过；Vitest 在批准的沙箱外执行，10 个文件 66 项全部通过；学生、教师、管理三端生产构建通过，HLS 仍为按需 chunk。OpenAPI 继续为 0.7.0，生成客户端无差异。
- Java：完整 `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 的 8 个 Reactor 模块全部成功；14 份报告共 48 项，47 项通过、真实环境用例默认跳过 1 项。`FfmpegCommandRunnerTest` 继续确认不存在 `-an`，包含 `0:a:0?`、AAC 128 kbit/s、48 kHz、双声道和 MPEG-TS HLS；本轮没有修改 Java 生产代码。
- 浏览器：OpenAPI Mock 下匿名学习深链接正确回到统一登录并保留完整目标；课程详情 DOM 的播放器数量为 0，390×844 窄屏 `scrollWidth` 与 `clientWidth` 同为 375，控制台无错误。Mock 详情没有视频资源且 Mock 登录无法建立有效会话，因此没有宣称真实学习页 HLS 或上传 E2E；真实带声音 OSS/RabbitMQ/FFmpeg 证据沿用此前已记录并精确清理的验收。
- 数据与清理：本轮没有连接或修改 MySQL、OSS、RabbitMQ，没有创建数据库、表、迁移、Seed、报名、进度或业务伪数据。临时 OpenAPI Mock、Vite 进程与浏览器页签已关闭；Maven H2 Fixture 随进程释放，构建产物均为忽略项。
- PR：使用规定的 `git push origin HEAD` 更新现有 Ready [PR #15](https://github.com/chengchen-ccnu/ccnu-edulion/pull/15)，未创建重复 PR、未自行合并；push 与 pull_request 两组 CI 的 Java、Python、Frontend、OpenAPI 共 8 项均通过。

## 2026-08-09 T08B 最终真实浏览器 E2E

- 门禁与环境：继续使用分支 `codex/t08b-course-media-web` 和现有 Ready PR #15；开始时工作区干净，`HEAD` 与远端均为 `5d253c4d7707ba99c5144804f036e487ce95770b`。通过 `scripts/Invoke-WithLocalEnvironment.ps1` 启动当前 JAR 的 Gateway/IAM/Course/Media 和教师/学生 Vite，使用本机既有的真实 RabbitMQ/OSS/FFmpeg 依赖；凭据仅在子进程环境中注入，未读取或输出。
- 教师上传与发布：真实登录 `teacher_test_01` 后编辑固定 seed 课程；1280×720 PNG 封面直传 OSS、100% 进度、`READY` 和课程封面投影实际加载通过。创建两个 VIDEO 课时，各上传同一 12 秒、640×360、含 1 kHz 声音的 H.264/AAC MP4；形成两个独立 READY 视频资产。保存目录后发布按钮恢复可用，v2 发布成功；owner 预览有源、`readyState=4`、`muted=false`。
- 学生页面与播放：真实登录 `student_test_01`，从课程列表进入详情并确认封面可见、详情 `<video>` 数为 0；“开始学习”进入 `/learn/:courseId/:lessonId`，当前课时、两课目录、上一课、下一课和退出学习齐全。HLS 实际显示 640×360 画面，`volume=1`、`muted=false`、时间从 0 推进到约 10 秒；刷新深链接后同 URL 恢复到 `readyState=4`。切换第二课后只有一个新播放器且时间归零，退出后 `<video>` 数为 0。
- 音轨证据：从两个本轮 LESSON_VIDEO 的精确 OSS Object Key 各下载首个 TS 切片；定向 Java 审计 1 项通过，`ffprobe` 均确认 H.264 视频流和 AAC 音频流，FFmpeg `volumedetect` 均确认平均音量高于 `-60 dB`、不是静音。审计不列举 Bucket、不输出签名 URL，下载临时文件随后删除。
- 授权刷新与失败边界：Media 访问授权 TTL 临时设为 20 秒，18 秒观察窗内播放器保持可用，授权审计由 20 增至 22，证明到期前刷新。随后发布 v3 为报名制且两课均非试看，不创建报名；学生访问当前 v3 lessonId 显示 T11 前报名 403 文案，连续观察后 LESSON_VIDEO 授权审计保持 8，没有自动重试循环。旧 v2 lessonId 在 v3 发布后按历史版本不可见返回 404，当前 v3 lessonId 的 403 与之区分。
- 安全审计：浏览器控制台 8 条记录中无 warning/error、无签名 URL/Token/密钥标记；Gateway、IAM、Course、Media 和当前 Vite 日志无签名 URL、Bearer Token、密码或 AccessKeySecret。唯一启动错误来自旧教师 Vite 占用 5174，停止后当前分支 Vite 日志无错误。没有把 OSS URL、Bucket、Object Key 或授权头写入课程请求和持久化状态。
- 完整回归：冻结安装、OpenAPI 生成与一致性、全 workspace 类型检查、三端生产构建均通过；Vitest 沙箱内被 `esbuild spawn EPERM` 阻止启动，同命令在批准的沙箱外复跑后 10 文件 66 项全部通过。Java 完整 `clean verify` 的 8 个 Reactor 模块全部成功，Media 14 项中 13 项通过、真实环境用例默认跳过 1 项；另行显式执行的 OSS 音轨审计 1 项和精确清理 1 项均通过，临时测试源码未保留。
- 数据与清理：未新增数据库、迁移、表或虚假报名。复用 `edu_platform_iam_dev`、`edu_platform_course_dev`、`edu_platform_media_dev`、两个固定测试账号和一个幂等 seed 课程；按 3 个精确 mediaId/uploadId/Object Key 删除 1 个封面、2 个视频、HLS/临时授权对象，四个媒体 RabbitMQ 队列均为 0且未 purge。媒体库恢复原有 6 个资产，本轮前缀为 0；seed 课程恢复为原 v1/PUBLIC/1 章/1 课时/0 资源，另一个既有课程保留；删除本轮 16 条刷新会话、4 条登录审计和 20 个本地临时文件，两个账号保持 ACTIVE，六个应用端口均停止。
- 证据边界：这是实际 Vue 页面、Gateway、课程域授权、真实 OSS/RabbitMQ/FFmpeg 的浏览器 E2E，不是 Mock；未验证或实现 T11 报名、学习进度、笔记、练习、AI、多码率、字幕、DRM 或 CDN 管理。

## 2026-08-09 三端全局导航规格审计

- 门禁：确认 T08B 分支工作区干净，`HEAD` 与 `origin/codex/t08b-course-media-web` 均为 `384ad3fed84c2a23bf92a6a28912f4ec55e87448`；PR #15 为 Ready/Open、`CLEAN/MERGEABLE`，两组 Java、Python、Frontend 和 OpenAPI 检查均成功。随后保留 T08B 分支，从与 `origin/main` 一致的 `a642d7ea98396ef1da6da98047556f1773c2e0ea` 创建独立文档分支。
- 审计发现：student-web 同时把全局品牌标题和主导航入口命名为“学生学习中心”，且公共课程页、工作台数量和登录状态会动态增删主导航；teacher-web 与 admin-web 也只按已实现功能临时拼接入口。现有页面规格冻结了路由职责，却没有冻结全局导航槽位，因此后续任务仍可能反复改变应用外壳。
- 规格结论：品牌固定为 `Edu Lion`，页面标题进入内容区；学生/教师固定顶栏、管理端固定侧栏以及通知/工作台/账号工具区的标签、顺序、启用任务、活动路由和响应式语义均写入 `docs/frontend/page-specs.md`。未完成入口保留“建设中”禁用槽位，不跳转占位页；后续任务只启用，不增删/改名/换序。
- 任务归属：不新增冗余任务，将三端稳定导航壳和当前重复文案修复纳入现有 T08B PR #15；T10、T12A～C、T15A 和 T17A～C 只启用各自已有槽位。T08B 同步本次文档后必须重新验证三端各路由、匿名/登录、多工作台、窄屏和键盘状态。
- 范围与数据：本次只修改 Markdown 页面规格、架构、开发规则和路线图，不修改 Vue/TypeScript、OpenAPI、Java/Python、数据库迁移或事件；没有连接或修改数据库、Redis、OSS、RabbitMQ、FFmpeg 或外部模型，没有创建表、Seed、伪数据或外部对象。

## 2026-08-09 T08B 三端固定导航实现

- 合并门禁：工作区最初干净但位于 `main@1cf7c11`，切回既有 T08B 分支后确认本地/远端均为 `384ad3f`；完整读取新版规则与页面规格，使用普通 merge 合入 `origin/main@1cf7c11`，merge commit `be627eb`。冲突同时保留 PR #17 的导航事实源和 T08B 真实浏览器、音轨、403、数据清理事实，未把 T10/T12/T15/T17 标为完成。
- 模型与结构：共享 `AppShell` 只渲染固定 `Edu Lion` 品牌、传入导航、账号工具和内容结构；三端角色/权限判断留在应用模型。学生五项、教师七项、管理七项顺序固定；未交付项无 href、无 tabindex、`aria-disabled=true`，管理端为左侧栏。账号工具始终按通知、工作台、账号排列，匿名/登录/多工作台只改变槽位状态。
- 路由语义：学生课程列表/详情只高亮课程中心，学习页只高亮我的学习且不跳转未交付 `/my-learning`；教师只有公共课程中心和有权限的课程管理启用，默认/单工作台落点改为课程管理；管理端 T17C 前七项均建设中。没有新增 PlaceholderPage 或后续业务页面。
- 自动化：冻结安装、`generate:api`、`check:api`、全 workspace `typecheck`、13 文件 91 项 Vitest 和三端生产构建通过；构建只有既有 hls.js 动态 chunk 体积提示。沙箱内 Vitest 因 `esbuild spawn EPERM` 未启动，同命令在批准的沙箱外通过。
- 响应式与可访问性：真实 Vite 页面在 1440×900 与 390×844 同源视口下均保持冻结顺序且无横向溢出；浏览器 DOM 断言所有建设中入口 href/tabindex 均为空，`aria-disabled=true`，可聚焦元素按启用主导航、账号工具、页面表单顺序出现。临时视口文件已删除，控制台无 warning/error。
- 账号菜单回归：实际登录学生页面复现到账号工具区 `overflow-x:auto` 连带生成 `overflow-y:auto`，将向下弹层裁进 46px 高滚动容器。修复后账号菜单从滚动上下文释放，桌面浮层宽 248px；390×844 同源视口下页面宽度为 375/375、弹层完整可见且账号工具溢出为 `visible/visible`。个人中心和布局设置明确建设中、无 href 且禁用，退出登录是唯一可操作项；原生 `summary` 的 `tabIndex=0`、标签为“打开账号菜单”。
- 真实链路边界：复用现有 dev 服务和两门已发布课程，只读确认公开详情品牌/高亮正确、无播放器且提供真实 `/learn/...`；匿名点击后统一登录完整保留深链接。学生登录后真实进入既有 `/learn/...`，只高亮“我的学习”、没有重复“学生学习中心”，当前课时/目录/退出学习存在，单个 HLS 播放器 `readyState=4`、`muted=false`、`volume=1`；刷新深链接后恢复，退出后播放器数为 0。控制台无 warning/error。未创建数据库、表、迁移、课程、媒体、报名、进度、OSS 对象或 RabbitMQ 消息。

## 2026-08-09 T09 题库与知识点后端

- 门禁与证据：开始时工作区干净，分支为 `codex/t09-question-bank-backend`，`HEAD=6839eab1577d559bd83417baaf8cf3d807fef305`。完整阅读契约、事件、媒体/MQ ADR、环境、路线图和 T09/T10/T11 边界。外部题库以逐文件流式程序做聚合统计，仅人工打开 5 个代表文件；没有输出/提交完整题目、扫描缓存或原始样本。
- 契约：先冻结 OpenAPI 0.8.0 和 `QuestionImportRequested` v1，再实现服务；新增教师知识点、本人题目、状态和导入接口及管理员知识点写接口。生成客户端来自同一 OpenAPI，未手写 DTO，未创建 T10 页面或学生取题端点。
- Learning：实现树形知识点、五种初中数学题型、稳定聚合 ID、不可变版本、DRAFT/ACTIVE/ARCHIVED、owner 404、`clientRequestId` 幂等、revision 乐观锁和无答案列表投影。Flyway V1 只有题库/导入/可靠消息 10 张表，不含 T11 报名、进度、试卷、作答、成绩或错题。
- 媒体与导入：media-service 评审并启用四种教师用途；learning-service 只保存 mediaId/用途/顺序，通过 JSON 校验 owner/用途/READY，不引入 OSS SDK。CSV/XLSX 规范化导入保留文本/LaTeX、不猜拆选项，对公式、嵌图、外部图片和坏结构返回行错误；Outbox、mandatory confirm/return、有限退避、3 次总消费、DLQ、eventId 与确定性行请求双重幂等已覆盖。
- Java：最终完整 `clean verify` 的 8 个 Reactor 模块全部成功；2026-08-12 在 MySQL 保留字修复后复跑耗时 1 分 40 秒，默认构建 0 失败/错误。Gateway 11 项通过；learning-service 14 项中 H2/Mock 13 项通过、MySQL 验收 1 项默认跳过；另行显式执行 `LearningMysqlMigrationAcceptanceTest`，1 项通过、0 跳过。
- Frontend：`generate:api`、全 workspace `typecheck` 通过。Vitest/Vite 在沙箱内因既有 esbuild `spawn EPERM` 无法启动，批准沙箱外复跑后 13 文件 91 项和学生/教师/管理三端生产构建全部通过；只更新生成客户端，没有实现 T10 Vue 页面。
- MySQL 与数据：2026-08-12 在既有专用 `edu_platform_learning_dev` 上显式验收。首次发现 V1 内部列 `row_number` 与 MySQL 8 保留字冲突并以 1064 停止；V1 尚未合入主线，改为 `source_row_number` 并同步 Repository SQL 后，Flyway V1 校验和真实迁移测试通过。真实验收创建的合成题目、知识点、导入任务、行错误、Outbox、消费记录均按精确 ID 清理；验收前已有的 1 道 DRAFT 题和 2 个知识点未修改。
- 真实业务链路：用户先经 Gateway 完成题目创建与完全相同幂等重放；其余领域场景在 learning-service 可信身份边界执行，并补验 Gateway 伪造身份头无 Bearer Token 返回 401。五种题型、不同 Body 幂等冲突、列表答案防泄漏、他人 404、不可变 v2、旧 revision 409、发布/归档、归档后拒绝编辑全部通过。四类 T09 媒体用途使用真实 OSS 小型合成对象达到 READY；错用途、他人、非 READY、历史版本引用、6 个 Bound Outbox/Media 引用通过。
- RabbitMQ 与清理：两行合成 CSV 经真实队列得到 `PARTIAL_SUCCESS`（1 成功、1 个 `QUESTION_IMPORT.EXTERNAL_IMAGE_REQUIRES_UPLOAD`）；相同 `eventId` 重投前后题目数不变且消费记录为 1。契约合法但任务缺失的测试事件经有限重试进入专用 DLQ并带 `x-death`，随后精确 ACK，主队列/DLQ 归零。到期导入源写 Released Outbox 并由 Media 置为 RELEASED。7 个精确 OSS 对象由 media-service 回收器删除，7 条资产、9 个上传会话及本轮数据库记录归零；未列举 Bucket 或批量删除既有数据。真实 XLSX E2E 未执行，公式/嵌图拒绝由自动化覆盖。
- 安全与可读性：未知/缺失 JSON 字段、错角色/权限、答案泄漏、媒体错绑定、重复消息和失败语义均有验证；人工维护的业务边界、复杂流程、SQL、契约和事件均有中文说明。最终 `git diff --check`、敏感信息审计、暂存范围和提交后 `check:api` 在推送前复核。
- 发布：关键实现提交 `169457d` 已按规定使用 `git push origin HEAD` 推送；Ready [PR #18](https://github.com/chengchen-ccnu/ccnu-edulion/pull/18) 已创建且未合并。首轮 CI run #98 的 Python、Frontend、Java 与 OpenAPI 四个 Job 全部成功；落盘发布状态的后续纯文档提交仍需通过同一 CI。

## 2026-08-12 T09A 历史 JSON 题库兼容与迁移

- 门禁与样本边界：分支 `codex/t09a-legacy-json-import` 基于最新 `main@71bd7d8`；开始时工作区干净。只读目录以逐文件流式脚本完成聚合统计，共 44,441 份 JSON、0 个解析失败、7,359 个显式 `subQues` 容器、28,646 份含外部图片、8,152 份空 `solution`。模型人工阅读 6 个代表文件，每种结构不超过 3 个；未复制、修改、提交或在终端输出真实题干、答案和 JSON。
- 契约与迁移：先更新 OpenAPI 0.9.0、`learning-question-events.v2.yaml`、ADR 0006、媒体 ADR 与 backlog，再实现代码。既有 Flyway V1 未修改；V2 新增历史批次、来源引用、问题记录和有序子题关系，保留目录/JSON 教材与年级元数据、原始难度及内容/路径哈希，不引入跨 Schema 外键。
- 兼容结果：`select_single`、`select_multiple`、`fillin`、`writeout` 和显式 `subQues` 可导入；组合题保存父题、子题及稳定顺序。严格匹配的内嵌选项才拆分；无法可靠拆分的选项、隐式多问、子题答案缺失、未知结构、路径/JSON 元数据冲突和清洗到不安全 HTML 均进入 `NEEDS_REVIEW`。HTML 使用白名单清洗，LaTeX 原样保留，`solution` 允许为空。
- 有界与安全：PowerShell 迁移器逐文件读取，默认 dry-run，每批最多 20、每任务最多 1000，当前脚本并发固定为 1，仅输出聚合计数。任务、批次和来源均可幂等重放；失败项可在新受控任务中重试。外部图片由 media-service 以 HTTPS 精确主机白名单、逐跳 DNS/重定向复核、大小/MIME/魔数限制下载后写入对象存储并返回 `mediaId`；learning-service 未引入 OSS SDK，也不保存外部 URL。
- 自动化结果：最终完整 `clean verify` 的 8 个 Reactor 模块通过，共 71 项测试、67 通过、4 个真实环境用例默认跳过；media-service 共 19 项，17 通过、2 个真实环境用例默认跳过。受控真实图片用例另在显式启用真实环境后 1 项通过。OpenAPI 生成和全 workspace 类型检查通过；Vitest 在沙箱内因 `esbuild spawn EPERM` 无法启动，经批准在沙箱外复跑后 13 个文件 91 项全通过，学生/教师/管理三端生产构建通过。两个 PowerShell 脚本完成语法解析，聚合统计脚本以 3 文件上限验证只输出计数。
- 真实小样本与数据：先通过凭据包装脚本在既有专用 `edu_platform_learning_dev` 验证 V1/V2，程序化选择 1 份无图片且无需复核的真实文件完成 dry-run、实际导入及任务级精确清理。最终验收另只选择 1 份含 1 张图片的 `fillin` 真实 JSON，media-service 白名单仅为精确主机 `v3.rawstonedu.net` 且禁止重定向；真实 Gateway → learning-service → media-service → 阿里云 OSS 链路通过，生成 `QUESTION_IMAGE/READY`，learning 题目版本只保存 `mediaId`。同源请求幂等重放保持 1 个媒体/上传记录；dry-run 媒体、审计、对象和临时文件增量均为 0；非法主机返回 422 且无上传记录。
- 真实外部系统与清理：RabbitMQ 实际完成 Bound/Released 事件发布、消费与状态转换；OSS `HEAD` 和短期签名读取验证成功。随后按精确任务、题目、媒体、上传、事件和 Object Key 清理，得到 `mysqlRemainingRows=0`、`ossObjectDeleted=true`、6 个相关队列为空、本机临时文件增量为 0。最终复跑证据哈希前缀为任务 `ea90ccf387d38207`、题目 `be3404bf0b8b4e7b`、媒体 `01afe8b6a742604e`、上传 `aacd4baa5800f90e`、对象键 `64bf9dd968cac53b`；没有输出真实文件路径、内容、完整 URL、Bucket、Object Key 或签名 URL。未创建数据库、Seed 或持久伪数据。
- 边界：未执行 44,441 份全量迁移，未实现 T10 页面、T11 组卷/作答，也未修改 Python 智能体。本次单文件 E2E 只证明经审核主机上的该图片在验收时可安全转存，不代表全站下载授权或全量兼容；全量前仍须另行确认来源授权、最小白名单、限流和迁移计划。
- 发布：实现提交 `ac69319` 和最终真实图片验收提交 `027de44` 已按规定推送；[PR #19](https://github.com/chengchen-ccnu/ccnu-edulion/pull/19) 的 Java、Python、Frontend 和 OpenAPI 最新检查全部成功，并于 2026-08-12 合并到 `main@8295f46768c8ea77eae617b266e85f06d2445477`。合并后本地 `main` 已快进同步，T09A 本地和远端功能分支均已清理。

## 2026-08-12 题库页面路由与 T09B 契约闭环规格

- 审计基线：总控在 `main@8295f46768c8ea77eae617b266e85f06d2445477`、干净工作区完成核对；T09A PR #19 已合并且最终 CI 全绿，Node `20.19.5`、pnpm `10.22.0` 与仓库冻结版本一致。完整读取前端页面唯一事实源，并只读核对 OpenAPI 0.9.0、生成客户端、教师路由和固定导航。
- 缺口证据：现有契约将 `COMPOSITE` 排除在题目输入之外；题目详情只有当前版本，没有不可变版本历史查询；历史 JSON 虽持久化来源项和问题码，却没有可分页、刷新恢复的逐项查询和显式解决/跳过接口。教师端也只有 `/teacher/questions` 聚合路由，无法在不发明平行路由或仅靠浏览器内存的情况下完成编辑与复核。
- 页面冻结：教师题库高亮组固定为 `/teacher/questions`、`/teacher/questions/new`、`/teacher/questions/:questionId`、`/teacher/questions/:questionId/edit`、`/teacher/question-imports` 和 `/teacher/question-imports/:importTaskId`。列表、新建、只读详情/版本、编辑、导入列表和导入详情/复核职责互不混入临时弹层，刷新后必须由 URL 与服务端恢复。
- 任务拆分：新增 T09B 先向前补齐组合题结构化写入、版本历史、统一导入查询和逐项人工解决契约；T10 只在 T09B 冻结后实现 Vue 页面。T09B 不创建前端页面、不修改已合并 V1/V2、不授权全量迁移；T10 不实现组卷、学生作答、成绩或自动导入 44,441 份题库。
- 范围与数据：本轮只修改页面规格、路线图、T09A 状态和验证记录，没有修改 OpenAPI、生成代码、Java/Python/Vue、数据库迁移或配置；未连接 MySQL、Redis、OSS、RabbitMQ、FFmpeg 或外部题库，未创建数据库、表、Seed、伪数据、媒体对象或消息。Markdown 差异检查通过即可，不把未执行的工程构建宣称为验证结果。

## 2026-08-12 T09B 题库前端契约闭环实施

- 门禁与范围：开始时分支为 `codex/t09b-question-bank-ui-contract`，HEAD、merge-base 和本地 `main` 均为 `c358c8e7dd971dd57c31f7040252b6715a1256ae`，工作区干净。完整读取 `docs/frontend/page-specs.md`，保持教师题库六组稳定路由不变；未创建 T10 Vue 页面、组卷/作答/成绩接口或 Python 智能体修改。
- 契约先行：主体实现先把 OpenAPI 升级到 0.10.0 并生成客户端，独立验收修复再向前升级到 0.10.1，明确人工复核终态门禁、409 业务码与并发相同请求结果复用。普通题与 `COMPOSITE` 共用完整父版本字段，组合题以 `children` 提交一层有序完整子题；父/子均含题型、题干、选项、答案、解析、知识点和 `mediaId` 引用。
- 不可变历史与权限：V3 只向前增加 `parent_question_id`、精确 `child_question_version_id`、复核项表和解决幂等表，V1/V2 未修改。历史父版本读取精确子版本，不跟随子聚合指针；子聚合不进入根列表或独立教师路由。他人题目、版本、任务和复核项统一 404，列表 SQL 不读取答案或完整解析。
- 复核状态机：每个提交项保存安全元数据、问题码、outcome、revision 和解决状态，不保存原始 JSON、题干、答案、路径明文或外部 URL。只有最终批次完成且任务处于 `COMPLETED/PARTIAL_SUCCESS` 才允许显式完整修正导入或确认跳过；`PENDING/PROCESSING/FAILED/CLEANED` 返回 409 `QUESTION_IMPORT.TASK_NOT_REVIEWABLE`，不会提前结束多批次任务。批次 20、任务 1000 上限未改变，未执行全量题库迁移。
- 并发幂等：相同 `clientRequestId`/请求指纹在任务锁前后双查；锁后二查及结果项均使用 `FOR UPDATE` 当前读，兼容 MySQL `REPEATABLE READ`。两个并发相同解决请求返回第一次结果，只创建一题、只写一条解决记录且统一计数只累计一次；不同指纹或旧 revision 仍返回 409。
- 自动化：OpenAPI 0.10.1 `generate:api`/`check:api` 和全 workspace `typecheck` 通过；最终 Java 8 模块 `clean verify` 全部通过，learning-service 20 项中 18 通过、2 个显式真实环境用例默认跳过。新增覆盖非最终批次产生 NEEDS_REVIEW、提前解决 409、任务保持 PROCESSING、后续最终批次继续提交、最终解决成功及双线程相同请求一致结果/单题/单计数。
- 真实 MySQL 与数据：通过规定凭据包装脚本在既有 `edu_platform_learning_dev`、V3 Schema 上复跑；应用未创建数据库或迁移。仅创建随机 owner 下的最小虚构知识点、单子题组合题、一项含糊历史 JSON 复核项和并发修正题。初轮验收发现结果详情旧快照，改为当前读后 MySQL 8 双线程复跑通过；按精确 ID 逆序清理，owner 题目、幂等键、任务、复核项和解决记录均断言为 0。没有读取真实历史题库、连接 OSS/RabbitMQ 或创建媒体/Seed。
- 完整工程门禁：独立验收修复后的 Java 8 模块 `clean verify` 全部通过（2 分 14 秒）；Frontend 生成客户端与全 workspace 类型检查通过。主体实现阶段的 Vitest 沙箱外 13 文件 91 项及三端生产构建结果保持通过，本轮未修改 Vue 运行时代码。
- 发布：关键主体提交 `86a9176` 和独立验收修复提交 `db4ee5a` 经 Ready [PR #21](https://github.com/chengchen-ccnu/ccnu-edulion/pull/21) 交付；最新 Java、Python、Frontend、OpenAPI 检查全部成功，并于 2026-08-12 合并到 `main@82ca1fbe5c98ee8fbc010304bdfb16deeb6ac84e`。合并后本地 `main` 已快进同步，T09B 本地和远端功能分支均已清理；T10 前置审计确认 T07/T09B、OpenAPI 0.10.1、生成客户端、六组稳定路由规格和教师端固定题库导航槽位均已具备。

## 2026-08-12～13 T10 教师题库前端实施与真实验收

- 门禁与契约：开始时分支为 `codex/t10-question-bank-web`、工作区干净、`HEAD=74b6b7c09b...`，并确认 `82ca1fbe` 与 `74b6b7c` 都是 HEAD 祖先。完整阅读仓库规则、页面规格、OpenAPI 0.10.1、T09B 交付和路线图；T07、T09、T09A、T09B 提交与生成客户端均可用，初始 `check:api` 通过。
- 页面与权限：页面规格冻结的六组路由全部使用真实页面和 `teaching:access` 守卫；既有“题库”导航槽位启用且六路由同组高亮，没有调整标签、顺序或其他槽位。本人列表摘要、筛选/分页、历史版本、导入复核通过 URL 深链接与刷新恢复；匿名 401、学生访问教师端 403、他人/随机资源统一 404 均通过真实浏览器验证。
- 结构化编辑与内容：真实创建普通题和含 2 个有序结构化子题的一层组合题；`expectedRevision` 不可变 v2/v3、指定历史快照、两个浏览器标签制造的 409 本地输入保留、422 字段映射、DRAFT→ACTIVE→ARCHIVED 及归档只读均通过。`marked` + KaTeX + DOMPurify 预览可显示公式和上标，危险事件属性与不允许的嵌入图片没有进入 DOM。
- 媒体与导入：QUESTION_IMAGE、QUESTION_AUDIO、QUESTION_ATTACHMENT 经真实上传会话/直传/校验到 READY 并绑定组合题；CSV 导入 1/1，原一行 18 列 XLSX 为 0/0，修正最小 XLSX 为 1/1。LEGACY_JSON 验证默认 dry-run、3≤20/1000、有界文件读取、稳定问题码、结构化修正、确认跳过、结果筛选刷新和任务级二次确认清理；没有读取个人 PDF、扫描完整历史目录或提交客户端绝对路径。
- 自动化与响应式：`generate:api`、`check:api`、全 workspace `typecheck` 通过；Vitest 18 个文件 116 项全通过；学生、教师、管理三端生产构建通过。真实浏览器另覆盖 45 条内存分页、429、503、partial-error 保留旧卡片、空态，以及六路由逐一 820×1180 无横向溢出。
- 最终人工补验：Tab/Shift+Tab 与可见焦点环通过；“导入任务”“新建题目”可由 Enter 激活，“应用筛选”可由 Space 激活。首次 offline 补验暴露受保护的应用内路由会再次请求当前用户，断网后误清认证状态并整页跳登录；共享守卫改为仅在缺少已认证快照时恢复身份，真实 401 仍由授权请求处理，并新增离线路由专项测试。修复后人工复验确认断网筛选保留旧列表、URL 和输入并显示离线提示，恢复网络重试正常；总体结论更新为**无保留通过**。
- 题目清理：普通题 `ab10bf99-ca6d-4be7-9f97-5da699e7a4fb`、组合父题 `2a9134bc-5c96-407e-b507-1fa43deeaa35`、CSV 题 `eeae94bc-35c9-4de8-85a5-7c5e5f5a7d4d` 和修正 XLSX 题 `1a0c426a-30a1-4e2a-8974-709581bfea03` 均已归档；组合的两个 DRAFT 子聚合作为父版本内部组成保留，不进入根列表。正式契约没有普通题删除端点，未跨库删行或新增生产清理 API。
- 任务与媒体清理：LEGACY_JSON 成功任务 `ba0baf11-198f-4aa8-9f89-c037b7d54fba` 已精确清理（题目 1、来源引用 3、媒体引用 0），空任务 `e4e0276c-5d0b-48fd-a09e-0e4584592222` 已零影响清理。三个 CSV/XLSX 任务和三项题目媒体、三项导入源仍按正式契约保留且只读复核为 READY/BOUND；导入源到 7 天保留期后发布 Released，media-service 最后引用释放后再经 24 小时宽限和每小时回收器精确删除。归档题媒体为保持不可变详情而继续绑定；如后续要求零残留，需要另建仅 local/test 可启用、按 run ID/显式 ID 的跨服务精确清理任务。
- 消息审计：3 条 `QuestionImportRequested`、6 条 `MediaReferenceBound` 以及相关 `MediaUploaded/MediaReady` 均已发布；learning/media 六个既有主队列与 DLQ 收尾只读计数均为 0，未清共享队列，未创建通知消息。唯一 `LegacyQuestionImportBatchProcessed` 事件 `8ed81ec4-4b96-49e4-9a31-ff5786f19c76` 为 PENDING、10/20 次；文档与源码确认 v2 当前没有 Queue/Binding/消费者。Publisher 最大 20 次、最大 256 秒退避，耗尽转 FAILED，不会无限重试或堆积 Rabbit 队列；无订阅阶段每批会留下失败 Outbox 和有限告警，记录为独立消息治理已知风险，不在 T10 扩展业务。
- 边界与发布：未实现 T11/T12、组卷、学生作答、成绩或错题，未修改 Python 智能体、已合并迁移、OpenAPI 或全局页面规格，也未执行 44,441 份题库。关键实现提交为 `32371d0`，真实验收基线为 `aaaaf73`，最终离线修复为 `5fbdc72`；[PR #22](https://github.com/chengchen-ccnu/ccnu-edulion/pull/22) 于 2026-08-13 合并到 `main@f05c555252ea27054f459f0ae93dc9dbaaff136e`，合并后主分支 Java、Python、Frontend 和 OpenAPI CI 全部成功，本地 `main` 已同步且 T10 本地/远端分支已清理。

## 2026-08-12 T09B.1 题目 HTTP 绑定一致性修复

- 门禁与范围：开始时工作区干净且位于 `codex/t10-question-bank-web`，只读核对其 T10 交付说明后，从最新 `origin/main@82ca1fbe5c98ee8fbc010304bdfb16deeb6ac84e` 新建 `codex/t09b1-question-http-binding`。未修改或覆盖 T10 分支与 Ready PR #22，未新增产品能力、接口字段、数据库迁移或业务表。
- 根因与修复：OpenAPI 0.10.1 的 `QuestionVersionInput.children` 本来就是可选字段，但 Java record 把它作为 creator 参数，和 `fail-on-missing-creator-properties=true` 组合后在 Controller 前错误拒绝普通题。`VersionInput` 现保留 11 个必填字段的严格 creator，仅把 `children` 作为拒绝显式 null 的可选后置属性；`QuestionService` 在创建/更新入口将省略值规范为空列表，使校验与幂等指纹统一。全局严格配置未放松，未知字段、非法枚举/UUID 和真正缺少的必填 creator 字段继续返回 422。
- 安全错误定位：`HttpMessageNotReadableException` 只读取 Jackson 结构化引用链中的安全属性名和数组下标，不解析异常全文；非法枚举可返回 `version.type`，未知字段可返回 `version.unexpected`，没有可靠路径的畸形 JSON 才回退 `request`。统一响应不包含原始请求体、非法值、Jackson/DTO 类名或内部堆栈。
- HTTP 自动化：新增独立随机端口测试，覆盖普通单选题省略 `children` 返回 201、显式 `children=[]` 返回 201、合法单子题组合题返回 201、非法枚举定位 `version.type`、未知字段定位 `version.unexpected`、非法 UUID 定位 `version.knowledgePointIds[0]`、缺少 `stem` 定位 `version.stem`。专项 7 项全部通过；learning-service 共 27 项执行、25 通过、2 个显式真实环境用例按条件跳过。
- 完整门禁：`pnpm generate:api` 已把两处 OpenAPI 说明生成到唯一客户端，提交后 `check:api` 通过且无生成漂移，8 个 workspace 的 `typecheck` 通过；最终 8 模块 Java `clean verify` 全部通过，耗时 2 分 21 秒。
- 数据与外部系统：自动化仅在独立 H2 内存库中创建固定合成 owner、一个合成知识点、两道普通题和一道单子题组合题；每项前后按 owner、知识点和关联版本精确清理，进程结束后内存库销毁。未连接真实 MySQL/Gateway、OSS、RabbitMQ 或历史题库，未创建持久数据库、表、Seed、媒体或消息，因此没有外部数据需要清理。
- 发布：实现提交 `8c1049a` 经 Ready [PR #23](https://github.com/chengchen-ccnu/ccnu-edulion/pull/23) 交付；Java、Python、Frontend 与 OpenAPI 检查全部成功，并于 2026-08-13 合并到 `main@0df0ae6cc8b7da999c540a040fcb9b53761aaff5`。T09B.1 本地和远端分支均已清理；该修复作为 T10 真实写请求验收前置条件，通过普通 merge 纳入现有 T10 PR #22。

## 2026-08-13 T11 学习测评后端前置审计

- Git 与发布基线：审计开始时 `main`、`origin/main` 均为 T10 合并提交 `f05c555252ea27054f459f0ae93dc9dbaaff136e`，工作区干净；PR #22 已合并且主分支 CI 全绿。随后从该基线创建 `codex/t11-learning-assessment-backend`，未创建或修改业务代码。
- 依赖结论：T05 已冻结公开课程、不可变发布版本、章节/课时和访问方式；T08A.1 已建立 T11 前报名制非试看拒绝边界及课程媒体授权链路；T09B 已冻结普通题/组合题不可变版本和教师答案投影。三项均已合并，满足 T11 的领域输入前置。
- 身份与授权：IAM 已提供 `learning:access`，Gateway 可转发可信用户、角色和权限头。T11 必须按能力与报名/测评所有权等业务事实授权，允许同时拥有教学与学习能力的账号进入学习域，不得硬编码只有 `STUDENT` 才可报名、学习或作答。
- 契约与服务边界：当前 OpenAPI 0.10.1 和事件目录明确尚未暴露 T11 API/事件，这是 T11 需要先完成的契约工作而非阻断。报名校验、课程访问快照与课程播放授权必须通过版本化 API/事件交互；learning-service 不得查询 course Schema，course-service 也不得查询 learning Schema。服务间鉴权、超时、失败降级和幂等语义必须在实现前冻结。
- 数据演进（后续按真实库状态修正）：learning-service 已有 V1～V3、V5～V8，且真实开发库已经应用到 V8；T11 必须从 V9 向前增加报名、进度、笔记、测评、试卷快照、作答、成绩和错题数据。补写 V4 会成为低版本迁移，无法在已到 V8 的数据库中按正常顺序应用，因此不得补 V4、启用 out-of-order、baseline 或额外迁移 gate。试卷必须冻结题目版本而非跟随题库当前指针，学生读接口不得泄露未到发布时点的答案或解析。
- 范围与环境：本轮只更新总控文档并准备分支，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg，没有创建数据库、迁移、表、Seed、伪数据、媒体对象或消息。T10 遗留的无消费者 v2 审计 Outbox 仍是有限重试的独立已知风险，不阻断 T11，但 T11 不应继续新增无明确消费者的事件。

## 2026-08-13 T10A 历史 JSON 目录上传修复

- 前置与范围：工作区最初位于与 `main@0abc39c` 相同的空 T11 准备分支且保持干净；切回 `main` 后独立创建 `codex/t10a-legacy-json-directory-upload-fix`。完整阅读页面规格，未调整路由、导航或页面职责，也未修改 T11 业务代码。
- 根因证据：浏览器目录选择把完整 `webkitRelativePath` 去扩展名后作为 `externalId`，而后端在批次入口要求它与 JSON `exerciseID` 精确一致，导致任何多层目录项在解析题型前返回 422；旧路径逻辑还固定取前两个目录段，选择“题库”根目录时会把根名误当教材版本。
- 修复行为：前端优先使用 `exerciseID`，缺失时回退文件名且不截断；从全部安全相对目录段识别教材版本和七/八/九年级，兼容选择题库根、教材目录、年级目录及既有通用三层来源。无法恢复的单文件元数据继续使用 `UNKNOWN` 并交由人工复核，没有弱化服务端校验。
- 自动化门禁：Vitest 实际执行 18 个文件、121 项全部通过，历史导入专项由 3 项增加为 8 项；教师端专项类型检查通过；全 workspace OpenAPI 生成与 8 组类型检查通过；三端生产构建在沙箱内因 esbuild `spawn EPERM` 受阻，获批在沙箱外复跑后全部通过，仅有既有大 chunk 警告；`git diff --check` 通过。
- 数据与外部系统：只读检查用户明确指定的 1 份 JSON 以复现字段和目录形态，没有复制、修改、上传或批量扫描题库。未连接 MySQL、Redis、RabbitMQ、OSS 或外部图片主机，未创建数据库、表、Seed、题目、任务、消息或对象，不需要清理。
- 真实服务状态：后续全量预检前 Gateway、IAM、learning-service 均为 HTTP 200 / `UP`；IAM 的 DB/Redis 与 learning-service 的 DB/Redis/RabbitMQ 组件均为 `UP`。未启动或验收教师端浏览器页面。
- 全量脚本扩展：用户明确授权后增加 `AllItems` 多任务编排；正式模式需额外 `ConfirmFullMigration`，每任务 1000、每批 20 的服务端边界保持不变。来源按稳定路径排序并生成清单指纹，任务/批次请求 ID 确定生成；检查点在每个确认批次后原子更新，可在令牌、网络或进程中断后以相同命令恢复，并支持按 Import 检查点逐任务精确清理。
- PowerShell 合成验收：在 Windows PowerShell 5.1 下用本地 HTTP Fixture 和 5 个临时合成 JSON 验证 3 个任务分片、dry-run 5/5、Import 5/5、完成态幂等重跑及 3 个任务清理；随后在第 2 批注入一次 503，确认检查点保留 1 个批次且源索引未前移，相同命令恢复后完成 5/5。检查点断言不含虚构 Token、临时绝对根或题目正文；临时文件和 Mock 进程均已清理。
- 安全失败诊断：任务创建和批次 catch 只白名单提取 `httpStatus/code/traceId/fieldErrors(field,reason)`，忽略完整响应、`message/path/timestamp` 和异常全文；字段原因额外遮蔽 Token、Bearer/JWT、绝对来源路径、题干、答案、解析和结构化原始 JSON。
- 诊断与编码自动化：`pwsh` + Pester 7/7 通过，包含真实本地 TCP HTTP 401 任务创建、422 批次失败、四类安全诊断字段、失败检查点保持和显式 UTF-8 无 BOM 请求字节门禁。另用 Windows PowerShell 5.1 对稳定排序前 3 项构造批次并由本地 TCP 捕获，严格 UTF-8 解码、JSON 解析、3 项数量和第 3 项 17 个属性均通过；未请求真实 API。失败检查点仍保持原 `importTaskId`、`nextBatchIndex=0`、`nextSourceIndex=0`、`completed=false`，没有删除、重建或推进任务。
- 真实全量 DryRun：经 Gateway 以 `dryRun=true` 完成 44,441 项、45 个唯一任务，最终 `processed=44,441`、`VALID=29,127`、`NEEDS_REVIEW=15,314`、`FAILED=0`、`SKIPPED=0`、`imported=0`。检查点 `completed=true`、无活动任务，文本复核不含 Token、题目字段或绝对路径；现有任务与复核记录按要求保留。
- DryRun 安全汇总工具：新增 `scripts/Get-LegacyQuestionDryRunSummary.ps1`，只从检查点取得任务 ID，经 Gateway GET 任务详情与分页复核投影；输出仅含任务/记录总数、四类 outcome、issueCode 和组合计数/比例。合成 HTTP Fixture 6/6 通过，覆盖多任务分页、计数/组合、401、422、空任务、中途 503、无部分输出、敏感投影不泄漏，以及不遍历题库、不写检查点、不调用批次或清理端点。与迁移诊断测试合计 Pester 13/13；5 个相关脚本通过 Windows PowerShell 5.1 语法检查，`git diff --check` 通过。
- 真实 issueCode 分布：17 种组合合计恰为 15,314 条 NEEDS_REVIEW。问题码出现次数依次为 `PATH_JSON_METADATA_CONFLICT=5,793`、`SUBQUESTION_ANSWER_MISSING=4,827`、`IMPLICIT_MULTI_PART=3,375`、`EMBEDDED_OPTIONS_AMBIGUOUS=2,471`、`FIELD_INVALID=173`、`HTML_UNSAFE=37`；单问题码 13,954 条，多问题码 1,360 条。`TYPE_UNSUPPORTED`、`SOURCE_CONTENT_CONFLICT`、`EXTERNAL_IMAGE_TRANSFER_FAILED` 均为 0，但 DryRun 不执行图片转存，不能据此宣称图片迁移通过。
- 分类结论：仅“规范化后唯一等价的路径/JSON 元数据”和“满足连续有序、数量一致、内容非空、答案落在选项集合的显式选项标记”可作为确定性规则候选；子题答案缺失需先细分真实原因，隐式多问、HTML 不安全、多问题码组合和真实元数据冲突必须人工复核。解析规则变更后必须使用新 `SourceSystem` 与独立检查点重新 DryRun，保留旧 45 个任务作为基线，用户确认前不执行 Import。
- VALID-only 编排：在既有迁移器中增加 `ValidatedDryRunCheckpointPath` 与 `PlanOnly`。脚本先核对已完成检查点、冻结来源根/清单指纹、SourceSystem、任务终态和持久化计数，再经 Gateway 分页取得 VALID 键，只将快照中一一匹配的来源加入正式任务；15,314 条既有复核项不会提交，也不会创建正式来源幂等记录。计划模式不创建/推进检查点或任务，只输出总来源、选择数、图片数和精确主机聚合。
- 图片边界：只对所选 VALID 合成项提取与 Java 解析器一致的带引号 HTTPS `<img src>`，正式执行要求 `AllowedImageHost` 完整覆盖且拒绝通配符/URL/端口/路径。URL 随原 JSON 进入既有 API，图片仍由 learning-service 调 media-service 执行精确白名单、逐跳 DNS/重定向、大小、MIME、魔数和 OSS 转存；脚本不下载图片、不连接 OSS，也不修改服务配置。远端正式失败仍按 `EXTERNAL_IMAGE_TRANSFER_FAILED` 复核，不能以 DryRun VALID 或主机门禁替代媒体校验。
- VALID-only 合成验收：PowerShell Pester 新增 4 项并全部通过；仅使用 3 份临时合成 JSON 和本地 TCP Fixture，覆盖计划无写入、两个 VALID/一个复核项的精确筛选、图片 URL 保留、图片主机未批准时 POST 前停止和 401 安全诊断。与既有迁移/汇总测试合计 17/17；输出断言不含 Token、题目、来源路径、原始 JSON或完整任务 ID。没有读取真实题库正文、调用真实 Gateway、数据库、media-service、OSS 或外部图片主机。
- 边界说明：本轮没有执行正式 Import，没有创建正式题目、媒体或 OSS 对象，也没有删除、重建或清理检查点、45 个 DryRun 任务及复核记录。全量迁移脚本逐项有界读取来源以提交预检，但本次后续验收与汇总不得再次批量读取 44,441 份 JSON 正文。`AllItems` 只保证全部文件提交处理；`NEEDS_REVIEW`、字段失败和图片失败仍需人工处理，不能把 `processedCount` 当作全部成功导入。
- 发布：T10A 的 6 个实现与文档提交经 [PR #25](https://github.com/chengchen-ccnu/ccnu-edulion/pull/25) 合并到 `main@ed141aa56b3f14cfc9486250a753f3e28419c78c`；分支推送与 PR 事件的 Java、Python、Frontend、OpenAPI 共 8 项 CI 全部通过。PR 合并只发布迁移能力，没有触发正式题库 Import、数据库清理或 OSS 写入。

## 2026-08-19 T10B 数学知识图谱导入与题目知识点绑定

- 门禁与范围：从 T10A `df73fb0` 的干净工作区创建 `codex/t10b-knowledge-graph-import`；完整阅读页面规格和现有 OpenAPI 后实施。没有新增路由、改动 Python 智能体、修改两个只读来源文件，或删除/重建 T10A 的 45 个 DryRun 任务、复核记录及检查点。该任务当时曾写“T11 预留 V4”，但 V5～V8 随后已进入真实开发库；T11 因此从 V9 开始，补写 V4 会成为无法正常顺序应用的低版本迁移，且不使用 out-of-order/baseline 绕过。图谱迁移 V5/V6 及后续 V7/V8 均保持不改写。
- 契约与权限：OpenAPI 0.11.1 新增管理员图谱任务/节点批次/关系批次/安全问题分页接口，以及教师端分页搜索和 1～5 层有限邻域接口；生成客户端同步更新。节点输入显式携带已确认学段和“知识点/题型”名称。Gateway 只路由请求，管理员写接口要求 `administration:access`，教师查询要求 `teaching:access`，浏览器不直连 learning-service。
- 数据模型与导入：`knowledge_point` 保留内部 UUID，并以 `(source_system, source_know_id)` 唯一识别来源节点；来源 parent key 仅作不透明元数据，不写树自外键。新增同 Schema 双外键的显式 `prerequisite -> dependent` 边、任务/批次/staging/安全问题表及题目 tag 元数据；V6 使规范学段和类型名称参与 staging、摘要及正式写入。节点后关系两阶段幂等；正式写入前校验确认映射以及缺失端点、自环、跨学科、跨学段和环，且新节点默认禁用、不删除旧数据。
- 只读图谱复核：3,671 节点、2,107 关系；学段来源分布 `02=817`、`03=1,386`、`04=1,468`，六种类型合计一致；必要字段空值、重复 ID、缺失端点、重复边、自环、跨学科、跨学段和环均为 0，重复名称组 110。关系语义确认为文件 `b` 是 `a` 的先修，平台写作 `b -> a`；孤立节点 2,267，最大入度 11、最大出度 19、最长链按边数 21。479 个不同来源 parent key 全部不可解析，未据此造父节点。
- 历史 tags 与题库复核：44,441 份 JSON 全部可解析；早期只读 tag 扫描确认 1,260 个唯一知识点 ID 均存在且类型冲突为 0。正式图谱导入后，V3 命名空间下的 45 个真实 Gateway DryRun 任务处理 44,441 项：VALID 27,687、NEEDS_REVIEW 16,754、FAILED/SKIPPED 0；未知知识点、类型冲突、学段不匹配均为 0。主要问题为路径/JSON 元数据冲突 5,793、子题答案缺失 4,827、知识点重复 3,567、隐式多小题 3,375、内嵌选项歧义 2,468、名称冲突 406、字段无效 173、HTML 不安全 37。
- 脚本：`Invoke-KnowledgeGraphMigration.ps1` 默认 DryRun，确定性排序和批次 ID，先节点后关系；检查点不保存 Token、绝对路径、节点列表或原始 JSON。请求通过 `MemoryStream` 原样发送 UTF-8，测试要求服务端直接解析中文 JSON，并复用白名单安全诊断器。正式 Import 在任何 HTTP 前要求导入确认和 stage/status/type/parent 四项语义确认；脚本通过已确认的学段与类型数量签名生成规范字段，服务端再次校验来源状态、学段和类型名称。T10B 专项 5/5、全部 Pester 22/22、Windows PowerShell 5.1 解析通过。
- 自动化：learning-service 34 项执行、32 通过、2 个显式真实 MySQL 用例按开关跳过，图谱专项 7/7；H2 MySQL 模式成功应用 V1/V2/V3/V5/V6。OpenAPI 重新生成、全 workspace `typecheck` 和 Vitest 18 文件 122 项通过。完整 Java Reactor、三端生产构建、`git diff --check` 与提交后 `check:api` 在最终提交前后复核。
- 真实环境与数据：通过本地环境包装脚本临时启动 IAM/Gateway/Learning，使用独立 18081/18080/18084 端口并在结束后全部停止；既有 `edu_platform_learning_dev` 由 Flyway 向前迁移到 V6。管理员登录产生正常本地 IAM 审计/刷新会话，Token 未保存。保留 V1/V2 图谱 DryRun 审计任务和新 V3 正式任务；V3 正式表为 3,671 个禁用、整学段节点和 2,107 条关系，parent 外键均为空。另保留 V3 的 45 个题库 DryRun 任务；44,441 条来源记录的 `question_id` 均为空，正式题目与绑定为 0。未创建伪数据，H2 Fixture 随进程销毁。
- 真实 DryRun：V1 修复传输后完成，但发现服务端重复名称按超额节点计 117，而本地按重复组计 110；保留 V1 审计任务，统一为组数后使用新 `KG_MATH_T10B_V2` 与独立检查点重跑。V2 为 `COMPLETED`，本地/远端均为节点 3,671、关系 2,107、六类类型、学段 `02=817/03=1,386/04=1,468`、重复名称组 110、孤立 2,267、未解析 parent key 479、最大入度 11、最大出度 19、最长链 21；缺失端点、重复边、自环、跨学科、跨学段、环、节点冲突及跳过关系均为 0，唯一安全问题为 `SOURCE_PARENT_UNRESOLVED=479`。
- 正式导入与发布边界：负责人确认 `02/03/04=PRIMARY/JUNIOR/SENIOR`、无年级适用整个学段、`status=0` 只保存不启用、parentId 只作外部分组键、六组类型规范为“知识点/题型”，并确认高中关系暂不完整。`KG_MATH_T10B_V3` 正式图谱 Import 完成，节点 3,671、关系 2,107、冲突/结构错误 0；类型分布为小学 277/540、初中 451/935、高中 775/693（知识点/题型），全部节点禁用。题目 Import 未执行；必须先评审 16,754 个复核来源，再单独确认正式题目导入和节点启用策略。

### 2026-08-21 小学/初中学段开放补充

- 契约与实现：OpenAPI 向前升级到 0.12.0，`QuestionStage` 开放 `PRIMARY/JUNIOR`，`QuestionGrade` 开放一至九年级；教师知识点树和题目列表增加学段/年级筛选，题目摘要增加学段。服务端统一校验学段年级组合、组合题父子上下文和知识点学段；历史 JSON/CSV 导入按年级推导学段。高中未进入题目契约，搜索接口对高中仍返回 422。
- 前端：六条既有教师题库路由不变。列表新增 URL 可恢复的学段/年级筛选；编辑器提供小学/初中选择和动态年级，切换学段或年级时清除不匹配知识点，组合题子题继承父题上下文；分页知识点选择器按当前学段查询，详情使用中文学段/年级。
- 数据：只新增并应用 `V7__enable_primary_and_junior_knowledge_points.sql`，没有新表。既有 `edu_platform_learning_dev` 从 V6 前进到 V7；聚合复核为小学启用 817、初中启用 1,386、高中禁用 1,468，先修关系仍为 2,107，来源节点 `parent_id/taxonomy_parent_id` 非空数仍为 0。没有创建题目、媒体、Seed 或伪数据。
- 自动化：本地 MySQL 条件验收通过并精确清理其随机 Fixture；H2 learning-service 测试、OpenAPI 客户端生成、前端类型检查和 Vitest 均通过。正式题目导入仍未执行，题目公共/教师所有权与媒体授权边界不在本次学段适配中扩张。

### 2026-08-21 SYSTEM/TEACHER 题库作用域与平衡样本正式导入

- 契约与权限：OpenAPI 升级到 0.13.0，题目和媒体增加 `SYSTEM/TEACHER`。教师列表只返回 SYSTEM＋本人 TEACHER 并支持来源筛选；SYSTEM 可读但教师写入固定 403。历史 JSON 创建/批次/清理收口到 `administration:access`，教师端仅保留旧任务只读审计与 CSV/XLSX 私有导入。学习端 SYSTEM 投影不读取/返回答案、解析或历史来源。
- 数据模型：Learning V8 为 `question_aggregate.scope` 和 `question_import_task.question_scope` 回填 `TEACHER`，Media V4 为 `media_asset.scope` 回填 `TEACHER`；新 SYSTEM 题目/子题直接 `ACTIVE`，新 SYSTEM 图片保留管理员 owner 且已认证学生/教师可申请短期授权。本任务未创建新数据库、未跨 Schema 建外键或 Join 业务实现，未改写既有迁移。
- 管理员预检：通过 Gateway 新建 45 个 SYSTEM DryRun 任务，44,441 条全部处理，`VALID=27,687`、`NEEDS_REVIEW=16,754`、`FAILED/SKIPPED=0`。它们与旧教师归属 DryRun 分离，检查点不含 Token、题干、答案或源目录绝对路径。
- 均衡计划：硬配额为每个可用年级/题型/难度 101，不足则全选，总上限 650，含图下限 325。计划为七/八/九年级 202/242/206，易/中/难 378/165/107，填空/组合/单选/简答/多选 149/111/273/110/7，含图题 483/650，不同知识点 1,208。源数据没有小学题或判断题；多选题仅 7 道，全部选入。
- 正式结果：650 条处理为 648 道 `ACTIVE SYSTEM` 根题、2 条 `EXTERNAL_IMAGE_TRANSFER_FAILED` 安全复核、普通失败 0。成功集合为七/八/九年级 202/240/206，易/中/难 378/164/106，填空/组合/单选/简答/多选 149/110/272/110/7；481/648（74.23%）含图，另创建 362 道 SYSTEM 组合子题和 1,910 个媒体引用。知识点禁用/跨学段绑定、owner 不匹配和题目字段外部主机残留均为 0。
- 图片缺口与重导：首轮验收发现 2 道答案 HTML 中的图片 URL 未被提取。解析器修复为答案与题干/解析使用同一安全清理/媒体转存逻辑，并新增专项测试。首轮任务精确清理 1,010 个题目聚合、650 个来源引用和 1,905 个媒体引用，释放事件由 Media 绑定台账消费；最终重导后题目字段外部 URL 为 0。1,910 个最终引用媒体全部存在、管理员 owner、`SYSTEM/READY/BOUND`，对象键外部主机残留为 0。
- 真实权限验收：教师 SYSTEM 筛选总数 648，详情 `editable=false`，状态改写为 403 `QUESTION.SYSTEM_READ_ONLY`；学生 SYSTEM 列表/详情成功且 JSON 不含 `answers/analysis`；教师/学生读 SYSTEM 媒体均为 200，学生短期访问授权为 201。凭据均经本地环境包装与 DPAPI 注入，未输出或落盘 Token/密码。
- 自动化门禁：Java 8 模块 `clean verify` 全部成功（Learning 38 项，2 跳过；Media 20 项，2 跳过；Gateway 11/11）；Pester 均衡样本 8/8；OpenAPI 客户端生成、全 workspace 类型检查、Vitest 18 文件 122 项和三端生产构建全部通过；`git diff --check` 无错误。
- 数据与清理：未创建伪数据；正式样本是用户批准的只读历史来源，用于来源/年级/题型/难度/知识点/含图筛选与学生/教师公共读验收。最终检查点位于未跟踪 `.m2/migration-checkpoints/system-import-kg-math-t10b-v3-sample650-answer-images.json`；`-Action Cleanup -CheckpointPath <该文件>` 可精确撤销未被后续修改的题目/引用，重建时重跑文档中的 Plan/Import 命令。任务与检查点保留作为审计证据，不清表。

### 2026-08-21 组合题原题版式与内联图片补充

- 问题根因：历史解析器把 `<img>` 替换为 `[图片N]` 文本，再把图片只按顺序挂到版本 media 列表；教师端又分开渲染父题、媒体和子题，因此原文位置和完整试题版式丢失。
- 契约与导入：OpenAPI 升级到 0.13.1，`stem/options/answers/analysis` 允许 `{{media:<uuid>}}` 稳定占位，但 UUID 必须是同一不可变版本已绑定的 `QUESTION_IMAGE`。历史 JSON 在题干、选项、解析和答案之间统一分配顺序，外部图安全转存成功后才替换为稳定 mediaId；未绑定、非图片或格式错误的占位在写入前返回 422。
- 前端：组合题详情保留子题聚合与顺序，但按“完整父题题干＋连续小问”在同一区域展示；父级答案和解析分别统一展示，小问编号之间换行。内联图片只对当前版本媒体申请短期授权，以 Blob URL 写入清洗占位元素，离页中止请求并撤销 URL。存量 `[图片N]` 按 media 顺序兼容恢复，无需删除或重导 648 道 SYSTEM 根题。
- 验证：learning-service `verify` 执行 39 项，37 项通过、2 个真实环境用例按开关跳过；OpenAPI 客户端重新生成，teacher-web 类型检查和生产构建通过，Vitest 18 文件 123 项全部通过。Vitest/Vite 在沙箱内因 `esbuild spawn EPERM` 未启动，经批准在沙箱外复跑通过。
- 数据边界：本补充没有新建或修改数据库、表、迁移、题目、媒体、OSS 对象或伪数据。当前 SYSTEM 样本、知识点绑定、导入任务和精确清理检查点全部保留。

### 2026-08-21 KaTeX 分式定位修复

- 根因与修复：旧管线先恢复 KaTeX HTML、再用 DOMPurify 全局删除 `style`，误删了分式 `vlist` 的公式特有高度和纵向定位参数，导致分子/分母跌出基线并被容器裁切。现改为先清洗用户 Markdown/HTML，再恢复 `trust=false` 的本地 KaTeX 输出和代码生成的稳定媒体占位；题目原文中的样式、事件、脚本和外链媒体仍不可信。
- 验证与数据：Vitest 18 文件 124 项全部通过；新增回归测试同时覆盖分式定位样式保留、用户 `position/color` 样式拒绝和 KaTeX 危险链接拒绝，并保留原有危险链接、iframe、脚本、图片和媒体占位测试。全 workspace 类型检查与教师端生产构建通过，仅有既有 hls.js 大 chunk 提示。当前桌面会话的浏览器连接组件初始化失败，未执行人工截图复核。本修复只修改教师端渲染与文档，没有改变 OpenAPI、Java、数据库、题目、媒体、OSS 对象、导入任务或伪数据，无需清理或重新导入。

### 2026-08-21 填空题空位显示补充

- 根因与实现：只读抽样 500 道历史填空题，仅输出标记聚合、不输出题干；其中 465 道在去除 HTML 标签后仍以连续 3 个以上普通空格表示空位，浏览器会折叠这些空格。教师端现仅对 `FILL_BLANK` 根题/组合子题题干，把标签外连续空格或下划线转换为固定 5em 底边线空位，并提供“填空处”辅助文本；答案、解析、普通题、HTML 属性和 KaTeX 内部不转换。
- 验证与数据：Vitest 18 文件 125 项、全 workspace 类型检查和教师端生产构建全部通过；自动化覆盖两个空位生成、标签属性不误转换和普通题不转换，构建仅保留既有 hls.js 大 chunk 提示。本补充没有修改 OpenAPI、Java、数据库、题目、媒体、OSS 对象、导入任务或伪数据，现有 648 道 SYSTEM 根题无需清理或重导。
- 发布：T10B 经 [PR #26](https://github.com/chengchen-ccnu/ccnu-edulion/pull/26) 合并到 `main@6d3d47ea1effd575a8a9a094c5aff73910f8a05b`；分支推送与 PR 事件的 Java、Python、Frontend、OpenAPI 共 8 项 CI 全部通过。合并动作没有再次运行知识图谱/题库 Import，没有新增或清理数据库记录、媒体、OSS 对象、RabbitMQ 消息或本地检查点。

## 2026-08-24 T11 学习与测评后端实施验证

- 基线与分支：开始时 `main`/`origin/main`/`HEAD` 均为 `e64255b1d5368460762a29accc49e4089a645fbd`，工作区干净且无开放 PR。旧 `codex/t11-learning-assessment-backend` 对 main 的 left/right 为 `23 0`，确认无独有提交后才 `merge --ff-only main`，未 reset/删除/覆盖。按要求完整阅读 API、事件、页面规格、T05/T08/T09B/T10B 交付和本地环境文档；页面路由职责未变，没有修改 page specs。
- 契约与边界：OpenAPI 0.14.0 定义报名/取消、我的报名、课程/课时进度、笔记 CRUD、教师测评/题目顺序/发布/归档、学生安全试卷、草稿/提交/结果/错题、人工批改和教师学情。学生试卷类型不含答案/解析/评分规则，结果再按 AFTER_SUBMISSION/AFTER_GRADED/NEVER 公布。学习/教学分别校验 `learning:access`/`teaching:access`，教师型账号可同时作为学习者；越权 owner 资源隐藏为 404，状态/revision 冲突为 409，字段/可用性校验为 422，Course 依赖故障为 503。
- 数据与消息：Learning 从真实 V8 正常应用 V9，新增报名、单调进度/事件、笔记、测评/课程分配/不可变题目快照、作答/答案、人工评分、错题及幂等表；不存在 V4，不启用 out-of-order/baseline/gate。Course V3 新增报名访问投影和消费幂等表。Learning 事务 Outbox 发布 `EnrollmentAccessChanged` v1，Course 主队列总计最多 3 次尝试、超限进 DLQ，按 eventId 幂等和 changedAt 防乱序，且 ACTIVE 投影实际用于报名制非试看播放判断。生产代码无跨 Schema 查询/Join/外键/共享 Mapper。
- 业务验证：H2 集成测试覆盖报名幂等/无效课程/能力化教师学习、进度重复/乱序/越界/并发、笔记 owner、教师课程 owner、SYSTEM/本人 TEACHER/他人私题、发布前归档拒绝和发布后快照不漂移、草稿恢复、并发/重复提交一次、单选/多选/判断/标准填空自动判分、解答题人工批改、批改 revision 冲突、成绩/错题幂等和答案防泄漏。Gateway 新路由与能力测试通过。
- 自动化结果：Java 8 模块 `clean verify` 全部成功，合计 105 项中 99 通过、6 个真实环境用例默认跳过、0 失败/错误；Learning 47 项中 44 通过/3 跳过，Course 16 项中 15 通过/1 跳过，Gateway 12/12。OpenAPI 客户端生成和前端 3 应用+5 共享包 typecheck 通过，`git diff --check` 通过。
- 真实验收与清理：通过凭据包装器在 MySQL 8 上正常应用 Learning V9/Course V3，RabbitMQ 持久主队列与 DLQ 存在，随机事件的 ACTIVE/CANCELED/重放/乱序验收通过并清理。双服务真实启动后，课程事实 HTTP -> 教师型账号报名 -> Learning Outbox `PUBLISHED` -> Course `ACTIVE` -> 取消后 `CANCELED` 链路通过。本轮课程 `08629e70-60f8-4ff1-9250-0297f3bd8a95`、课时 `a305ab82-acb1-4a76-b945-9213b4cff82a`、报名 `eb2de3da-99ed-447c-a5c0-7f0fe50af08e` 及相关 Outbox/消费/投影均按精确 ID 逆外键清理，`learningFixtureRows=0`、`courseFixtureRows=0`。未清表、未运行历史题库 Import，未删除/改写 648 道 SYSTEM 题、图谱、T10 任务、媒体或检查点。
- 剩余风险与发布：报名投影是最终一致，Outbox 未投递时播放短暂 403；FAILED Outbox/DLQ 需运维告警与人工重放。首期不含支付、监考/复杂防作弊、多选部分分、LLM 判分或弱知识点推断。T12A/B/C 分别依赖报名进度笔记、测评闭环和教师学情投影，不得发明平行路由或手写 DTO。契约/迁移、实现/测试、交付证据关键提交分别为 `57b1f1f`、`e8ecbbd`、`7e048d1`；Ready [PR #28](https://github.com/chengchen-ccnu/ccnu-edulion/pull/28) 已创建，实现 head `7d39d6f` 对应 [CI run 32717288978](https://github.com/chengchen-ccnu/ccnu-edulion/actions/runs/32717288978) 的 Java、Frontend、Python、OpenAPI 四项全部通过。本任务不自行合并，等待总控验收。

## 2026-08-26 T11 合并与 T12A 前置审计

- 合并与清理：[PR #28](https://github.com/chengchen-ccnu/ccnu-edulion/pull/28) 在 Ready、可合并且 Java、Frontend、Python、OpenAPI 检查全部成功后合并为 `main@73fc35f0581e534e7a31718fce7fd01a9faf7a78`；本地 `main` 已快进同步，`codex/t11-learning-assessment-backend` 本地和远端分支已清理。
- 前置结论：T08B 已合并并提供既有 `/learn/:courseId/:lessonId` 播放器、目录和稳定导航壳；T11 已合并并冻结 OpenAPI 0.14.0 的报名、我的报名、单调进度和笔记契约。因此 T12A 的代码、契约、路由和数据前置均满足，可以从最新 `main` 准备 `codex/t12a-learning-progress-web`。
- 实施边界：T12A 只启用既有 `/my-learning`，在课程详情接通报名/继续学习，并在既有学习页增加进度和笔记；不得搬迁播放器、创建第二套学习页、手写 DTO，或提前实现 T12B 测评、T12C 学情、T15 AI 与 T17 门户内容。必须复用 `learning:access`，同时具备教学能力的账号仍可作为学习者。
- 验收边界：后续 T12A 必须覆盖深链接/刷新、匿名回跳、能力不足、loading/empty/partial-error、401/403/404/409/422/429/offline、重复/乱序进度、笔记所有权、报名最终一致窗口、响应式、键盘操作，以及离页后的播放器、请求、定时器和临时 URL 清理；不得用 Mock 成功替代真实 Gateway、Course、Learning 和媒体播放链路。
- 数据：本次总控只执行 Git/文档状态更新和只读前置审计，没有连接或修改数据库、Redis、RabbitMQ、OSS 或 FFmpeg，没有创建、修改或清理表、消息、对象、Seed、伪数据及迁移检查点。

## 2026-08-26 T12A 学习与进度前端实施验证

- 范围与架构：启用 `/my-learning` 固定导航槽位，分页读取本人 ACTIVE 报名并独立组合 Course 发布详情与 Learning 进度；课程详情读取真实报名/进度并提供报名、开始/继续学习；既有 `/learn/:courseId/:lessonId` 原位增加单调进度与本人笔记。播放器仍只在学习页，测评、教师学情、AI、门户/通知/资料均未提前实现。收藏因 OpenAPI 0.14.0 没有契约而保持隐藏，不手写 DTO 或伪数据。
- 能力与失败边界：`/my-learning` 和 `/learn/...` 都只声明 `learning:access`，不要求 `STUDENT`；统一工作台选择测试证明 TEACHER 型账号同时具有 learning/teaching 能力时得到学习与教学两个入口。页面/适配器覆盖 loading、empty、单卡 partial-error、401/403/404/409/422/429/503/offline；429 最多两次退避且进度重放复用相同 `clientEventId`/请求体，409 笔记编辑保留用户输入，报名投影最终一致 403 不做无界轮询。
- 自动化：`pnpm.cmd --dir web-apps install --frozen-lockfile --offline` 完成且锁文件无漂移；`pnpm.cmd --dir web-apps run typecheck` 通过 3 应用和 5 共享包；`pnpm.cmd --dir web-apps run test` 的 23 文件 139 项全部通过；`pnpm.cmd --dir web-apps run build` 三端通过，仅保留既有 523.17 kB HLS 动态 chunk 提示；`pnpm.cmd --dir web-apps run check:api` 与 `git diff --check` 通过。新增测试覆盖生成 Gateway 路径、报名/进度/笔记、刷新会话失效映射、429 请求体复用、墙钟观看增量、暂停/结束、重复/乱序单调合并、离页中止、能力路由与跨任务边界。
- 真实链路：实际启动 Gateway/IAM/Course/Learning/Media（8080～8084，全部 `UP`）与学生 Vue，浏览器只经 Gateway 和后端短期媒体授权。匿名开始学习保留完整深链接；登录后 PUBLIC 课程幂等报名，HLS 只有一个 video，`readyState=4`、92.04 秒、未静音。实际播放约 29 秒得到位置 28/累计 29 秒；笔记创建后 revision 0、编辑后 revision 1。刷新同一深链接后登录、28 秒位置与笔记恢复；`/my-learning` 展示真实报名/最近 0:28/继续学习，课程详情无 video 且显示继续学习。
- 响应式/键盘/资源：390×844 下 `/my-learning` 和学习页的 `scrollWidth` 均等于 `clientWidth=375`，无横向溢出；固定导航、高亮、原生键盘控件与 `:focus-visible` 轮廓有效。浏览器 warning/error 为 0。路由切换/离页会中止 Course/Learning/Media 请求、429 等待和进度队列，并清理 HLS、刷新定时器和 video 源；测试与源码边界断言覆盖这些清理路径。
- 数据与清理：仅复用四个用途明确的 dev Schema、既有 `student_test_01`、课程/课时/媒体。创建 1 报名、1 聚合进度、2 进度事件、1 T12A 笔记；播放另产生 5 Media 授权/nonce/临时清单，认证产生 7 刷新会话和 4 登录审计。结束时按精确 actor/course/enrollment/time/Object Key 删除临时 OSS 清单和四个 Schema 本轮记录，残留审计逐表为 0；没有新建数据库/表/迁移/Seed/账号/课程/媒体资产，没有清空表或列举 Bucket。验收服务端口 5173、8080～8084 均已停止。
- 可读性与风险：新增 Vue 页面、API 适配器、进度队列和共享播放器跟踪器具有中文职责/输入/输出/权限/失败/副作用注释，生成代码未手工修改。报名投影仍有短暂最终一致窗口；`hls.js` 体积提示和缺少收藏契约记录在交付文档。实现/测试/验收提交 `dd491c4` 已推送到 `codex/t12a-learning-progress-web`，Ready [PR #30](https://github.com/chengchen-ccnu/ccnu-edulion/pull/30) 已创建；本任务不自行合并。

## 2026-08-26 T12A 进度语义、播放平滑度与学习卡片复核

- 产品语义：课时页移除整课完成度、保存位置和累计观看时长，只使用媒体实际 duration 与当前位置显示“本视频进度”百分比；整课完成度继续在 `/my-learning`，并补充到课程详情。进度持久化协议、15 秒墙钟样本和自然结束完成课时规则未改变。
- 播放诊断：本地配置确认授权 TTL 仍为 10 分钟。只读下载 media `1131a27a-7299-4ef1-8e59-77ba5fcd06df` 后，9×10 秒＋2.04 秒 MPEG-TS 清单完整解码，25fps 时间轴连续，源/HLS 无黑帧或亮度突变，切片 GET 50～427ms。真实整段复播在约 10/35/58/83 秒及 20/44/67 秒重复闪动，但始终 `readyState=4`、未 pause/seek/换 URL；隔离进度回写、ManagedMediaSource 与 HLS Worker 后时间点不变。已排除授权刷新、进度保存、Vue 重挂载、源画面、解码损坏和明显下载不足，问题收敛到 MPEG-TS 经 Chromium MSE 缓冲窗口切换；fMP4 HLS v2 留待下一轮实现与复验。
- UI：`/my-learning` 改为紧凑横向学习记录卡，状态、课程完成度、最近位置与主次操作形成稳定层级；按钮统一使用 inline-flex 双向居中并让卡片操作等宽，移动端回落为单列。课程详情增加紧凑完成度区，不把播放器搬回详情页。
- 自动化：OpenAPI 生成后的全 workspace typecheck 通过；Vitest 23 文件 140 项通过；三端生产构建通过，仅有既有 HLS chunk 体积提示。沙箱内 Vitest/Vite 因 esbuild `spawn EPERM` 未启动，同命令在批准的沙箱外通过。只读媒体诊断临时源码与临时下载目录均已删除，没有新增或修改数据库、表、迁移、Seed、业务数据或 OSS 对象。
- 待续：`/my-learning` 与进度语义/UI 已完成复核；视频闪动仍未关闭，不能标记为最终验收通过。下一轮从版本化 fMP4 HLS（init.mp4/m4s 登记、清单短期 URL 重写、v1 兼容、最小开发资产重处理）继续，同一播放器整段无闪后再关闭该风险。

## 2026-08-27 T12A fMP4 播放链路收口

- 根因与实现：旧 `hls-v1` 的 MPEG-TS 在 Chromium MSE 缓冲窗口切换附近复现约 20～25 秒间隔的闪帧；本轮新增版本化 `hls-fmp4-v2`，FFmpeg 生成 H.264/AAC、关键帧对齐的 `init.mp4`/`.m4s` 与 HLS v7。Media 对普通媒体行和 `EXT-X-MAP` 统一规范化、登记和短期签名；Course 优先 v2，只有明确 404 才使用新 jti 回退 v1。外部 OpenAPI、浏览器 DTO、播放器实例和学习路由均未复制或改变。
- 自动化：受影响 Java Reactor 测试通过；新增 fMP4 命令、清单解析/授权、HTTP v2 与旧 v1 权限、Course 404 单次回退覆盖。4 秒真实 FFmpeg 冒烟产出 init+2 个 m4s，`ffprobe` 识别 H.264/AAC。完整真实 Media 验收中的 v2 处理、OSS 上传和内部授权已通过，但套件最后仍停在既有 RabbitMQ unacked-redelivery 共享队列时序断言，因此如实记为部分通过而非全绿。
- 真实资产与浏览器：仅对既有 dev media `1131a27a-7299-4ef1-8e59-77ba5fcd06df` 运行显式门控、幂等的 `T12AFmp4BackfillAcceptanceTest`，真实 MySQL/OSS/FFmpeg 1 项通过。登录后的既有 `/learn/5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057/18d2d6cc-f374-46b1-ad81-743f169d0ce9` 请求 `hls-fmp4-v2.m3u8`，从 0 连续播放到 92.12 秒结束；始终一个 video、`readyState=4`、无 pause/seek/URL 更换或控制台错误。刷新后仍恢复 100%、duration 92.178667 秒。自动检查无法度量单帧主观亮度，保留同一资产供用户目视确认。
- 数据与边界：没有新建 Schema、表、迁移、Seed、账号、课程、课时或源媒体。仅保留上述 mediaId 的一个 v2 处理任务、一个变体、init/m4s 组件及对应精确 OSS 派生对象、消费/Outbox/审计记录；旧 v1 和源文件未动。清理须按 mediaId+variant 精确 Object Key 与外键顺序执行，重新生成使用同一显式门控测试。历史 v1 资产的批量迁移、T12B/T12C/T15/T17 均不在本轮范围。

## 2026-08-27 T12A 合并与 T11A/T12B 前置审计

- 合并与清理：[PR #30](https://github.com/chengchen-ccnu/ccnu-edulion/pull/30) 在 Ready、可合并且最新两组 Java、Frontend、Python、OpenAPI 检查全部成功后合并为 `main@a2a9c95dc2639c63f337e2881bc7c930f370e4af`；本地 `main` 已同步，`codex/t12a-learning-progress-web` 本地和远端分支已清理。完整 Media 真实套件中既有 RabbitMQ 共享队列时序断言仍被如实保留，但 fMP4 处理/OSS/授权由独立真实用例、集成测试和整段浏览器播放覆盖，不阻断本次合并。
- T12B 阻断证据：OpenAPI 0.14.0 的学习者测评列表只返回 `AssessmentSummary`，没有当前 `attemptId/status`，刷新后无法区分或恢复可参与、进行中、已完成；错题投影只有 ID/次数，没有安全题目内容、掌握状态或重做命令；`EXAM` 没有可选开放时间、时限及服务端时间语义；结果依赖仍处于 PUBLISHED 的试卷读取，归档后缺少自包含不可变题目展示；教师私有题媒体又只允许资产 owner 读取，学生无法凭测评访问事实取得题图。现有 Git、版本号、主键、事务、唯一约束、TypeScript 类型与普通前端测试都不能生成这些缺失的领域事实或授权，因此不得让 T12B 猜字段。
- 调整结论：新增最小 T11A“测评前端契约闭环”，先以 OpenAPI 为事实源补齐上述读写与短期媒体授权，再向前演进 Learning/Media/Gateway 和生成客户端；不拆 learning-service，不引入第二套 OSS 客户端，不实现 Vue、监考、复杂防作弊或无限重考。T09B 与 T11 均已合并，T11A 前置满足；T12B 改为依赖 T11A 和已合并的 T12A。
- 后续验收重点：T11A 必须验证列表状态与刷新恢复、一次性提交/有限重做边界、考试时间由服务端裁决、归档后本人结果可读且答案发布策略不泄漏、错题 owner/掌握幂等，以及教师私有题媒体仅对获授权测评学习者短期可读；401/403/404/409/422/429/503 与媒体令牌防重放保持现有安全语义。
- 数据：本次总控只执行 Git 合并、只读代码/契约审计和文档规划，没有连接或修改数据库、Redis、RabbitMQ、OSS 或 FFmpeg，没有创建、修改或清理表、消息、对象、Seed、伪数据及迁移检查点。

## 2026-08-27 T11A 测评前端契约闭环

- 契约优先：OpenAPI 从 0.14.0 向前演进到 0.15.0 后才实现服务端并重新生成客户端。学习者列表现在返回服务端时间、当前 attempt ID/状态、参与状态、恢复/结果布尔值；新增草稿恢复、归档结果安全试卷、错题详情/掌握/最多三次重做和测评题目媒体授权。创建/更新/摘要补齐仅 EXAM 可用的开放、截止和限时字段。
- 时间与不可变性：开始作答时冻结 `startedAt/deadlineAt`，deadline 取全局截止与开始+限时较早者；开放前、截止后和草稿超时均由服务端拒绝。超时提交忽略客户端后补答案，仅结算最后保存草稿。提交仍只成功一次；错题重做是独立不可变记录，不修改或重开原 attempt。
- 权限与防泄漏：列表/草稿/结果/错题均按本人，教师组卷/发布/批改按 owner；归档或取消报名后本人仍可按历史 attempt 读取自包含安全结果。学习者题目 DTO 不含答案、解析或评分规则，结果答案继续服从公布策略。测试覆盖他人 404、报名/测评访问、409 revision/重复提交、422 输入和依赖 503；401/403/429 由 Gateway/统一 OpenAPI 边界保持。
- 媒体授权：Learning 只从冻结试卷确认 mediaId/用途并复用既有 `HttpMediaClient`，不接 OSS SDK。内部 JWT 绑定 learning issuer、audience、visitor、mediaId、用途、`ASSESSMENT_QUESTION`、`ORIGINAL`、disposition、traceId、jti；Media 将 course/learning issuer 与各自 accessType 严格绑定并用既有 nonce 表防重放。浏览器只获得短期读 grant，永不获得服务 JWT、密钥、Bucket 或 Object Key。
- 数据演进：新增 Learning V10，补 `assessment` 时间、`assessment_attempt` 开始/截止、`wrong_question` 掌握/revision/重做计数和 `wrong_question_redo`；不修改已合并迁移，不新增跨 Schema 外键/查询/Join。真实 MySQL 与真实 OSS/RabbitMQ 验收的最终结果、精确 Fixture/清理见 `docs/delivery/T11A-assessment-ui-contract.md`。
- 自动化：完整 Java Reactor 8 模块通过；Gateway 12/12、Learning 52 项（4 个真实项按开关跳过）、Media 25 项（4 个真实项按开关跳过）无失败。OpenAPI 客户端重新生成，前端 8 workspace 类型检查、23 文件 140 项测试和三端构建通过。真实 MySQL 验收 4 项中 3 项通过、旧 root 用例跳过；隔离的真实题图 MySQL/OSS/learning issuer/jti 重放验收 1/1 通过。完整 Media 综合真实用例仍在既有 RabbitMQ unacked-redelivery 时序断言失败，关键 T11A 链路已在该断言之外独立通过，未将综合用例误记为全绿。
- 边界：没有实现 T12B Vue 页面、监考、复杂防作弊、无限重考、第二套媒体客户端、支付、LLM 判分、hash gate、baseline、contract freeze 或额外限流器。T12B 必须等待本 PR 合并，只消费生成客户端并遵守既有四条测评路由职责。
- 发布：关键实现提交 `f3c6801` 已推送，Ready [PR #32](https://github.com/chengchen-ccnu/ccnu-edulion/pull/32) 已创建；本任务不自行合并。

## 2026-08-27 T11A 合并与 T12B 前置审计

- 合并与清理：[PR #32](https://github.com/chengchen-ccnu/ccnu-edulion/pull/32) 在 Ready、可合并且路由文档修正后的最新两组 Java、Frontend、Python、OpenAPI 检查全部成功后合并为 `main@981d08736610be90221ce233925dda9b5da599d1`；本地 `main` 已同步，`codex/t11a-assessment-ui-contract` 本地和远端分支已清理。
- 前置结论：T09B 已冻结结构化题目/组合题与不可变版本，T11/T11A 已提供 OpenAPI 0.15.0 的测评列表状态、草稿、一次性提交、归档结果、错题掌握/有限重做、题图短期授权和教师组卷/发布/批改，T12A 已稳定学生导航及学习页。生成客户端包含全部对应路径，T12B 的契约、路由、权限和数据前置均满足。
- 页面边界：T12B 只启用既有学生 `/practice`、`/practice/:assessmentId`、`/attempt/:attemptId/result`、`/wrong-questions`，以及教师 `/teacher/assessments` 和课程编排中的测评绑定；不得把作答或结果塞回课程详情，不得复制学习页/播放器、手写 DTO，或提前实现 T12C 学情、T15 AI、监考和复杂防作弊。
- 验收重点：后续必须覆盖刷新恢复、服务端时间/截止裁决、自动保存与离页清理、一次性提交、答案发布策略、归档结果、错题三次重做/掌握 revision、教师 owner/题目版本/发布/批改冲突、测评题媒体短期授权、401/403/404/409/422/429/503/offline，以及手机学生端、平板教师端和键盘可访问性；真实流程只能经 Gateway 和生成客户端。
- 数据：本次总控只执行 Git 合并、只读前置审计和文档状态更新，没有连接或修改数据库、Redis、RabbitMQ、OSS 或 FFmpeg，没有创建、修改或清理表、消息、对象、Seed、伪数据及迁移检查点。

## 2026-08-27 T11B 规格冻结与 T12B 前置重审

- 阻断证据：OpenAPI 0.15.0 的 `AnswerPublicationPolicy` 只有 `AFTER_SUBMISSION/AFTER_GRADED/NEVER`，`AttemptResult` 没有独立的成绩/参考答案公布事实，测评状态也没有教师不可撤销的整场关闭命令、受影响人数、待结算数量或公布资格。前端即使隐藏元素，响应仍可能携带分数、对错、参考答案或解析；Git、版本号、主键、事务、唯一约束、TypeScript 类型和普通组件测试均不能生成缺失的服务端授权事实。因此原“T12B 只消费 T11A”前置结论失效，必须先增加最小 T11B，而不是让前端猜字段。
- 关闭规则：教师只能关闭本人测评；关闭不可撤销，页面未来需展示进行中人数并二次确认。服务端关闭后立即拒绝新 attempt 和新答案，只以最后成功保存的草稿做有界、幂等、可恢复结算；不得用无限重试或无界同步大事务。待结算清零前不能错误标记整场结果就绪。
- 公布规则：成绩与参考答案/解析是两个独立、不可撤回的公布事实。普通练习可在个人提交后自动公布答案；作业/考试关闭前绝不公布答案。参考答案可在关闭且批改完成后自动公布、关闭后手动公布或永不公布；手动公布答案不要求完成批改。成绩只能在关闭、草稿结算完成且全部人工批改完成后自动或手动公布。首期不做逐学生、定时公布、撤回、批量评分或复杂批注。
- 结果安全：提交后始终允许本人查看提交回执、答案和提交时间。未公布成绩时，服务端不返回总分、逐题分数、对错或可反推评分的内容；未公布答案时不返回参考答案和解析。只公布成绩、只公布答案、全部公布三种视图分别按事实投影。公布后可重新批改并保留审计，学生直接看到最新分数和最后更新时间；revision 冲突返回 409，不静默覆盖。
- T12B 交互冻结：一个 T12B 任务启用既有四条学生路由和 `/teacher/assessments`，不新增平行路由。作答按主问题分块并保持组合题连续，支持题号导航、前后题、答题状态和整卷概览；自动保存采用稳定请求标识、变化防抖、定时兜底和有限重试，考试按服务端时间锁定并仅结算最后保存草稿。结果页用同一安全结果支持 `view=all` 与 `view=single&question=<序号>` 深链恢复；错题在当前页调用独立 redo，不重开原 attempt。教师端用可恢复查询参数承载列表、编辑、组卷、发布、结果和逐份批改，课程编排只跳转携带上下文。
- 演示数据：T12B 的临时边界数据必须精确清理；另可提供明确标记、生产禁用的幂等 local seed，从固定既有 SYSTEM 题 ID 建立最小普通练习、限时测评、客观题、主观题、组合题、错题重做和待批场景，并提供独立清理/重建命令。固定题不存在时明确失败，不随机抽题、不复制题干答案。
- 调整结论：先独立合并本次总控文档，再从最新 `main` 实施 `codex/t11b-assessment-result-release`。T11B 契约、实现、生成客户端和安全验证合并前，T12B 保持阻断；T11B 不实现 Vue，T12B 不修改后端补洞。
- 数据与范围：本次总控只修改页面规格与路线图并执行 Git 编排，不修改 OpenAPI、Java、Vue、数据库迁移或生成客户端；没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 或外部模型，没有创建、修改或清理 Schema、表、消息、对象、Seed、伪数据及迁移检查点。

## 2026-08-28 T11B 测评关闭与结果公布契约实施验证

- 契约与边界：OpenAPI 先从 0.15.0 向前演进到 0.16.0，新增 owner 范围内的不可撤销关闭、成绩公布和答案公布命令，并扩展关闭/结算、待结算/待批计数、公布资格/时间及两个结果可见性字段。客户端从同一 OpenAPI 重新生成；未创建 T12B Vue 页面、前端路由或第二套 DTO。
- 关闭与结算：关闭命令校验 `teaching:access`、owner、资格、`clientRequestId` 和 `expectedRevision`，事务冻结 DRAFT 影响数后立即阻断新 attempt、草稿恢复和答案写入。后台每轮最多 10 场、每场 50 份，只按最后成功保存答案结算；确定性结算请求、事务回滚和数据库状态支持恢复，1/5/30/120/300 秒有限退避在第五次后进入 FAILED，待结算清零前 `resultsReady` 始终为 false。
- 公布与安全：成绩和答案分别单向公布。`AFTER_SUBMISSION` 只允许 PRACTICE；作业/考试关闭前无法泄题；手动答案公布只需关闭且结算清零，成绩公布还要求人工待批清零。学生响应通过 `gradeVisible/answerVisible` 与显式 null 实现均未公布、仅成绩、仅答案、全部公布四态，序列化断言确认未授权值不在响应体中；归档后本人仍按 attempt owner 读取冻结结果。
- 重新批改：成绩公布后允许基于最新 attempt revision 追加人工评分审计和更新总分/`gradeUpdatedAt`，不撤回公布、不改变原公布时间、不删除历史；陈旧 revision 返回 409。
- 自动化：Learning 定向 11 项 T11/T11A/T11B 集成用例通过；最终完整 Java Reactor `clean verify` 的 8 个模块全部成功，Gateway 12/12、Learning 57 项中 53 项通过且 4 项按真实环境开关跳过、Media 25 项中 21 项通过且 4 项按真实环境开关跳过。OpenAPI 生成和全 workspace 类型检查通过；Vitest 在批准的沙箱外 23 文件 140 项通过，三端构建通过，仅有既有 HLS chunk 体积提示。沙箱内 Vitest/Vite 的首次 `spawn EPERM` 属执行环境限制，未误记为业务失败。
- 真实数据：经 `scripts/Invoke-WithLocalEnvironment.ps1` 在明确的 `edu_platform_learning_dev` 将 Flyway V10 正常迁移到 V11。MySQL 验收 4 项中 3 项通过，需 `LEGACY_QUESTION_ROOT` 的旧题库用例按设计跳过；随机 EXAM、attempt、wrong、redo Fixture 按精确 ID 逆序删除并逐项为 0。没有创建数据库、业务表或 local seed，没有修改现有正式题库、课程、用户、媒体、消息或对象；只保留 V11 Schema/Flyway 历史。
- 后续：T12B 可在本 PR 合并后只消费 OpenAPI 0.16.0 生成客户端实现既有测评路由。持续五次失败的结算保留 FAILED 供运维诊断，首期没有管理恢复 API；容量压测和动态批量调参留待后续。
- 发布：关键实现提交 `8d84b92` 已按规定使用 `git push origin HEAD` 推送；Ready [PR #35](https://github.com/chengchen-ccnu/ccnu-edulion/pull/35) 已创建，目标为 `main`，本任务未自行合并。

## 2026-08-28 T11B 合并与 T12B 前置审计

- 合并与清理：[PR #35](https://github.com/chengchen-ccnu/ccnu-edulion/pull/35) 在 Ready、可合并且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后合并为 `main@a254641`；本地 `main` 已同步，`codex/t11b-assessment-result-release` 本地和远端功能分支均已清理。
- 前置结论：T09B 已提供结构化普通题/组合题和不可变版本；T11A 已提供参与状态、服务端时间、草稿恢复、错题 redo、归档结果和题图授权；T11B 已提供 OpenAPI 0.16.0、整场关闭、草稿有界结算、成绩/参考答案独立公布、资格计数、重新批改审计及四态安全结果；T12A 已稳定学习导航和课程学习页。因此 T12B 的代码、契约、路由、权限和数据准备机制前置均满足。
- 实施边界：T12B 作为一个任务只启用既有 `/practice`、`/practice/:assessmentId`、`/attempt/:attemptId/result`、`/wrong-questions`、`/teacher/assessments` 以及课程编排中的测评跳转；只消费生成客户端，不修改 T11B 后端补洞，不复制学习页/播放器，不提前实现 T12C、AI、逐学生/定时公布、撤回或复杂监考。

## 2026-08-29 T12B 测评闭环前端实施与真实验收

- 路由与边界：学生四条冻结测评路由和固定“练习中心”槽位已启用，作答/结果/错题共用高亮；学习页只按 courseId 查询并在返回结果内筛 lessonId。教师只使用 `/teacher/assessments`，assessmentId/panel/courseId/lessonId/status/page 由 URL 恢复，课程编排只跳转。没有平行详情、课程详情作答、复制播放器、T12C、AI 或占位成功页。
- 契约与安全：OpenAPI 保持 0.16.0，前端 DTO 全部由生成 Schema 派生。结果严格以 `gradeVisible`/`answerVisible` 展示四态；未授权成绩、判分、答案和解析不进入 DOM。题目内容经 Markdown 清洗和严格 KaTeX 渲染，题图先经测评媒体授权再下载为临时 Blob，离页中止请求并撤销 URL。教师只显示 learnerId，不伪造姓名或历史评语。
- 学生闭环：练习中心显示可参与/进行中/已完成与服务端时间；作答支持主问题连续区块、组合题有序小问、全部契约题型、题号导航和整卷概览。防抖＋定时兜底保存复用稳定请求 ID/请求体，429/离线/5xx 有限重试且旧响应不能覆盖新状态；考试按 serverTime/deadlineAt 校准、刷新恢复、到期锁定并只提交最后成功草稿。结果 `view=all`/`view=single` 深链复用同一 DTO；错题掌握和最多三次 redo 不重开原 attempt。
- 教师闭环：真实页面完成草稿创建/编辑、SYSTEM 组合题＋简答题精确版本组卷、调序计分、发布、关闭影响数确认、PENDING→COMPLETED 结算、待批计数、逐份批改、成绩/答案独立不可撤回公布、公布后重批、409 保留 6.5/5.5 与本地评语后载入最新 revision 重提，以及归档。联调发现并修复成功后旧 422 提示残留、results 面板被残留 attemptId 拉回 grading 两项页面状态缺陷。
- nullable 快照：真实组合题冻结暴露知识点 parentId/grade 和历史来源 jsonEdition/jsonGrade 允许为空却在 JSON 往返时被省略；经用户授权只在现有 DTO 序列化边界显式保留 null，并增加 H2 快照往返测试。OpenAPI、Learning 状态机、数据库和迁移均未改变。
- 演示数据：新增显式 local、生产禁用的幂等 Seed，只允许 `edu_platform_learning_dev`，从三个固定 `KG_MATH_T10B_V3` source ref 解析 SYSTEM 单选/简答/含图组合题。保留 6 场 assessment、6 份 attempt、10 条冻结题快照、2 条错题/redo 与可选的一条 Seed 自有 ACTIVE 报名；`Initialize/Cleanup/Rebuild` 均经凭据包装器执行并按固定 ID/owner 精确处理。随机临时教师验收 assessment 及其分配、草稿、答案和批改记录已事务删除，复查剩余 0；最后 `Rebuild` 恢复固定演示初态。
- 自动化：`install --frozen-lockfile`、`generate:api`、`check:api`、`typecheck` 全部通过；Vitest 29 文件 191 项全部通过；三端生产 build 通过，只保留既有大 chunk 提示。`mvn ... clean verify` 八个 Reactor 模块全部成功（2 分 09 秒），Gateway 12/12、IAM 9/9、Course 17 项中 1 跳过、Learning 59 项中 5 个显式门控项跳过、Media 25 项中 4 个显式门控项跳过，无失败。test/build 沙箱内因 Windows esbuild `spawn EPERM` 无法启动，批准后在沙箱外以同一命令通过。
- 真实浏览器：学生 6 场状态、四种结果、双视图、草稿刷新、完整提交、错题主观 redo、掌握更新和 15→5→0 秒考试截止均通过；教师完整闭环和刷新 query 恢复通过。学生 375 px、教师 1024/1440 px 均无横向溢出，控制台 warning/error 为 0且无敏感信息。用户最终以真实键盘确认 Tab/Shift+Tab、Enter/Space、关闭确认、标签切换、题号导航和错题入口均正常，焦点顺序合理且轮廓可见。
- 题图复验：题图授权经 Gateway 成功，真实对象下载为 HTTP 200、`image/png`、2810 bytes；开发 OSS Bucket 配置精确学生/教师前端 origin 的 GET/HEAD CORS 后，用户确认浏览器 fetch→Blob 与题图展示正常。页面继续使用短期授权和临时 Blob URL，未改成签名直链、Gateway 文件代理或其他绕过。
- 发布：关键实现提交 `71ff6b46612f374823122f77ca3bcb6338db478a` 已按规则使用 `git push origin HEAD` 推送；Ready [PR #37](https://github.com/chengchen-ccnu/ccnu-edulion/pull/37) 已创建，目标为 `main`，本任务不自行合并。
- 验收重点：必须覆盖稳定请求标识的自动保存与有限重试、服务端截止锁定、最后成功草稿提交、组合题连续展示、结果双视图深链、四种公布组合、防泄漏、页内错题 redo、不可撤销关闭二次确认、待结算/待批资格、重新批改 409、401/403/404/409/422/429/503/offline、手机学生端、平板教师端、键盘可访问性和离页清理。临时验收数据须精确清理；可选 local seed 只能引用固定既有 SYSTEM 题并提供幂等清理/重建，题目缺失时明确失败。
- 数据：没有新建或修改 Schema、表、迁移、RabbitMQ 消息、OSS 对象、课程、课时或账号。教师真实验收创建的随机 assessment 及关联作答/批改数据已按精确 ID 事务删除并复查剩余 0；随后执行 Seed `Rebuild`，仅在 `edu_platform_learning_dev` 保留可由 `Cleanup` 精确删除、由 `Initialize/Rebuild` 幂等恢复的固定 T12B 演示数据。

## 2026-08-29 T12B 合并与网课基础能力/Redis 路线收口

- 合并与清理：[PR #37](https://github.com/chengchen-ccnu/ccnu-edulion/pull/37) 在 Ready、可合并且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后合并为 `main@5d866ba`；本地 `main` 已快进同步，`codex/t12b-assessment-web` 本地和远端分支均已清理。T12B 更新为已完成、已合并。
- 缺口证据：总体架构建议稿曾提及注册、找回密码、收藏、评价、图文/课件、字幕、多码率、Redis 缓存/会话/进度等能力，但 OpenAPI 0.16.0 身份契约仍只有登录、刷新、退出和当前用户，页面规格无注册/找回/账号安全/教师申请路由，Java 服务也只有 Redis 依赖、配置和健康检查，没有 `RedisTemplate`、Spring Cache 或 Gateway Redis 限流的业务使用。因此这些能力不能继续被视为会由 T16/T18 自动补齐。
- 产品收口：总体架构和页面规格明确学生自主注册、角色中立身份入口、普通账号申请教师、管理员不可公开注册、密码生命周期、会话版本、用户/分类管理、收藏评价、免费课程取消报名、图文/课件、播放器字幕/清晰度、HOMEWORK 时间/迟交/次数、教师成绩册及公共协议页面。新路由归入既有固定导航高亮组，不增加第六个学生主导航或第二套登录表单。
- Redis 收口：T12D 负责 Gateway 多实例身份/高风险接口限流、验证码、短期锁定和会话版本缓存；T12G 负责普通播放心跳缓冲及可靠批量落库；T14 复用用户额度、并发和会话预算；T16A 负责热点 Sorted Set、课程/教师/首页缓存、通知未读计数、事件失效和重建。MySQL 继续保存账号、Refresh Session、申请、发布、进度完成、作答和成绩事实；Redis 锁不替代数据库事务、唯一约束或正式提交幂等。
- 任务顺序：新增 T11C、T12D～T12G、T16A，并把 T12D 设为当前下一实施任务；T12C 优先级后移。T12D 的 T02 Redis 基础、T04 IAM/Gateway/统一登录、T07 `TEACHER_QUALIFICATION` 媒体能力均已合并，OpenAPI/页面/backlog 缺口已由本次文档任务冻结，因此其前置条件满足。T12D 只做契约和后端，不提前实现 T12E 页面、T16 审核决定或绑定短信供应商。
- 验证与数据：本任务只修改总体架构、页面规格、backlog 和本验证记录；`git diff --check` 通过。未修改 OpenAPI、Java、Python、Vue、数据库迁移或配置，未连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg、外部模型或短信/邮件服务，没有创建、修改或清理 Schema、表、消息、对象、Seed、伪数据及迁移检查点。
## 2026-08-29 T12D 账号生命周期与 Redis 身份安全后端验证记录

- 门禁与契约：开始时分支为 `codex/t12d-account-lifecycle-security` 且工作区干净；完整阅读仓库规则、页面规格、backlog、OpenAPI、工业架构设计及 IAM/Gateway/Media 既有实现。先将 OpenAPI 向前演进至 0.17.0，再实现生产者、消费者和生成客户端，没有手写第二套前端 DTO。
- IAM：实现学生注册与协议事实、一次性验证码挑战、不可枚举密码找回/重置、修改密码、当前/全部设备退出、持久 `sessionVersion`、设备 Refresh Session、教师申请提交及管理员查询/解锁/禁用/强制下线。教师申请只绑定本人 READY 的 `TEACHER_QUALIFICATION mediaId` 并保持 `SUBMITTED`，不授予教学能力、不实现 T16 审核。
- Redis 与 Gateway：验证码/发送冷却、登录失败计数与 TTL 锁定、会话版本缓存和多实例 Lua 限流均使用 Redis；MySQL 保持唯一身份、会话、协议、申请、版本和审计权威事实。登录、注册、验证码、密码重置、上传、题库导入和答题提交超限统一 429/`Retry-After`；Redis 缺失或执行失败明确返回 503，不静默放行。
- 真实基础设施：通过本地安全凭据包装脚本在真实 MySQL 8 `edu_platform_iam_dev` 完成 Flyway V1→V2、身份/协议/会话/审计和真实 Redis DB 1 的挑战一次消费、锁定与会话版本验收；随机 Fixture 和精确 Redis Key 均在 `finally` 清理并断言为零，只保留 V2 Schema 与 Flyway 历史。真实随机端口 Gateway 经 Redis Lua 验证前 10 次登录转发成功、第 11 次返回 429 和 `Retry-After`，精确限流 Key 已删除。
- 数据：V2 新增 `user_identity`、`registration_request`、`protocol_agreement`、`teacher_application`、`teacher_application_command`、`account_security_audit`、`admin_account_command`，并扩展用户与 Refresh Session。local/test Seed 仅为三个既有合成账号增加 `example.invalid` 身份及学生 `local-v1` 协议同意；专用清理脚本支持按固定 ID 清理和幂等重建。未创建 Media 资产、OSS 对象、RabbitMQ 消息或其他领域数据。
- 安全边界：生产环境没有真实短信/邮件供应商时挑战接口明确 503；验证目标/验证码和登录维度使用 HMAC 摘要，不记录明文验证码。Redis 不作为唯一事实源、不替代数据库事务/唯一约束；没有第二套登录入口、T12E 页面、T16 审核决定或跨服务数据库访问。
- 自动化：Java `clean verify` 的 8 个 Reactor 模块全部通过，共 134 项测试、122 项通过、12 项真实外部环境用例按开关跳过；前端 OpenAPI 生成、全 workspace 类型检查、29 个 Vitest 文件 191 项测试及三端生产构建通过；Python 字节码、8 项单元测试和 wheel 构建通过；两个 PowerShell 脚本通过 AST 语法解析。Vitest/Vite 因 esbuild `spawn EPERM`、pip 因构建隔离下载限制分别经批准在沙箱外复跑后成功。
- 本机初始化限制：自动为存量用户目录补建新增 DPAPI IAM 安全文件的操作被当前沙箱策略拒绝，未绕过；现有开发机需由当前 Windows 用户重跑 `scripts/Initialize-LocalEnvironment.ps1`。仓库实现、真实 MySQL/Redis/Gateway 验收及自动化不依赖提交任何凭据文件。
- 发布：关键实现提交 `e888c8f7308b458bb7632eab353557a84f413849` 已按规则使用 `git push origin HEAD` 推送；Ready [PR #39](https://github.com/chengchen-ccnu/ccnu-edulion/pull/39) 已创建，目标为 `main`，核对为 OPEN 且 `isDraft=false`。创建时 CI 正在执行，本任务不自行合并。

## 2026-08-30 T12D 合并与 T12E 前置审计

- 合并与清理：[PR #39](https://github.com/chengchen-ccnu/ccnu-edulion/pull/39) 在 Ready、可合并且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后合并为 `main@0dd0188`；本地 `main` 已同步，`codex/t12d-account-lifecycle-security` 本地和远端功能分支均已清理。
- 前置结论：T12D 已冻结 OpenAPI 0.17.0、IAM V2、角色中立注册/验证/密码/会话/教师申请契约及 Redis/Gateway 身份安全边界；`page-specs.md` 已冻结唯一身份区域路由、权限和跨应用边界，生成客户端由合并 CI 验证为最新。因此 T12E 的 Git、代码基线、既有接口、路由和权限工程前置满足，分支可以准备；协议文本与当前版本的权威来源、手机/邮箱绑定范围以及 `/help` 是否提交真实反馈仍缺契约或产品决定，确认前不得开始业务实现。
- 实施边界：T12E 只在 `student-web` 交付 `/register`、`/forgot-password`、`/reset-password`、`/profile`、`/profile/security`、`/apply-teacher`、`/terms`、`/privacy`、`/help`，只消费生成客户端；不得复制教师/管理端登录注册表单，不实现 T16 审核决定、T17A 完整个人资料或真实短信/邮件供应商。
- 本地验收准备：旧本地环境在启动真实 IAM/浏览器验收前必须由当前 Windows 用户重新执行 `scripts/Initialize-LocalEnvironment.ps1`，补齐 DPAPI 加密的 IAM 安全文件；不得把验证码、JWT 或其他秘密写入仓库或 `local.env`。
- 数据：本次总控只执行 Git 合并、文档状态更新和只读前置审计，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 或外部模型，没有创建、修改或清理 Schema、表、消息、对象、Seed、伪数据及迁移检查点。

## 2026-08-30 T12D.1 身份安全补口产品审计

- 审计方式：用户通过 `grill-me` 分轮确认协议、身份、验证码、SMTP、登录、会话和账号注销边界；总控同时只读核对 OpenAPI 0.17.0、IAM/Gateway、共享认证包、ADR-0003、T12D 交付和页面规格。事实缺口是当前注册仍强制 `account/displayName`、登录仅查 `account`、没有 CAPTCHA/SMTP/协议查询/身份绑定/验证码登录，T12E 不能在前端自行补假状态。
- 唯一身份：正式与测试环境都只允许已验证邮箱或手机号登录，昵称 1～30 个可见字符、允许中文和重名且不参与鉴权；内部 `account` 由服务端生成。local/test Seed 固定使用 `student1@example.invalid`、`teacher1@example.invalid`、`teacher2@example.invalid`、`admin1@example.invalid` 并禁止 SMTP 发送；真实邮件验收使用操作者控制的临时邮箱并精确清理。
- 规范化与绑定：邮箱去首尾空格并按不区分大小写唯一；首期中国大陆 11 位手机号规范为 `+86` E.164。每账号最多一个邮箱和一个手机号；绑定验证当前密码和新身份，换绑先验证后原子替换，解绑不得删除最后身份。新增第二身份撤销其他设备，换绑/解绑撤销全部设备；revision/`409`、安全审计和 sessionVersion 不得省略。
- 登录与验证：默认邮箱/手机号密码登录，可切换验证码登录；未知身份不自动注册且保持不可枚举。注册/找回发送前必须通过 Redis 算式验证码；密码第 4～5 次尝试需要算式验证码，第 5 次仍失败后锁定 15 分钟。邮箱先接供应商无关标准 SMTP，手机只保留 local/test 固定码，生产通过能力查询隐藏未配置渠道。
- 页面交互：`/login` 与 `/register` 使用同一背景和身份壳但保留真实路由；注册默认邮箱、可切手机，包含昵称、算式/一次性验证码、密码/确认和协议同意。注册成功后自动尝试既有登录，失败只回登录而不重复注册。协议在注册卡片弹窗展示，`/terms`、`/privacy` 展示同一服务端正文；`/help` 只做静态说明，不展示反馈按钮。
- 协议与会话：协议版本不可修改，只能发布包含正文、生效时间、当前状态和重新同意标记的新版本；注册只接受当前版本，测试正文禁止正式发布，T17A 再实现正式重新同意。保持 15 分钟内存 Access JWT＋7 天 HttpOnly Refresh Cookie且不增加“记住我”；MySQL 保存 Refresh Session/设备/撤销/审计事实，Redis 保存验证码、冷却、失败计数、锁定、限流和 sessionVersion，不保存完整 JWT 或完整 Session。
- 安全收口：验证码、完整身份和 Token 不进入 URL/历史/日志；退出网络失败不能伪装成服务端已撤销。账号注销不塞入 T12D.1/T12E，新增 T12H 独立设计冷静期、恢复、跨域数据保留和审计，冻结前安全页不展示注销按钮。
- 编排：尚未开发且未推送的 `codex/t12e-account-security-web` 仅含上一轮总控状态提交，已直接重命名为 `codex/t12d1-identity-security-contract` 以保留历史并撤销错误实施顺序。T12D.1 只做契约与后端补口；合并后重新准备 T12E，只消费生成客户端。
- 数据与验证：本轮只修改 Markdown 总控文档并执行 Git 分支整理，不修改 OpenAPI、Java、Python、Vue、Flyway、生成代码或配置；没有连接 MySQL、Redis、RabbitMQ、OSS、FFmpeg、SMTP 或外部模型，没有创建、修改或清理 Schema、表、Seed、伪数据、消息、对象、验证码或会话。

## 2026-08-30 T12D.1 唯一身份、协议与验证安全补口验证记录

- 门禁与契约：实施开始时分支为 `codex/t12d1-identity-security-contract` 且工作区干净；完整阅读 `AGENTS.md`、页面规格、backlog、verification、ADR-0003、T12D 交付和工业架构设计。先将 OpenAPI 向前演进到 0.18.0 并评审，再实现 IAM、Gateway、Redis、SMTP 和生成客户端；没有新增 T12E 页面或第二套 DTO。
- 唯一身份与协议：正式/test 登录只查询已验证 EMAIL/PHONE，邮箱 trim+小写、手机号仅接受中国大陆 11 位并保存 `+86` E.164；昵称按 Unicode code point 校验 1～30 个可见字符并允许中文、补充平面字符和重名，内部 `account` 由服务端 UUID 生成。注册必须同意当前生效的 TERMS/PRIVACY；local/test 正文明确不可正式发布。
- 登录与 CAPTCHA：普通密码和一次性验证码登录均只接受邮箱/手机号；验证码登录只登录已有 ACTIVE 账号且未知身份不发送、不注册、响应同形。注册、找回和验证码登录发送前消费 Redis 算式 CAPTCHA；密码第 4～5 次先消费 CAPTCHA，第 5 次密码仍错后锁 15 分钟。CAPTCHA 错误不会执行密码校验或增加密码失败次数；错误/刷新次数有界，正确答案一次消费，回答只以 HMAC 摘要进入 Redis。
- 发送与渠道：业务只依赖 `VerificationDeliveryPort`；local/test 固定码无网络副作用，标准 SMTP 适配器拒绝 `.invalid`、正常委托 JavaMailSender，发送失败补偿删除挑战与冷却。生产没有短信适配器时渠道能力关闭手机注册/验证码登录。检查本机只确认 DPAPI SMTP 凭据文件不存在、未读取任何凭据内容；因此真实 SMTP 端到端发信明确未验收且未伪造通过。
- 身份状态机与会话：身份查询只返回掩码值和 revision；绑定校验当前密码与新身份验证码并撤销其他设备，换绑先验证后原子替换，解绑禁止删除最后身份，两者撤销全部设备。三种变更均递增 revision/sessionVersion、写安全审计并支持 `clientRequestId` 幂等及 409。继续使用 15 分钟内存 Access JWT、7 天 HttpOnly Refresh Cookie、MySQL Refresh Session 和 Redis sessionVersion；Cookie Path 为 `/api/v1`，Gateway 在非 IAM 路由精确剥离 `EDU_REFRESH`。
- 迁移与 Seed：没有改写 V1/V2；IAM V3 只向前增加每用户每身份类型唯一约束、注册指纹、`protocol_document` 和 `identity_change_command`。local/test Seed 幂等生成 `student1@example.invalid`、`teacher1@example.invalid`、`teacher2@example.invalid`、`admin1@example.invalid`，均已验证且不发送 SMTP；清理脚本只按四个固定 user ID 和外键逆序删除，并提供显式重建方式。
- 自动化：最新 Java `clean verify` 的 8 个 Reactor 模块全部通过，共 150 项测试、138 项执行通过、12 项外部验收按开关跳过；IAM 为 28 项、27 项执行通过、1 项真实环境用例默认跳过。OpenAPI 客户端重复生成无漂移；全 workspace 类型检查通过；29 个 Vitest 文件 191 项测试通过；admin/student/teacher 三端生产构建通过。PowerShell SMTP 初始化/安全注入脚本 AST 语法解析和 `git diff --check` 通过。
- 真实基础设施：全部命令只经 `scripts/Invoke-WithLocalEnvironment.ps1` 注入秘密。真实 MySQL 8 `edu_platform_iam_dev` 已从 Flyway V2 正常迁移到 V3；IAM+Redis 验收 1/1 通过，覆盖挑战/CAPTCHA 一次消费、错误上限、有限刷新、密码阈值/锁定、sessionVersion 与 Refresh 撤销。真实随机端口 Gateway+Redis 验收 1/1 通过，覆盖第 11 次限流 429/`Retry-After`。随机账号、身份、协议、审计、会话和全部本轮 Redis Key 均在 `finally` 中按精确 ID/Key 清理；只保留 V3 Schema/Flyway 迁移事实。
- 边界：未实现 T12E Vue 页面、真实短信、账号注销、人工申诉、反馈提交、“记住我”、完整 JWT 黑名单、完整 Redis Session 或跨服务数据库访问。没有创建 Media/课程/学习/运营伪数据、OSS 对象、RabbitMQ 消息或外部模型数据。
- 发布：实现检查点为 `682fd9e`、`26ed7d0`，最终验证记录为 `23d740d`；已按规定执行 `git push origin HEAD`，并创建指向 `main` 的 Ready PR [#40](https://github.com/chengchen-ccnu/ccnu-edulion/pull/40)。已核验 PR 为 `OPEN`、`isDraft=false`；本任务不自行合并。

## 2026-08-30 T12D.1 真实 SMTP 续验与 Gateway 白名单修复

- 门禁与启动：续验前再次确认分支为 `codex/t12d1-identity-security-contract`、工作区干净、PR #40 为 Open/Ready。IAM 与 Gateway 均通过 `scripts/Invoke-WithLocalEnvironment.ps1` 启动；IAM 使用专用 `edu_platform_iam_dev`，Flyway 版本为 V3，Gateway 监听 8080，IAM 监听 8081。SMTP 模式、DPAPI 凭据文件和非敏感连接配置存在；未读取、打印或复制 SMTP 用户名、密码、授权码、验证码或完整收件地址。
- 发现与修复：首次匿名 CAPTCHA 请求被 Gateway 误判为 401。原因是 Gateway 白名单及会话版本豁免遗漏 T12D.1 的 CAPTCHA、验证码登录挑战/提交、CAPTCHA 刷新、渠道能力和当前协议端点。已在现有分支最小修复 `GatewaySecurityConfig` 与 `SessionVersionGlobalFilter`，并扩展 Gateway 路由测试；未新增分支/PR，未触及 T12E 或短信供应商。
- 自动化结果：通过 `scripts/Invoke-WithLocalEnvironment.ps1` 执行 Gateway 受影响测试，`GatewayRouteSecurityTest` 12/12、`DistributedSecurityFilterTest` 4/4，共 16/16 通过。
- 真实 SMTP 结果：算式 CAPTCHA 图片为 `8 - 7 = ?`，答案只用于本次请求并未写入日志；挑战请求已到达 IAM，但 SMTP 主机 DNS 解析和 587 端口连通，投递仍返回 `IAM.VERIFICATION_DELIVERY_UNAVAILABLE`（HTTP 503）。因此没有确认真实邮件的发件人、注册用途、有效期或正文，也没有向 `.invalid` Seed 地址发信；没有继续注册或消费邮箱验证码，不能宣称真实 SMTP 成功。
- 清理：发送失败触发应用补偿删除挑战/冷却；本轮没有创建账号、身份、协议、Refresh Session 或安全审计。停止服务后按本轮已知 CAPTCHA ID 和 Gateway 限流哈希精确清理，Redis 已知临时 Key 余量为 0；没有执行扫描删除、清表、Seed 清理或修改既有 Seed。未创建任何数据库、表、伪数据、媒体对象、RabbitMQ 消息或 OSS 对象。
- 后续阻塞：若要补齐真实 SMTP 成功与验证码错误/过期/重放验收，需要当前 Windows 用户重新运行 `scripts/Initialize-LocalSmtp.ps1` 检查/更新本机 DPAPI SMTP 配置后再重试；凭据不得发到对话。当前真实 SMTP 投递失败结果保留为未通过，不阻塞已完成的自动化 Gateway 修复验证。

## 2026-08-30 T12D.1 真实 SMTP 成功复验与邮件文案收口

- 环境与门禁：保持 `codex/t12d1-identity-security-contract` 和现有 Ready/Open PR #40，不创建分支或 PR。SMTP 初始化脚本修复 Windows PowerShell 5.1 未初始化变量后，由当前 Windows 用户在本机交互保存 DPAPI 凭据；IAM/Gateway 只经 `scripts/Invoke-WithLocalEnvironment.ps1` 启动。IAM 连接专用 `edu_platform_iam_dev`，Flyway 保持 V3；未读取、打印或落盘 SMTP 用户名、密码、授权码、验证码或完整收件地址。
- 真实 SMTP：通过 Gateway 创建算式 CAPTCHA 后，两次向操作者控制的真实邮箱创建 EMAIL 挑战，注册和验证码登录均返回 202，操作者均收到六位随机数字验证码。第二封真实邮件由操作者确认发件显示与本机配置一致，正文以自然语言说明正在登录、10 分钟有效、只能使用一次及非本人忽略，不再展示机械的“用途：登录”。没有向任何 `.invalid` Seed 地址发送 SMTP。
- 受控验证：注册挑战前 4 次错误码均返回 `422/IAM.VERIFICATION_FAILED`，随后正确码成功创建 1 个学生账号；使用新请求 ID 重放已消费挑战仍返回相同 422。验证码登录挑战连续 5 次错误后被销毁，之后正确码仍返回 422，证明错误上限不能被绕过。真实 Redis 验收新增 5ms TTL 挑战并确认过期后消费失败；Gateway 还在高频注册重放时返回 429，限流与 IAM 一次消费职责均有效。
- 文案修复：`SmtpVerificationDelivery` 现在按注册、重置密码、验证码登录、身份绑定/换绑生成自然主题和正文，并从 `platform.iam.verification.ttl` 写入有效期；SMTP 专项测试 4/4 通过，IAM 全量 28 项中 27 项通过、真实环境用例按默认开关跳过。显式开启的真实 MySQL/Redis 验收 1/1 通过，包含新增过期断言。
- 数据与清理：本轮只在既有 `edu_platform_iam_dev` 创建 1 个临时学生账号、1 个已验证邮箱身份、2 条协议同意、1 条注册幂等事实、1 条安全审计和 2 条临时当前协议；未创建 Refresh Session。按精确 user ID/协议 ID 事务删除后，账号、身份、协议、安全审计和 Refresh Session 复查均为 0。7 个 IAM 精确 Redis Key 与 12 个本机 Gateway 候选限流 Key均已自然过期且复查剩余 0；未清表、未扫描删除、未修改或删除既有 Seed，未创建其他 Schema、表、媒体、MQ、OSS 或短信数据。

## 2026-08-30 T12D.1 合并与 T12E 前置审计

- 合并与清理：[PR #40](https://github.com/chengchen-ccnu/ccnu-edulion/pull/40) 在 Ready、可合并且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后合并为 `main@15184f2`；本地 `main` 已快进同步，`codex/t12d1-identity-security-contract` 本地和远端功能分支均已清理。
- 前置结论：T12D.1 已合并 OpenAPI 0.18.0、IAM V3、Gateway 匿名路由/Cookie 隔离、Redis CAPTCHA/失败锁定、供应商无关标准 SMTP、渠道能力、当前协议、唯一邮箱/手机号身份和生成客户端；页面规格已冻结唯一身份区域路由、状态和产品边界，真实 QQ SMTP 注册与验证码登录验收已经通过。因此 T12E 的 Git、契约、生成类型、页面职责和真实邮箱环境前置均满足，已从最新 `main` 准备 `codex/t12e-account-security-web`。
- 实施边界：T12E 只消费生成客户端，在 `student-web` 的同一身份壳交付密码/验证码登录、默认邮箱注册、找回/重置、协议弹窗与公共深链、静态帮助、账号安全、设备管理和教师申请。真实短信供应商尚未接入时必须依据渠道能力隐藏手机注册/验证码入口；不得复制教师/管理端表单，不实现 T16 审核决定、T17A 完整资料、反馈、记住我、人工申诉或 T12H 账号注销。
- 数据与验证：本轮只执行 Git 合并、同步、分支清理、只读前置审计和 Markdown 状态更新；没有修改 OpenAPI、Java、Python、Vue、Flyway 或生成代码，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg、SMTP 或外部模型，也没有创建、修改或清理 Schema、表、Seed、伪数据、消息、对象、验证码、会话或迁移检查点。

## 2026-08-31 T12E 统一注册与账号安全前端验证记录

- 基线与边界：开始时位于准备好的 `codex/t12e-account-security-web` 且工作区干净，完整阅读仓库规则、页面规格、backlog、verification、T12D.1 交付和 OpenAPI 0.18.0。实现仅修改 `student-web` 及共享 API/auth/ui 技术包，不修改契约、后端、迁移或事件，不复制教师/管理端表单，也没有手写 REST DTO。
- 页面结果：统一登录支持密码、服务端阈值 CAPTCHA 和一次性验证码；注册默认邮箱并按渠道能力禁用真实短信未接入的手机入口；找回/重置挑战只保存在标签页模块内存；协议正文来自服务端并支持弹窗、`/terms`、`/privacy`；`/help` 无反馈提交；`/profile`、`/profile/security`、设备会话和教师申请均按页面规格落地。
- 自动化：`check:api` 通过且生成客户端无漂移；8 个 workspace 类型检查通过；Vitest 31 个文件、212 项测试全部通过；admin/student/teacher 三端生产构建通过，仅有既有 chunk size 提示。Gateway 当前代码及依赖模块以 `-DskipTests package` 构建成功；`git diff --check` 通过。
- 真实 SMTP 浏览器验收：使用专用 `edu_platform_iam_dev`、Redis、真实 Gateway/IAM 和 QQ SMTP，受控收件邮箱完成算式 CAPTCHA、真实邮件收取、一次性验证码注册和密码登录。邮件发送账号只承担 SMTP 发件，不作为测试注册账号。隔离同名历史 Cookie 后，临时账号的 HttpOnly Refresh Cookie 轮换、`/profile` 深链接刷新恢复、脱敏邮箱/revision、当前与其他设备会话、教师申请未提交状态均通过；协议深链/弹窗键盘操作、找回/重置缺失挑战、验证码登录、静态帮助和 390×844 响应式布局通过。
- 环境发现：本地默认 Gateway 与 IAM Redis logical database 不同，未对齐时 Gateway 按安全设计返回 503；真实会话验收显式共用 IAM DB。验收浏览器已有其他账号同名 Cookie，最终使用临时 `t12e.localhost` 同源代理隔离 Cookie；代理与服务均已停止，未形成仓库变更。未启动 learning-service 导致的学习页 500 与 T12E 无关。
- 数据清理：仅创建一个临时邮箱学习账号及关联身份、注册事实、协议同意、角色、Refresh Session、登录/安全审计和两条临时当前协议；未创建教师申请、OSS 对象或其他领域数据。结束后按临时 user ID 和协议固定 ID 逆外键精确删除，Redis 按本轮 CAPTCHA ID/user ID 删除，MySQL 用户、Refresh Session、身份、临时协议和 Redis 临时键复核为 0；既有 Seed、角色权限、迁移表及预置会话键未触碰。
- 排除项：未实现真实短信供应商、反馈提交、“记住我”、账号注销、人工申诉、教师审核决定、T17A 完整资料或真实资质上传；这些边界继续由 T12H、T16、T17A 和 media-service 后续任务承担。
- 交付：关键实现提交为 `57b3643`，已按规定执行 `git push origin HEAD` 并创建 Ready/Open PR [#41](https://github.com/chengchen-ccnu/ccnu-edulion/pull/41)；未自行合并。

## 2026-08-31 T12E 合并与 T12F 前置审计

- 合并与清理：[PR #41](https://github.com/chengchen-ccnu/ccnu-edulion/pull/41) 在 Ready、可合并且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后合并为 `main@319a404`；本地 `main` 已快进同步，`codex/t12e-account-security-web` 本地和远端功能分支均已清理。
- 前置结论：T07 已提供统一 OSS 上传、私有资产、处理状态和短期授权，T08A/T08A.1/T08B 已提供五类课时媒体用途、课程版本资源绑定、领域可见性和稳定 HLS 播放页，T12A 已接通同一路由的学习访问与进度。OpenAPI 0.18.0、页面规格和 ADR-0004 均允许 T12F 向前演进；FFmpeg/ffprobe 可用、RabbitMQ 服务正在运行，仓库外共享环境和加密凭据文件存在。因此 T12F 的 Git、架构、媒体基础和本地工具前置满足，已从最新 `main` 准备 `codex/t12f-course-content-player`。
- 待实现而非阻塞：当前课程模型只有课时类型和媒体引用，没有图文正文；课程授权只完整开放视频/封面，尚无字幕、海报、课件和附件的领域短期读取投影；现有 HLS 为单清晰度。T12F 必须先评审并向前演进 OpenAPI、Course/Media 契约和新增迁移，再实现服务端权限、派生变体、生成客户端、教师编排和既有学生学习页，不得让前端猜字段或直接凭 `mediaId` 申请通用媒体授权。
- 实施边界：图文正文采用受控可渲染内容并由课程版本保存；字幕、海报、课件和附件继续只保存稳定 `mediaId`，由 course-service 根据课程/课时/资源行及访问事实签发短期授权。多码率沿用 media-service 派生变体与私有 OSS，播放器只消费服务端列出的可用清晰度；不得搬迁播放器、创建第二套上传客户端、自研 DRM、跨服务查表或保存签名 URL。
- 数据与验证：本轮只执行 Git 合并、同步、分支清理、只读环境/前置审计和 Markdown 状态更新；没有修改 OpenAPI、Java、Python、Vue、Flyway、事件或生成代码，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 处理任务、SMTP 或外部模型，也没有创建、修改或清理 Schema、表、Seed、伪数据、消息、对象、变体、验证码、会话或迁移检查点。

## 2026-08-31 T12F 拆分规划与二次前置审计

- 规划结论：经产品与工程边界审计，原 T12F 拆成顺序实施的 T12F.1“课程内容、资源授权与自动海报后端契约”和 T12F.2“课程内容与播放器前端”。T12F.1 先合并 OpenAPI 0.19.0、Course V4、Media V5、Learning V12、真实基础设施验收和跨域 local/test Seed；T12F.2 才能消费生成客户端。此前本文件 T12E 合并审计中把字幕、多码率/清晰度列为 T12F 待实现的初步判断由本节取代，这些能力明确后移。
- 前置复核：T07 已提供唯一 OSS 媒体边界，T08A/A.1/B 已提供课程版本资源、引用 Outbox、单清晰度 HLS、领域服务授权和稳定学习页，T11/T12A 已提供报名访问与学习完成事实；OpenAPI 当前为 0.18.0，开发 Schema 最新迁移分别为 Course V3、Media V4、Learning V11。FFmpeg/ffprobe、RabbitMQ、本地开发 OSS、仓库外共享环境与加密凭据均已具备。因此 T12F.1 的 Git、契约起点、领域边界、工具和真实验收环境前置满足；当前缺少的正文模型、`ARTICLE_IMAGE`、资源动作授权、`poster-v1` 和图文完成命令是任务内容而非阻塞项。
- 内容与安全冻结：公共课程详情只暴露目录摘要，OpenAPI 0.19.0 将 `LessonSummary.resources` 放宽为可选并 deprecated；教师草稿和学生受保护内容使用独立投影。正文为最多 100,000 可见字符的受控 Markdown，正文图片使用带必填替代文字的 `media:UUID`，引用与唯一 READY 绑定完全一致。外部图片、`data:`、原始 HTML/脚本/iframe 和危险链接禁止；发布版本不可变，类型互转在事务内释放不兼容引用。
- 资源与海报冻结：课程版本保存原文件名/MIME/大小快照、可选 `displayName` 和 `INLINE/DOWNLOAD`，浏览器只按课程/课时/resourceId/动作申请授权。新视频以既有 HLS 与 `poster-v1` 共同成功作为 READY 条件；自动海报在前 30% 有限选择非纯黑/纯白关键帧，回退 10%，输出最长边 1280 WebP且不上采样。独立 `VIDEO_POSTER` 覆盖自动海报；历史视频不增加生产重处理 API，仅允许 T12F.2 在明确本地开发数据中按正常领域链路一次性补自定义海报。
- 页面与进度冻结：T12F.2 只修改既有课程编排和学习页，提供 Markdown 工具栏/实时预览、资料预览/下载、海报、倍速 1/1.25/1.5/2/2.5/3 和图文完成反馈。正文成功渲染后使用稳定请求 ID 写入 0→100 单调完成，页面存活期间最多重试三次，失败不阻止阅读且离页后不无限重试；未报名试看不写进度。
- 排除项：T12F.1 不实现 Vue，T12F.2 不猜契约。两者均不实现多码率、清晰度选择、字幕访问/UI、Office 在线转换、历史海报生产重处理、DRM、AI 或 T12G Redis 缓冲；既有 `SUBTITLE` 枚举、上传策略和 READY 校验继续保留。不得搬迁播放器、增加平行学习路由、第二套 OSS 客户端、跨服务数据库关系或永久 URL。
- 数据与验证：本次总控只修改页面规格、backlog、verification、ADR-0004、总体架构和本地环境文档，并将未开发分支从 `codex/t12f-course-content-player` 改名为 `codex/t12f1-course-content-contract`；未修改 OpenAPI、Java、Python、Vue、Flyway、事件或生成代码，未连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 任务或外部服务，没有创建、修改或清理 Schema、表、Seed、伪数据、消息、对象、变体或迁移检查点。只需执行文档差异检查和范围审计，不能把本次记录解释为 T12F.1/T12F.2 已实现。

## 2026-08-31 T12F.1 课程内容、资源授权与自动海报后端契约

- 契约优先：`docs/api/openapi.yaml` 从 0.18.0 向前演进到 0.19.0，新增受保护课时内容、课程域资源动作授权、ARTICLE 完成命令及安全绑定快照；公共 LessonSummary 资源改为 deprecated/optional 并实际不输出正文/资源，前端类型由 `openapi-typescript` 重生，没有手写 DTO 或新 Vue 页面。
- Course V4：ARTICLE 保存受控 Markdown 与严格 `media:UUID` 引用集合，危险 HTML/协议、外部图片、空 alt、错占位符、重复/错用途/非 READY 绑定立即拒绝；发布校验 ARTICLE 非空和 V5 视频 HLS+poster，公共防泄漏、登录试看/报名、PDF/图片预览下载与 PPTX/TXT 下载动作由服务端决定。类型互转按最终请求释放不兼容资源并在课程事务写 Outbox，共同 COURSEWARE 不抖动；已发布版本仍复制到新草稿修改。
- Media V5：ARTICLE_IMAGE 限 JPG/JPEG/PNG/WebP、10 MiB、单对象，拒绝 SVG/GIF/Multipart；新视频以 `LESSON_VIDEO_V5` 要求 `hls-fmp4-v2` 和 WebP `poster-v1` 同时成功。海报在前 30% 最多 12 个关键帧内跳过近黑/近白，回退 10%，最长边 1280且不上采样；失败复用既有有限重试并最终 FAILED，派生对象随视频回收，历史空契约不重处理。
- Learning V12：ARTICLE 完成使用独立事实与请求表，稳定 `clientRequestId` 重放不新增事实或 revision，不伪造视频 position/watched 事件；PUBLIC 或 ACTIVE 报名可写，未报名付费试看只读不写，普通客户端没有回退命令。
- 自动化：Java `clean verify` 8 个 Reactor 模块全部成功，Gateway 21 项/1 跳过、IAM 28/1、Course 24/2、Learning 62/6、Media 29/6，无失败。受影响定向测试中 Course Markdown/绑定 17/17、Media HTTP/策略/命令 20/20、Learning+Gateway 27/27 通过。前端 `generate:api`、`typecheck`、31 文件 212 项 Vitest 和三端 build 通过，仅有既有 chunk 警告；实现提交后 `check:api` 重跑通过，生成客户端与 OpenAPI 0.19.0 一致。
- 真实 FFmpeg：显式运行 `T12F1FfmpegPosterRealAcceptanceTest` 1/1 通过。用例在 JUnit 临时目录生成一秒黑场加四秒测试画面，真实 ffprobe/FFmpeg 先拒绝黑场再选择彩色关键帧，输出 640×360 WebP。首轮真实运行发现 filtergraph 逗号转义错误，修正后通过；临时视频、候选 PNG、WebP 与日志由 JUnit 精确清理。
- 真实 MySQL/RabbitMQ：三个现有专用开发 Schema 已正常向前保留 Course V4、Media V5、Learning V12 和 Flyway 历史，没有创建数据库或跨域关系。`CourseEnrollmentRealEnvironmentAcceptanceTest` 在真实 `edu_platform_course_dev` + RabbitMQ 1/1 通过，验证真实队列、eventId 幂等、changedAt 乱序和 ACTIVE/CANCELED 投影；随机 enrollment/learner/course/event ID 在 finally 中精确删除。第一次因本地 Rabbit 密码环境值为空被 broker 拒绝，未创建 Fixture；使用正确的本机运行配置后通过，未修改 broker 用户或服务。
- OSS 与 Seed：编排已实现生产禁用的固定 ID Initialize/Cleanup/Rebuild，按 Media→Course→Learning 初始化、逆序清理；媒体脚本合成视频、自动/自定义海报、正文图、有效最小 PDF/PPTX 与 TXT，不提交二进制。经凭据包装器和只读 Bucket 元数据确认项目专用开发 OSS 后，真实 Rebuild 完整通过；再次 Initialize 对 6 个固定资产、HLS/`poster-v1` 派生对象、2 个课时和 0% 图文进度做数据库与精确 OSS HEAD 复核，确认幂等。`MediaRealEnvironmentAcceptanceTest` 2/2 通过真实 MySQL/OSS/RabbitMQ/FFmpeg 的单对象/Multipart、授权下载、confirm/return、重试/DLQ/重投及精确清理。随机验收对象、消息、记录和本机文件已清理，仅明确保留固定 T12F.1 Seed 与三项迁移。
- 可读性与边界：权限判断、Markdown 验证、处理状态机、幂等、Outbox、Seed/清理和复杂测试均增加中文 Javadoc/注释；OpenAPI/SQL 解释兼容与空值语义，生成代码未手改。本任务没有 Vue、多码率、清晰度、字幕 UI、历史海报 API、Office 转换、DRM、AI、Redis 播放缓存、第二套 OSS 客户端或跨 Schema Join/外键/事务。
- 发布：关键实现提交 `f5e03b5` 已按规则推送到 `codex/t12f1-course-content-contract`；Ready [PR #42](https://github.com/chengchen-ccnu/ccnu-edulion/pull/42) 已创建，目标 `main`，本任务不自行合并。完整实现、命令、演示和清理说明见 `docs/delivery/T12F.1-course-content-contract.md`。

## 2026-08-31 T12F.1 合并与 T12F.2 前置审计

- 合并与清理：[PR #42](https://github.com/chengchen-ccnu/ccnu-edulion/pull/42) 在 Ready、`MERGEABLE/CLEAN` 且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后合并为 `main@e53a58d`；本地 `main` 已快进同步并与 `origin/main` 一致，`codex/t12f1-course-content-contract` 本地和远端分支均已精确删除。
- 前置结论：OpenAPI 0.19.0、Course V4、Media V5、Learning V12、生成 TypeScript 客户端、受保护课时内容/资源动作授权、`ARTICLE_IMAGE`、自动/自定义海报投影、图文完成命令和生产禁用的固定演示 Seed 均已进入 `main`。既有教师课程编排页、学生 `/learn/:courseId/:lessonId` 和共享 `HlsVideoPlayer` 是唯一实现落点；页面规格已冻结 Markdown、资料、海报、倍速、完成反馈和排除项。因此 T12F.2 的 Git、契约、生成类型、页面职责、真实数据和浏览器验收前置满足，已从最新 `main` 准备 `codex/t12f2-course-content-player`。
- 一致性验证：重新执行 `pnpm.cmd --dir web-apps run check:api` 成功，OpenAPI 0.19.0 可重新生成且已跟踪 `schema.d.ts` 与 HEAD 无差异；`git diff --check` 通过。T12F.2 只能消费生成客户端，不得修改服务端契约来迁就页面、维护手写 DTO、搬迁播放器或创建第二套上传/学习路由。
- 实施边界：教师在既有课程编排页完成 Markdown 工具栏/实时预览、ARTICLE_IMAGE 上传插入、资源展示名/主动作和类型切换二次确认；学生在既有学习页消费受保护内容，显示有效/占位海报、1～3 倍速、资料预览/下载，并在正文成功渲染后以同一请求 ID 最多重试三次同步 100% 完成。多码率、清晰度选择、字幕 UI、历史海报生产重处理 API、Office 转换、DRM、AI 和 T12G Redis 缓冲继续排除。
- 数据与环境：本次总控没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 任务或外部服务，没有创建、修改或清理 Schema、表、消息、对象、迁移或伪数据；T12F.1 明确保留的固定演示 Seed 原样保留，供 T12F.2 页面和真实浏览器验收使用。没有执行 Seed Cleanup/Rebuild，也没有删除已有安全措施。

## 2026-09-01 T12F.2 课程内容与播放器前端

- Git 与契约：开始时分支为 `codex/t12f2-course-content-player`、工作区干净，HEAD `4c6aa3ff` 的 merge-base/main 为 PR #42 合并提交 `e53a58d`。完整复核页面规格、backlog、verification、T12F.1 交付、ADR-0004 与 OpenAPI 0.19.0 后确认契约足够；本任务没有修改 OpenAPI、Java、事件或 Flyway，只从生成 `components["schemas"]` 导出类型别名。
- 教师端：既有课程编排页实现 ARTICLE Markdown 工具栏、共享实时预览、`ARTICLE_IMAGE` 上传/引用收口、资料 `displayName`/`presentation`、显式 revision 保存、409/422/429/离线提示、未保存离页保护及三类课时互转二次确认。SUBTITLE 后端枚举和既有绑定边界保留，页面没有字幕入口。
- 学生端：既有学习页只从受保护内容接口读取正文、播放 mediaId、海报与资源；共享消毒器完成 Markdown/KaTeX/GFM/正文图渲染后才用页面级稳定 UUID 调用 ARTICLE 完成，最多退避三次且失败不阻断阅读。资料每次动作重新申请授权并只生成内存 Blob；付费未报名试看不写完成。
- 播放器：自定义海报、自动 `poster-v1` 与固定 SVG 占位按优先级展示；倍速固定 1/1.25/1.5/2/2.5/3，保持音调且只存播放器实例，切课恢复 1x；组件/页面离开时销毁 HLS、请求、计时器和临时 URL，不使用 Web Storage。
- 自动门禁：`check:api` 通过；全 workspace typecheck 通过；Vitest 35 文件/226 测试通过；admin/student/teacher 生产构建通过，仅有既有学生 chunk 大小警告；`git diff --check` 通过。新增测试覆盖 Markdown 消毒、图片解码失败不完成、生成客户端路径、类型切换边界、资源动作清理、海报/倍速复位和 ARTICLE 有限重试。
- 真实浏览器：固定学生深链接成功渲染标题、2 个 KaTeX、GFM 表格、代码和 READY Blob 正文图；PDF 显示预览/下载，TXT/PPTX 只下载，三个下载动作无错误；ARTICLE 从 Seed 0% 变 100%。视频使用 Blob 自定义海报，六档倍速和 3x 生效，切课恢复 1x，无字幕 UI。390×844、834×1112、1440×900 均无横向溢出，焦点轮廓可见。
- 数据恢复：真实完成后执行固定 Seed Rebuild；清理阶段完成但长任务未完成初始化，随后幂等 Initialize 完整通过。最终专用 Learning dev Schema 只读核验为固定进度 `completed=0`、位置/观看 0，完成事实与幂等请求均 0；固定 6 个媒体资产、课程和两个课时继续保留。未枚举 Bucket、直写媒体表、补历史海报或新建随机课程。
- 补验与认证修复：本地启动配置最初使 Gateway/IAM 分别读取 Redis DB 0/1，验收进程对齐后继续发现刷新请求携带 Cookie、最新数据库会话却保持 ACTIVE ROOT。Git 历史确认 T12D.1 把 Cookie Path 从 `/api/v1/auth` 扩为 `/api/v1` 时未清理旧 Path，浏览器会同时发送两个同名值。登录/刷新成功/退出响应同时清理旧 Path；`AuthControllerTest` 2/2 通过，真实学生深链接硬刷新、教师 5173→5174 跳转和教师页硬刷新均通过，未放宽 HttpOnly/SameSite、未持久化 Access Token。
- 补验契约差异：教师真实课程编排页加载、跨端和刷新成功，Markdown、KaTeX、GFM 表格、代码及资料区可见；保存后的 ARTICLE_IMAGE owner 预览返回 404。T12F.1 交付与 OpenAPI 0.19.0 只为学生受保护正文投影提供 ARTICLE_IMAGE 内联授权，普通 owner 资料动作不含该用途。按契约规则已停止前端猜测调用并显示明确错误；新上传未保存图片保留本地 Blob 预览。后续必须先补最小教师正文图片投影，本 PR 不修改 Course/Media 业务契约。
- 发布：关键实现提交 `a37fc37` 已按规则推送到 `codex/t12f2-course-content-player`；[Ready PR #43](https://github.com/chengchen-ccnu/ccnu-edulion/pull/43) 已创建，目标 `main`，本任务不自行合并。

## 2026-09-01 T12F.2 契约阻断与 T12F.1.1 前置审计

- 现状复核：[PR #42](https://github.com/chengchen-ccnu/ccnu-edulion/pull/42) 已合并为 `main@e53a58d`，OpenAPI 0.19.0、Course V4、Media V5、Learning V12 和生成客户端已在 `main`。T12F.2 [PR #43](https://github.com/chengchen-ccnu/ccnu-edulion/pull/43) 保持 Ready/Open、`MERGEABLE/CLEAN`，两组 Java、Frontend、Python、OpenAPI 共 8 项 CI 全部成功；本次总控没有合并、关闭、改写或追加该 PR。
- 阻断证据：T12F.2 的学生受保护正文、正文图片、资料动作、海报、倍速和图文完成真实流程已通过，但教师重新进入已保存 ARTICLE 课时时，普通 owner 资源动作对 `ARTICLE_IMAGE` 返回 404。OpenAPI 0.19.0 有意只在学生受保护正文投影中返回正文图片内联授权，普通资料动作又明确排除该用途；因此前端停止猜测并只保留新上传未保存图片的本地 Blob 预览。CI 全绿不能证明“保存—刷新—重新进入”产品闭环完成，PR #43 暂停合并。
- 最小补口：新增 T12F.1.1，OpenAPI 只向前到 0.19.1，为教师 owner 当前可编辑课程版本提供专用正文内容投影。course-service 必须从该版本 Markdown 引用和同课时绑定行解析 `ARTICLE_IMAGE mediaId`，不接受浏览器任意 mediaId；仅用途精确、READY、正文实际引用的资产可经 media-service 获得短期 `ORIGINAL/INLINE`。其他教师、错课程/课时、历史或非可编辑版本保持不可枚举拒绝，响应不得泄漏 Bucket、Object Key、永久 URL 或服务令牌。
- 范围门禁：T12F.1.1 只允许修改 OpenAPI、Course/Media 必要授权实现、生成客户端、权限/安全测试和交付文档；预计无需数据库迁移。不得实现 Vue、把 `ARTICLE_IMAGE` 加入普通资料列表或 PREVIEW/DOWNLOAD、增加任意 mediaId 授权入口、重写学生投影、修改 T12F.2 分支或扩大字幕/多码率/历史处理等范围。若实现发现必须新增迁移或破坏 0.19.0 语义，应停止并回报总控，不得自行扩展。
- 后续顺序：T12F.1.1 独立验收、合并并清理分支后，才可切回 `codex/t12f2-course-content-player`，将最新 `main` 以普通 merge 合入，解决文档冲突，消费 0.19.1 生成客户端并验证教师保存、硬刷新、重新进入后正文图预览；PR #43 重新全绿且真实闭环通过后才能合并。
- Git 与数据：开始时 T12F.2 分支和工作区干净，随后从与 `origin/main` 一致的 `e53a58d` 创建 `codex/t12f1.1-teacher-article-image-access`。本次只修改 page-specs、backlog 和 verification，没有修改 OpenAPI、Java、Vue、Flyway、事件、生成代码或 T12F.2 分支；没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 或外部服务，没有创建、修改或清理 Schema、表、Seed、消息、对象或伪数据。

## 2026-09-01 T12F.1.1 教师已保存正文图片预览授权补口

- 前置与审计：开始时 `codex/t12f1.1-teacher-article-image-access` 工作区干净，HEAD 包含规划提交 `b065f42`，最新 `origin/main@e53a58d` 是 HEAD 祖先。完整阅读仓库规则、页面规格、backlog、verification、OpenAPI 0.19.0、ADR 0004 和 T12F.1 交付；只读检查 [PR #43](https://github.com/chengchen-ccnu/ccnu-edulion/pull/43) 为 Ready/Open 且 8 项检查成功，没有切换、修改、推送、关闭或合并其分支。审计确认缺口只在教师 owner 当前可编辑 ARTICLE 投影，Media 已有 ARTICLE_IMAGE/READY/ORIGINAL/INLINE 与一次性服务令牌约束，无需迁移或新媒体动作。
- 契约与实现：OpenAPI 向前到 0.19.1，新增无 mediaId/resourceId/version 参数的教师 `article-content` GET，以及最小 `TeacherArticleContent`/`TeacherArticleInlineImage`。Course SQL 同时限定 owner 和 `COALESCE(current_draft_version_id,published_version_id)`；服务重新解析保存 Markdown，逐一要求同课时唯一 ARTICLE_IMAGE 绑定，再以现有跨服务 API 申请 `COURSE_ARTICLE_IMAGE/ORIGINAL/INLINE`。未引用行不返回；缺失、重复、错用途在授权前安全冲突；Media 再校验实际用途和 READY。响应不含普通资源、Bucket、Object Key、永久 URL、凭据或服务 Token，学生受保护投影和普通资料动作未改写。
- 自动化：完整 Java `clean verify` 的 8 个 Reactor 模块成功，Gateway 21/1、IAM 28/1、Course 30/3、Learning 62/6、Media 31/7，无失败；新增 Course、HTTP、Gateway、Media 测试覆盖 owner 新授权、非 owner/错版本不可枚举、任意 mediaId 无效、绑定一致性、实际用途、非 READY、精确媒体动作、一次性 jti、防泄漏及原普通动作回归。`generate:api`、8 workspace `typecheck` 和实现提交后的 `check:api` 均通过，客户端只由 OpenAPI 生成，没有 Vue 改动。
- 真实 owner/OSS：通过凭据包装器启动真实 Media→Course→Gateway，Course 门控 1/1 通过。随机课程保存含固定 ARTICLE_IMAGE 的正文，重新读取取得 INLINE 授权并从项目专用 OSS 加载非空 PNG；5 秒签名过期后原 URL 返回 403，重新请求再次通过 owner/current 校验并成功加载。非 owner、错课程、错课时、历史版本均不可枚举；未引用绑定忽略，普通附件实际用途返回不可见，缺绑定返回安全 409，任意查询 mediaId 不影响投影。
- 真实非 READY 与测试脚手架：Media 门控最终 1/1 通过，只把固定 `...f103` 从 READY 暂时改为 UPLOADED，真实 Gateway/Course/Media 链路返回 `MEDIA.NOT_READY`，finally 恢复 READY。首次两次运行分别在测试方法前暴露跨模块 test classpath 的 Flyway 重名和 Gateway 自动配置污染，未触碰媒体状态；按既有 Media 真实测试限定自身迁移并排除 Gateway 自动配置后通过。
- 数据与清理：没有创建数据库、表、迁移或 OSS 对象。随机课程/版本/章节/课时/绑定/Outbox 已按 courseId 逆序精确删除；固定 T12F.1 Course/Media Seed 和 OSS 原件未删除。媒体状态恢复 READY，本轮新增 nonce、到期 nonce 与 `t12f11-*` 访问审计已清理，临时服务和日志已停止/清空；没有改动本地凭据或仓库环境配置。
- 可读性与边界：新增业务方法、SQL、权限/绑定校验、跨服务失败和真实门控清理均有中文注释；生成文件未手改。无 Vue、Flyway、事件、跨 Schema Join/外键/事务、直接 OSS SDK、普通 ARTICLE_IMAGE PREVIEW/DOWNLOAD、任意 mediaId 入口或范围外媒体能力。关键实现 `1b586da`、验证记录 `3c7d6c9` 已推送，Ready [PR #44](https://github.com/chengchen-ccnu/ccnu-edulion/pull/44) 目标 `main` 并保持 Open。完整说明见 `docs/delivery/T12F.1.1-teacher-article-image-access.md`；T12F.2 PR #43 继续等待本任务合并。

## 2026-09-01 T12F.1.1 合并与 T12F.2 恢复审计

- 合并与清理：[PR #44](https://github.com/chengchen-ccnu/ccnu-edulion/pull/44) 在 Ready、`MERGEABLE/CLEAN` 且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后，以普通 merge 合并为 `main@1402e07`；本地 `main` 已快进同步并与 `origin/main` 一致，`codex/t12f1.1-teacher-article-image-access` 本地和远端分支均已删除。
- 分支恢复：开始合并前 `codex/t12f2-course-content-player` 工作区干净，随后以普通 merge 合入最新 `main`。OpenAPI 0.19.1、Course owner `article-content` 投影、权限实现、生成客户端和测试已进入 T12F.2；文档冲突按“T12F.1.1 已合并、T12F.2 等待最终真实复验”的事实合并，没有改写已验收的业务实现或扩大功能范围。
- 最终前置：自动生成契约与后端补口已经满足。PR #43 合并前仍必须让教师页面消费生成的 `article-content` 投影，并通过真实浏览器完成“保存含 ARTICLE_IMAGE 的正文—硬刷新—重新进入—图片成功加载”，同时复核非 owner/过期授权及离页 Blob URL 清理；随后重跑受影响前端门禁和最新 PR CI。未完成这些检查前不得仅凭旧 CI 合并。
- 数据与环境：本次总控只执行 Git、GitHub 和文档合并操作，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg 或外部服务，没有创建、修改或清理数据库、表、Seed、消息、对象或伪数据；T12F.1 固定演示 Seed 保持不变。

## 2026-09-02 T12F.2 教师已保存 ARTICLE_IMAGE 前端闭环终验

- Git 与范围：本次继续在 `codex/t12f2-course-content-player` 完成，工作区开始时干净，HEAD 包含普通 merge 提交 `e97bf67`。实现只修改教师前端、共享安全正文预览、前端测试和 T12F.2 交付/路线文档，没有修改 OpenAPI、Java、Flyway、事件 Schema、学生内容投影、普通资料 PREVIEW/DOWNLOAD、字幕、多码率或历史海报。
- 实现结果：教师 API 客户端消费生成的 OpenAPI 0.19.1 `GET /api/v1/teacher/courses/{courseId}/lessons/{lessonId}/article-content`；课程编排页在重新进入、刷新和切课时读取 `articleMarkdown` 与 `inlineImages`，通过现有 `SafeArticleContent`、短期授权和内存 Blob URL 渲染。图片授权返回 401/403 时重新走 `article-content` 申请，不保存永久 URL、不拼接 OSS 地址、不向接口提交任意 mediaId；未保存新上传图片继续使用本地预览。请求、组件实例和 Blob URL 在切课、重新加载、重试及离开时清理。
- 自动化验证：`pnpm.cmd --dir web-apps run check:api` 通过并确认生成客户端为 0.19.1；workspace typecheck 通过；Vitest 35 个文件、228 个测试全部通过；workspace build 中 admin/student/teacher 均成功，仅保留既有 student chunk 大小警告；`git diff --check` 通过。新增覆盖生成 API 路径/鉴权、保存正文 hydration、错误状态/重试、本地 Blob 预览、授权过期刷新和 Blob 释放。
- 真实本地人工验收：用户已在专用本地环境确认固定 Seed 的教师保存含 `ARTICLE_IMAGE` 正文后硬刷新、重新进入课时，正文图片成功显示；非 owner 无法读取；过期授权通过再次请求 `article-content` 恢复；loading、401、403、404、409、429、503、离线和图片加载失败均有可见处理；离页请求和 Blob URL 已释放；控制台无错误，页面/存储/日志未泄露 Bucket、Object Key、永久 URL 或 Token。该条为用户人工验收结论，不冒充自动化浏览器观测。
- 临时数据清理：人工验收使用固定课程 `00000000-0000-4000-8000-00000000f201`、ARTICLE 课时 `00000000-0000-4000-8000-00000000f232` 和图片 `00000000-0000-4000-8000-00000000f103`，仅涉及专用 `edu_platform_media_dev`、`edu_platform_course_dev`、`edu_platform_learning_dev` 三个开发 Schema 的既有 T12F.1 固定 Seed。验收结束后已按 `scripts/Invoke-T12F1CourseContentDemoSeed.ps1 -Action Cleanup` 成功清理，停止 5173/5174、8080–8084 验收进程；临时验收日志已清空，未改动本地凭据、仓库环境配置或非本任务数据。
- PR 状态：已准备将本次提交推送到既有 [Ready/Open PR #43](https://github.com/chengchen-ccnu/ccnu-edulion/pull/43)，不合并；推送后等待该 PR 最新 8 项 CI 全部成功再交付。

## 2026-09-05 T12F.2 合并与 T12G 前置审计

- 合并与清理：[PR #43](https://github.com/chengchen-ccnu/ccnu-edulion/pull/43) 在真实教师正文图片终验完成、Ready、`MERGEABLE/CLEAN` 且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后，以普通 merge 合并为 `main@96b58dc`；本地 `main` 已快进同步并与 `origin/main` 一致，`codex/t12f2-course-content-player` 本地和远端分支均已精确删除。
- T12G 前置结论：T02 已提供本地 Redis/健康配置，T11 已提供 `progress-events` 契约、MySQL `lesson_progress_event` 幂等事实、`lesson_progress` 单调聚合和课程访问校验，T12A/T12F.2 已提供播放器定时采样、暂停/结束触发、有限前端队列及既有学习页。`learning-service` 已包含 Spring Data Redis 与 Spring AMQP 依赖，并为 local profile 约定独立 Redis DB 4 和 RabbitMQ 连接。因此 Git、领域归属、基础设施、现有 API/数据事实和页面落点均满足，已从最新 `main` 创建 `codex/t12g-progress-buffer`。
- 实施前必须冻结的语义：当前 `ReportProgressRequest` 只有位置、增量观看时长、完成标记和时间，无法可靠区分周期心跳与暂停、离开、切课、结束等强制检查点。T12G 应先以兼容方式定义事件种类/持久性结果，再让普通心跳写 Redis、强制检查点形成 MySQL 可恢复事实；读取需合并两层最新值并保持位置/完成单调。不能根据请求间隔猜类型，不能在 Redis 故障时返回虚假成功，也不能同时引入 Redis Stream 与 RabbitMQ 两套生产落库链路。
- 范围与验证边界：T12G 只修改 Learning 进度契约/实现、必要的播放器上报标记、Redis/可靠落库配置与测试，不重做页面、不缓存作答/成绩/ARTICLE 完成事实、不使用 Redis 分布式锁替代 MySQL 事务和唯一约束。实现验收必须覆盖多实例并发、乱序/重复、Redis 重启或不可用、消费者重试/DLQ 或待恢复记录、强制检查点、读合并、最终落库、积压恢复和精确测试数据/Key/消息清理。
- 本次数据与代码：总控只执行 GitHub 合并、Git 同步/分支清理、只读代码/配置审计和上述文档状态更新；没有修改 OpenAPI、Java、Vue、Flyway、事件 Schema、生成客户端或运行配置，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、FFmpeg，没有创建、修改或清理 Schema、表、Seed、Key、消息、对象或伪数据。

## 2026-09-05 T12G 拆分规划与 T12G.1 分支准备

- 拆分结论：经产品和可靠性规则确认，原 T12G 拆为顺序实施的 T12G.1“进度缓冲、强制检查点与可靠落库后端契约”和 T12G.2“播放进度同步前端”。此前前置审计中仍待选择的 MQ/Stream、消费者 DLQ 和单任务前后端范围由本节取代：T12G.1 只采用 Redis Hash＋dirty ZSet＋Lua 与 Learning 内部 Worker，不使用 RabbitMQ、Redis Stream 或 DLQ；T12G.2 必须等待其合并。
- 契约冻结：T12G.1 目标为 OpenAPI 0.20.0、Learning V13 和重新生成的客户端。新增可选 `eventType=HEARTBEAT|PAUSE|LESSON_SWITCH|LEAVE|ENDED`；缺失字段的旧客户端继续同步写 MySQL，不能按间隔猜类型。HEARTBEAT 成功缓冲返回 `BUFFERED`，四类强制检查点直写 MySQL 返回 `PERSISTED`，学生读降级可标记 `PERSISTED_ONLY`；只有 ENDED 能请求完成。
- 存储与故障冻结：每个学习者/课程/课时只合并最新普通心跳，Worker 约 5 秒批量 UPSERT；MySQL 是聚合、完成和教师/报表的事实源，不保存逐心跳历史。多实例以租约/版本领取，提交成功后只清理未变化 dirty；MySQL 故障时保留并退避，积压超过阈值后普通心跳返回 503。Redis 故障时普通心跳同样 503，强制检查点仍直接尝试 MySQL，不返回虚假成功，不用 Redis 锁替代事务/唯一约束。
- 前端冻结：T12G.2 在既有播放器最多每 5 秒发送 HEARTBEAT，并对暂停、切课、主动退出、结束发送强制检查点。普通失败只在页面内存合并观看秒数并有限重试；强制检查点最多等待约 3 秒，失败后允许重试或明确仍然离开。刷新/关闭/崩溃只做 keepalive/beacon 尽力提交，不承诺必达，不使用 localStorage/IndexedDB 保存未提交进度，也不无限重试。
- 并发、指标和容量：允许多设备，最远位置/完成单调并按服务端时间窗口限制观看秒数叠加。Micrometer/Actuator 覆盖心跳、强制检查点、dirty 年龄/数量、批次/冲突、Redis/MySQL 降级和 Worker 健康且不记录敏感值。容量门槛固定为 1,000 模拟学习者、约 200 心跳请求/秒、至少 10 分钟、普通心跳 p95 小于 200ms、正常最老未落库约 5 秒以内；只压进度接口。
- 演示数据边界：T12G.2 使用本地“线性代数”第 2 课“行列式定义”，但必须经正常 Course API 解析稳定 ID 并核对已发布、视频 READY 和访问事实，不能只按标题或临时替换。一个真实浏览器播放既有视频，负载测试不拉取视频；不上传、复制、转码或删除 OSS 对象。临时进度、Redis Key 和租约按 run ID 精确清理并恢复验收前进度；目标不明确属于本地开发数据时停止。
- 分支与数据：原本地未开发分支 `codex/t12g-progress-buffer` 已重命名为 `codex/t12g1-progress-buffer-contract`，仍基于最新 `main` 且没有远端同名分支。本次只修改规划、页面规格、验收和环境文档，不修改 OpenAPI、Java、Vue、Flyway、生成客户端或运行配置，不连接或修改 MySQL、Redis、RabbitMQ、OSS/FFmpeg，不创建、修改或清理 Schema、表、Seed、进度、Key、消息、对象或伪数据。

## 2026-09-05 T12G.1 进度缓冲、强制检查点与可靠落库后端实现

- 契约与边界：OpenAPI 从 0.19.1 向前演进到 0.20.0，`eventType` 保持可选以兼容旧客户端；缺失字段同步写 MySQL，显式 `HEARTBEAT` 返回 `BUFFERED`，`PAUSE`/`LESSON_SWITCH`/`LEAVE`/`ENDED` 返回 `PERSISTED`，只有 `ENDED` 可设置完成。`LessonProgress.persistenceStatus` 支持 `PERSISTED_ONLY` 读降级。未修改页面路由、播放器、题目/成绩/ARTICLE 事实或教师 MySQL 只读投影。
- 后端实现：Learning V13 新增 `lesson_progress.buffer_version` 和检查点 `lesson_progress_event.event_type`；Redis 使用 Hash＋dirty ZSet＋Lua 原子合并，不使用 Redis Stream/RabbitMQ/DLQ。内部 Worker 以 Hash 租约领取、按版本幂等 UPSERT，MySQL 失败保留 dirty 并延后；成功且版本未变化才清理 Hash。Redis 故障时普通心跳返回 503，强制检查点仍直接尝试 MySQL，不返回虚假成功。
- 自动化结果：`learning-service` 编译通过；全量 Learning 测试 64 项通过、6 项真实环境测试按条件跳过；`LearningAssessmentIntegrationTest` 16/16 通过，覆盖 V13 H2 迁移、旧兼容路径、显式强制检查点、`ENDED` 完成门禁和 Redis 未配置时 HEARTBEAT 503；`pnpm.cmd --dir web-apps run generate:api`、workspace typecheck（8 个 workspace）和 Vitest 35 文件/228 项通过。`check:api` 在未提交工作树上按脚本设计检测到本次预期生成文件 diff，不能报告为 clean。真实 Redis Lua/多实例租约、MySQL 故障恢复、积压保护实机、1,000 学习者/约 200 请求每秒/10 分钟容量门槛和 T12G.2 浏览器流程本轮未执行。
- 配置与数据：新增五项非敏感进度缓冲配置并同步 `.env.example`、local/test 配置和本地环境文档；没有连接或修改 `edu_platform_learning_dev`、Redis DB 4、RabbitMQ、OSS/FFmpeg，没有创建或清理真实 Schema、表、Seed、进度、Key、租约、消息或对象。自动化仅使用隔离 H2 内存库，随测试销毁。
- 可读性与交付：新增 Redis/Lua/Worker/故障边界、V13 SQL、测试和配置均有中文说明；生成 `schema.d.ts` 未手改。独立交付说明见 [`docs/delivery/T12G.1-progress-buffer-contract.md`](../delivery/T12G.1-progress-buffer-contract.md)。当前分支为 `codex/t12g1-progress-buffer-contract`，尚未创建提交或 PR。

## 2026-09-05 T12G.1 收尾真实验收与容量结果

- 真实环境：通过 `scripts/Invoke-T12G1RealAcceptance.ps1`，脚本经 `scripts/Invoke-WithLocalEnvironment.ps1` 注入本地 DPAPI 凭据，并校验 MySQL `edu_platform_learning_dev`、Learning Redis DB 4；V13 已应用且 Flyway 校验通过。1/1 用例通过，覆盖 Redis Hash、dirty ZSet、Lua 原子合并、双实例租约竞争、旧 Worker 版本 ACK 保护、Worker 实际 MySQL 落库、Redis/MySQL 暂时不可用和恢复、有限退避、强制检查点、503 积压保护、`PERSISTED_ONLY` 读降级及精确清理。
- 容量环境：通过 `scripts/Invoke-T12G1CapacityAcceptance.ps1`，持续 600 秒、1,000 个模拟学习者、120,000 个 HEARTBEAT（约 200 req/s），只请求进度接口，不请求 OSS/HLS。成功 120,000、失败 0；p50 1.75ms、p95 4.17ms、p99 83.13ms；最老 dirty 5.682 秒，最终 dirty 0，满足 p95 <200ms 和约 5 秒落库年龄目标。
- 容量缺陷闭环：首轮真实容量暴露 course-service 同步放大和单线程/小批次 Worker 瓶颈；T12G.1 内已加入 1 秒课程事实短缓存、1000 批次及 16 路受控 Worker 并行，修复后重新执行完整 10 分钟验收通过。独立清理测试输出 `learners=1000 mysqlRows=0 redisKeys=0`。
- 数据边界：只确认/使用本任务所属 Learning V13 和本轮随机 UUID 进度记录；没有创建其他领域表、Seed、OSS 对象或 MQ 消息。真实用例结束后 MySQL `lesson_progress_event`/`lesson_progress`、Redis Hash、dirty 成员和租约均按 run ID 精确清理为零。T12G.2 浏览器播放器验收未执行。
- 当前实现仍未引入 RabbitMQ、Redis Stream、DLQ 或 Vue 业务改动；需在实现提交后补跑 `pnpm.cmd --dir web-apps run check:api` 并记录 CI/PR 结果。
- 实现提交：`a48a334`，分支 `codex/t12g1-progress-buffer-contract` 已推送；Ready PR 为 [#45](https://github.com/chengchen-ccnu/ccnu-edulion/pull/45)，`check:api` 在提交后通过。PR 最新 CI 全绿：两组 Frontend / Node 20.19、Java / JDK 17、OpenAPI generated source、Python / 3.9 均通过；未合并。

## 2026-09-05 T12G.1 合并与 T12G.2 前置审计

- 合并与清理：[PR #45](https://github.com/chengchen-ccnu/ccnu-edulion/pull/45) 保持 Ready、`MERGEABLE/CLEAN`，两组 Frontend / Node 20.19、Java / JDK 17、OpenAPI generated source、Python / 3.9 共 8 项检查全部成功后，以普通 merge 合并为 `main@aebc942`。本地 `main` 已快进同步并与 `origin/main` 一致，`codex/t12g1-progress-buffer-contract` 本地和远端分支均已精确删除。
- 契约前置：OpenAPI 0.20.0、生成 `schema.d.ts`、Learning V13、五类事件、`BUFFERED/PERSISTED/PERSISTED_ONLY`、Redis 缓冲、强制检查点和学生读合并均已进入 `main`。现有 `HlsVideoPlayer`、领域中立播放采样器、`progress-sync.ts`、`CourseLearningPage.vue` 及其测试提供唯一前端改造落点；当前仍是 15 秒无事件类型串行上报并在离页直接丢弃队列，恰好属于 T12G.2 要替换的任务内容，不是前置阻塞。
- 数据前置：通过正在运行的正常公共 Course API 只读查询，唯一命中课程 `5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057`“线性代数”，当前发布版本 `v4`、访问类型 PUBLIC；第 2 课 `f13f712f-b7d8-49c1-804f-58bded4e1c5a` 为 VIDEO“第 2 课 行列式的定义”。T12G.2 启动真实学生会话后仍须通过受保护内容接口复核视频 READY、报名/访问和播放授权；若失败应停止验收，不得替换课程或操作 OSS。
- T12G.2 结论：Git、契约、生成客户端、固定页面/播放器落点和真实课程目录前置满足，已从最新 `main` 创建 `codex/t12g2-progress-sync-web`。任务只允许显式五类事件、最多 5 秒心跳、页面内存合并/有限重试、强制检查点约 3 秒等待及离页确认、keepalive/beacon 尽力提交和三态反馈；不得修改 OpenAPI/Java/Flyway、复制学习页/播放器、持久化未保存进度或把标签关闭描述为可靠送达。
- 数据与代码：本次总控只执行 GitHub 合并、Git 同步、分支清理、正常 Course API 只读查询和状态/前置文档更新；没有修改 Vue、TypeScript、OpenAPI、Java、Flyway、生成客户端或运行配置，没有连接或修改 MySQL、Redis、RabbitMQ、OSS/FFmpeg，没有创建、修改或清理 Schema、表、Seed、报名、进度、Key、租约、消息、对象或伪数据。

## 2026-09-05 T12G.2 播放进度同步前端实现

- 范围与实现：分支 `codex/t12g2-progress-sync-web` 基于 `HEAD` 包含的 `2e51ade` 与 `main@aebc942` 实现。只修改既有 `HlsVideoPlayer`、`playback-progress`、学生端 `progress-sync`、`CourseLearningPage`、学习 API keepalive 适配及前端测试/交付文档；未修改 Java、Flyway、OpenAPI、事件 Schema、生成客户端、路由职责或 ARTICLE 独立完成命令。
- 事件语义：共享播放器默认按真实墙钟每 5 秒采样，暂停和自然结束分别产生中立 `PAUSE`/`ENDED` 原因；学生页把播放器样本映射为 `HEARTBEAT`、`PAUSE`、`ENDED`，页面路由守卫把切课映射为 `LESSON_SWITCH`、主动退出映射为 `LEAVE`。只有 `ENDED` 发送 `completed=true`，拖动不增加 watchedSeconds，倍速仍按墙钟累计。
- 可靠性与反馈：控制器只保留一个活动请求和一个合并快照，失败 watchedSeconds/最远位置/时间在内存中合并；网络、超时、429、5xx 最多三次有限重试，重试复用相同 clientEventId/请求体，逻辑保存成功后才生成新 ID。强制检查点最多等待约 3 秒，失败显示“进度尚未同步”并提供“重试/仍然离开”；`BUFFERED` 显示“正在同步”，`PERSISTED` 显示“已保存”，读取 `PERSISTED_ONLY` 显示“当前显示最近已保存进度”。
- 离页边界：SPA 路由由 `onBeforeRouteLeave` 统一拦截；`pagehide` 通过认证头和 `fetch keepalive` 一次性尽力发送 LEAVE，`beforeunload` 仅在确有未保存内存快照时启用。没有 localStorage、sessionStorage、IndexedDB、Cookie 进度写入，也没有卸载后的无限重试。
- 自动化结果：`pnpm.cmd --dir web-apps run check:api` 通过且生成客户端无漂移；`pnpm.cmd --dir web-apps run typecheck` 通过（8 workspace）；`pnpm.cmd --dir web-apps run test` 通过（36 个测试文件、236 项）；`pnpm.cmd --dir web-apps run build` 通过（三端构建，保留既有大 chunk 警告）；专项覆盖 5 秒采样、五类事件、完成门禁、失败合并、有限重试/稳定 ID、检查点超时、keepalive 认证头、dispose 清理和浏览器存储边界。
- 阶段性浏览器记录（已由下方最终真实验收覆盖）：当时通过正常 Course API 和课程详情页唯一核对课程 `5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057`“线性代数”、发布版本 `v4`、第 2 课 `f13f712f-b7d8-49c1-804f-58bded4e1c5a`“第 2 课 行列式的定义”、VIDEO；因当时浏览器为匿名会话而停止，未发送真实进度或触碰 OSS/媒体数据。最终受保护学生会话、五类事件、Redis 故障恢复、刷新恢复和精确清理结果见下方最终真实验收。
- 数据边界：本轮未连接或修改 `edu_platform_learning_dev`、Redis DB 4、Course/Media/OSS、RabbitMQ；未创建数据库、表、伪数据、进度事件、Redis Key、租约或 OSS 对象，故无本轮清理项。独立交付说明见 [`docs/delivery/T12G.2-progress-sync-web.md`](../delivery/T12G.2-progress-sync-web.md)。

## 2026-09-05 T12G.2 真实浏览器复核

- 学生测试账号登录成功，目标课时受保护内容加载成功；课程“线性代数”v4、第 2 课“行列式的定义”通过报名/访问事实、视频 READY 和播放授权门禁，视频实际时长约 12.2 秒。
- 播放约 1 秒后暂停以及自然播放结束均触发前端进度请求，但两者都返回 422；页面正确保持“进度尚未同步”，点击重试仍失败。真实 `PAUSE`/`ENDED` 持久化未通过，故不宣称五类事件链路验收成功。
- 点击退出学习后，路由守卫正确展示“重试/仍然离开”；选择“仍然离开”成功返回课程详情，失败检查点不会阻止用户明确离开。
- 只读核对专用 `edu_platform_learning_dev` 的目标课程/课时，验收后为 1 条聚合行（position 8、watched 8、completed false）和 1 条 `NULL` 事件类型记录；未观察到本轮新增的已接受 PAUSE/ENDED 事件。未创建数据库、表、伪数据、Redis Key、租约或 OSS 对象，无清理项。
- 结论：前置访问与前端失败反馈/离页分支通过；进度请求 422 是当前发布阻塞问题，需定位实际请求字段与服务端校验后重新验收。教师账号未用于本次学生端 T12G.2 复核，教师端不在本任务页面范围内。

## 2026-09-05 T12G.2 422 修复后复核

- 首轮 422 未能从浏览器诊断中取得可重复响应体；代码检查确认请求字段已按 OpenAPI 0.20.0 生成，页面此前只丢弃了服务端 `code`/`fieldErrors`。现已保留这些稳定诊断信息，并用稳定路由名和课程参数识别 `LESSON_SWITCH`。
- 重新打开同一学生会话后，暂停约 1 秒触发 PAUSE 并显示“已保存”；自然播放结束触发 ENDED，页面显示 100% 和“已保存”。专用 `edu_platform_learning_dev` 只读核对目标课时聚合为 `position=12`、`watched=21`、`completed=1`，事件表存在 PAUSE/ENDED；未创建伪数据或修改其他领域数据。
- 切课路由可正常切换；本阶段尚未覆盖的事件、刷新和 Redis 故障项已在下方最终真实验收中补齐。播放器提示在该阶段仅作现象记录，最终结论见下方。

## 2026-09-05 T12G.2 最终真实验收

- 固定验收对象：既有课程“线性代数”v4、第 2 课“行列式的定义”，未替换课程、课时或 OSS。真实浏览器开发日志逐项确认 `HEARTBEAT → 200/BUFFERED`、`PAUSE → 200/PERSISTED`、`LESSON_SWITCH → 200/PERSISTED`、`LEAVE → 200/PERSISTED`、`ENDED → 200/PERSISTED`。
- 切课初次未出现 `LESSON_SWITCH` 的原因已定位为动态路由复用同一记录，`onBeforeRouteLeave` 不会处理参数更新；前端改为 `onBeforeRouteUpdate` 后，路由守卫日志和服务端网络日志均确认 `LESSON_SWITCH`。真正离开学习空间的 `LEAVE` 也返回 `PERSISTED`。
- 强制检查点刷新：`ENDED/PERSISTED` 成功后刷新同一深链接，进度读取日志为 `200/PERSISTED`，页面恢复已保存进度。Redis 故障采用 Learning `18084` 的 Redis 端口 `1` 隔离实例，未执行 `FLUSHDB`；浏览器 `HEARTBEAT` 返回 `503/LEARNING.PROGRESS_BUFFER_UNAVAILABLE`，页面没有虚假成功，刷新读取为 `200/PERSISTED_ONLY` 并显示最近已保存进度；恢复正常 Redis 后有限重试再次得到 `200/BUFFERED`。
- 播放器提示复核：结束后曾出现“无法播放媒体”的瞬态提示；同期媒体元素 `readyState=4`、`error=null`，刷新后可重新从起点播放，且 `ENDED/PERSISTED` 正常。证据表明这是既有 HLS 结束态展示，不由 T12G.2 生命周期改动引入，也不影响重播。
- 数据审计与清理：验收前后目标聚合均为 `position=8`、`watched=8`、`completed=false`、`revision=1`、`buffer_version=0`，`last_event_at` 与原基线一致；事件表仅保留原有 `NULL` 事件。目标 Redis Hash 不存在、dirty ZSet 无目标成员，dirty cardinality 验收前后均为 0。未创建数据库、表、伪数据、OSS 对象或消息。

## 2026-09-05 T12G.2 合并与 T11C 前置审计

- 合并与清理：[PR #46](https://github.com/chengchen-ccnu/ccnu-edulion/pull/46) 在 Ready、可合并且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全部成功后，以普通 merge 合并为 `main@accec558b4d94e7e26c3caa5576203fe988c04aa`；本地 `main` 已快进同步，`codex/t12g2-progress-sync-web` 本地和远端分支均已清理。
- 优先级结论：下一任务选择 T11C。它的显式依赖 T11B、T12B 已合并，并直接阻塞 T18 发布前集成；T12C 和 T12H 在 backlog 中仍明确后移。T13 虽已满足独立前置并可与无冲突任务并行，但当前总控按单任务顺序先补齐学习测评主链的作业与成绩册缺口。
- 现状与缺口：OpenAPI 0.20.0、Learning V13、既有测评状态机、不可变试卷、作答/批改/关闭/公布与教师结果分页均可复用；`HOMEWORK` 已是合法类型。但现行 `opensAt`/`closesAt` 明确只允许 EXAM，作答仍是单一当前 attempt，且没有迟交策略、作答次数、跨测评成绩册、安全导出契约；教师端也尚未注册 `/teacher/gradebook` 实现路由。因此 T11C 需要先向前演进契约，再实现后端和冻结页面，不得由前端猜测字段。
- 前置结论：Git、T11B/T12B 领域能力、题目/媒体复用边界、固定 `/teacher/gradebook` 页面职责及生成客户端流程均满足；后续 grill 已完成 HOMEWORK 开放/截止、迟交、次数、成绩册人口、统一显示和导出安全的产品决策，原准备分支已改名为 `codex/t11c1-homework-gradebook-contract`。
- 数据与代码：本次总控只执行 GitHub 合并、Git 同步/分支清理、只读契约与代码审计及状态文档更新；没有修改 OpenAPI、Java、Python、Vue、Flyway、事件 Schema、生成客户端或运行配置，没有连接或修改 MySQL、Redis、RabbitMQ、OSS/FFmpeg，也没有创建、修改或清理数据库、表、Seed、伪数据、消息、对象或缓存 Key。

## 2026-09-05 T11C.1 作业规则、成绩册与 CSV 导出后端实施验证

- 门禁：在 `codex/t11c1-homework-gradebook-contract` 分支确认工作区初始干净，保留 `18cb028` 与 `9f31943` 规划提交；完整阅读 T11C 所需页面规格、路线图、OpenAPI、T11/T11A/T11B/T12B 交付文档。未修改其他功能分支或已合并 V1～V13 迁移。
- 契约：先将 `docs/api/openapi.yaml` 从 0.20.0 演进到 0.21.0，再生成 `web-apps/packages/api-client/src/generated/schema.d.ts`。新增学生课程任务/结果/开始入口、教师截止调整、成绩册矩阵和同步 CSV 下载契约；没有新增 Vue、页面路由、历史 attempt 查询、异步 XLSX 或 OSS 报表。
- Learning V14：只新增 `late_policy/late_until/max_attempts`、`attempt_sequence/late_submission`、`assessment_participant`、`course_student_number`、`course_student_number_counter`、截止/结算/导出审计表和必要唯一约束/索引。V14 向前替换 V10 的 EXAM-only 时间约束，保留既有 T11B 结果公布和旧 HOMEWORK 无时间节点数据的兼容读取；新 API 仍强制 HOMEWORK `closesAt`。
- 服务实现：HOMEWORK 迟交窗口、1～5 次不可变 attempt、最终 attempt 指针、开放/迟交期/关闭状态、报名/退课/重新报名投影、课程学生编号、自动关闭和有界结算已接入现有 Learning 测评模型。教师成绩册只读取本人 owner 的 HOMEWORK/EXAM，按最终 attempt 计算统计和分类汇总；未结算/待批时分数与数值统计为空，CSV 带 UTF-8 BOM、动态列、公式注入防护和导出审计。
- IAM 依赖：新增 `/internal/v1/users/nicknames` 批量投影，只返回 ID→昵称；Learning 使用超时和 DPAPI 注入的 `IAM_INTERNAL_SERVICE_TOKEN` 调用，IAM 不可用时保留课程编号与成绩事实并标记 `nicknameDependencyFailed`，不把昵称复制到 Learning Schema，不跨库 Join。新增令牌由既有本地初始化/加载脚本加密管理，不进入仓库。
- 自动化结果：V14 H2 Flyway 迁移通过；Learning `LearningAssessmentIntegrationTest` 21/21、IAM 定向测试 10/10 通过，覆盖时间/迟交、草稿不计次、最终 attempt/旧 attempt 404、统一成绩册门禁、编号/统计/CSV、截止调整和并发关闭；此前 T11B 16/16 回归也在新增迁移调整后复跑通过。`pnpm ... generate:api`、`check:api`、`typecheck` 通过，Vitest 36 文件 237 项通过。
- 真实验收：通过 `scripts/Invoke-WithLocalEnvironment.ps1` 在既有 `edu_platform_learning_dev` 执行 `LearningMysqlMigrationAcceptanceTest`，6 项中 5 项通过、1 项既有条件测试跳过；确认 V13→V14、旧 attempt `sequence=1`、V14 约束、两连接并发自动关闭一胜一负、PENDING 失败事实恢复为 COMPLETED，并按随机 ID 精确清理。T11C.1 Seed `Initialize` 1/1 和幂等重跑 1/1 通过，固定演示数据留在该开发库供演示。
- 完整验证：`mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 通过，8 个 Reactor 模块成功；Learning 76 项（12 项真实环境按开关跳过）、IAM 30 项（1 项真实环境按开关跳过），其他受影响模块无失败。`pnpm.cmd --dir web-apps run build` 通过，admin/student/teacher 三端生产构建成功，仅有既有大 chunk 警告。
- 未执行验收：真实 IAM HTTP 联调、真实 5,000/100 上限压测，以及停止/恢复 MySQL 进程期间的网络级故障验收尚未执行；本轮没有连接 Redis、RabbitMQ、OSS，未生成 CSV 文件或临时外部对象。演示 Seed 只写 Learning 固定测试数据，H2 Fixture 随进程释放。
- 交付状态：实施分支为 `codex/t11c1-homework-gradebook-contract`，关键提交为契约 `148ffd4`、兼容性修正 `f19f13e`、实现 `a7d94d5`；[PR #47](https://github.com/chengchen-ccnu/ccnu-edulion/pull/47) 已以普通 merge 合并，合并提交为 `ae628d0`，GitHub Java、Frontend、Python 和 OpenAPI generated source 共 8 项检查全部通过。没有实现 T11C.2 Vue 页面或路由。剩余风险是完整多进程部署、IAM HTTP 联调和网络级数据库故障需要发布环境验证。

## 2026-09-06 T11C.1 合并后真实验收与容量缺陷

- 真实 IAM HTTP：在当前 main 构建的 IAM jar、local profile、隔离 18081 端口和 `edu_platform_iam_dev` 上，`POST /internal/v1/users/nicknames` 对一个已知合成学习者和一个未知 ID 返回 200；响应只包含已知 ID→`测试学生`。错误 `X-Internal-Caller` 和错误 `X-Internal-Service-Token` 均返回 403。Learning `HttpLearnerDirectoryClientRealAcceptanceTest` 真实联调 2/2 通过：部分结果保留已知昵称并省略未知 ID；100ms HTTP 超时在有界时间内降级为空映射并标记 `dependencyFailed=true`。
- CSV 安全回归：`LearningAssessmentIntegrationTest#completedGradebookUsesStableNumbersAndExportsSafeWideCsv` 1/1 通过，实测覆盖 UTF-8 BOM，以及 `=SUM(A1)`、`@cmd` 昵称被加单引号后写入 CSV；没有生成文件或外部对象。
- 容量验收：在仅 Learning 的专用 local/test Schema（`edu_platform_learning_t11c1_acceptance`、`_v2`、`_v3`，均已在测试后 DROP 且确认不存在）创建固定 5,000 条报名/课程学生编号和 100 场 HOMEWORK/EXAM。成绩册请求未进入 CSV 或堆峰值统计，而是在 `LearningAssessmentService.synchronizeCourseGradebook` 中为每个学生×测评逐条执行 `reactivateParticipant`，形成约 500,000 次 N+1 参与者写入；线程栈和 MySQL 事务状态均确认该路径。容量验收因此阻塞，不能记录 gradebook/CSV 响应时间、内存、行列数或 owner 越权的“通过”结果。
- 数据清理：容量 Fixture 使用确定性 ID、local/test Schema 和 `finally`/显式清理入口；三套临时 Schema 删除测试均 1/1 通过。`edu_platform_learning_dev` 的明确演示 Seed 未清理；未操作生产数据、OSS、RabbitMQ 或 Redis Key。验收中断产生的 dev 回滚事务由 MySQL 自动处理，没有强杀数据库或用户进程。
- 结论：这是 T11C.1.1 修复前的历史缺陷记录，已由下一节的批量名单投影和容量复验 supersede；当时不能进入 T11C.2，也没有实现前端业务代码。

## 2026-09-06 T11C.1.1 成绩册应交名单批量投影与容量修复

- 分支与范围：实施分支为 `codex/t11c1.1-gradebook-roster-batch`，关键提交 `89effa5`；[PR #49](https://github.com/chengchen-ccnu/ccnu-edulion/pull/49) 的 Java、Frontend、Python 与 OpenAPI 共 8 项 CI 全部成功，并以普通 merge 合并到 `main@87bbfa6`。没有实现 T11C.2 Vue 页面、路由或前端业务代码；没有使用 Redis、RabbitMQ、分布式锁、OSS、生产数据或新增微服务。V14 文件保持未修改，本任务没有保留新的数据库迁移。
- 写路径：移除成绩册读取阶段的 `synchronizeCourseGradebook` 写入。测评发布、课程报名/重新报名、退课和最终关闭分别在现有 Learning 事务中使用 JDBC Batch/集合式 SQL 维护 HOMEWORK/EXAM 参与者。唯一键吸收重复初始化；已为 ACTIVE 的参与者不更新 revision；只恢复 WITHDRAWN 行；关闭后的名单不再因退课或重新报名改变，关闭事务在冻结前补齐名单。
- 查询路径：`teacherGradebook` 使用只读事务；学生分页只查询当前页课程编号和当前页参与者/最终 attempt，筛选、排序和总数由 SQL 完成，成绩册统计和 HOMEWORK/EXAM 平均分使用数据库聚合。昵称关键词只在确需搜索时调用一次 IAM 批量投影，普通分页不读取页外昵称。
- CSV 路径：导出通过 `StreamingResponseBody` 写入 UTF-8 BOM，按每批 250 名学生读取、组装和刷新，未构造 5,000×100 完整成绩格集合或完整 `byte[]`。导出审计记录总行数 5,000 和 100 场测评。
- 回归结果：`LearningAssessmentIntegrationTest` 25/25 通过，覆盖读路径无写、发布/报名/退课/重报/关闭冻结、ACTIVE revision 稳定、并发批量幂等、当前页昵称搜索、BOM 和 `=SUM(A1)`/`@cmd` 公式注入防护。真实 IAM HTTP 验收返回已知昵称 `测试学生`、未知 ID 省略，错误 caller/token 均 403；Learning 客户端的部分降级和 100ms 超时降级通过，超时返回空昵称映射并标记 `dependencyFailed=true`。
- 真实容量结果：干净构建后在专用 local/test MySQL Schema 创建 5,000 条报名/课程学生编号、100 场 HOMEWORK/EXAM 和 500,000 条参与者；幂等初始化重复执行通过。成绩册耗时 31,017ms，JVM 堆峰值 196.6MiB，总数 5,000，当前页 100 行，100 场生成 304 列；页外 4,999 名学生没有进入响应对象。CSV 流式耗时 190,014ms，JVM 堆峰值 366.5MiB，5,001 行（表头+5,000 学生）和 304 列，UTF-8 BOM 通过。H2 公式注入回归确认危险昵称单元格均加单引号；owner 越权返回 404/不可见。
- 清理：容量测试只允许 `edu_platform_learning_t11c1_acceptance`、`_v2`、`_v3`、`_v4`、`_v5` 五个明确专用 Schema，使用固定前缀/ID、幂等初始化和精确逆外键清理；最终每个 Schema 均 DROP 并确认不存在。`edu_platform_learning_dev` 中已明确标记的 T11C.1 演示 Seed 保留，未清理、未覆盖；没有创建其他领域数据。
- 下一步门禁：T11C.1.1 已合并并满足 T11C.2 的后端契约与容量前置；T11C.2 页面仍待独立任务实施。

## 2026-09-06 T11C.1.1 合并与 T11C.2 前置审计

- 合并与清理：[PR #49](https://github.com/chengchen-ccnu/ccnu-edulion/pull/49) 已以普通 merge 合并，合并提交为 `87bbfa6`；本地 `main` 已快进同步到同一提交。用户明确指定的 `codex/t11c1.1-gradebook-roster-batch`、`codex/t11c1-homework-gradebook-contract` 和 `codex/t11c1-merge-record` 本地及远端分支均已删除。
- 契约门禁：`main` 已包含 OpenAPI 0.21.0、对应生成客户端、课程任务列表/详情/开始/最后结果接口，以及教师成绩册分页和流式 CSV 接口。T11C.1.1 的只读成绩册、名单冻结、真实 IAM HTTP 和 5,000×100 容量验收均已有证据；T11C.2 无需新增或猜测 DTO。
- 页面与复用门禁：完整复核 `docs/frontend/page-specs.md` 后确认 `/courses/:courseId`、`/learn/:courseId/tasks`、`/learn/:courseId/tasks/:assessmentId`、其 `/result`、当前课时任务区、`/practice` 和 `/teacher/gradebook` 的职责已经冻结。现有 T12B 答题、结果、媒体解析和教师批改面板可复用；T11C.2 只能增加课程任务编排与成绩册页面，不复制答题器、结果页或批改器。
- 数据与范围：本次总控只执行 GitHub 合并、Git 同步/分支清理、只读前置审计和状态文档更新；没有修改 OpenAPI、Java、Python、Vue、Flyway、事件 Schema、生成客户端或运行配置，没有连接或修改 MySQL、Redis、RabbitMQ、OSS/FFmpeg，也没有创建、修改或清理数据库、表、Seed、伪数据、消息、对象或缓存 Key。
- 准备结论：T11C.2 前置条件满足，已从 `main@87bbfa6` 准备 `codex/t11c2-homework-gradebook-web`。后续功能任务必须先保持 `/practice` 仅含 PRACTICE，再实现课程任务深链、当前课时精确过滤、最后 attempt 结果和教师成绩册的 URL 恢复/部分错误/安全导出；不得扩大到后端契约、历史 attempt、附件作业、免交/个别延期、排名或异步 XLSX。

## 2026-09-06 T11C.1.2 Learning Course 事实适配器向前兼容修复

- 分支与范围：从最新 origin/main@87bbfa6f 创建 codex/t11c1.2-course-facts-forward-compat；初始工作树干净。只修改 Learning Course 事实适配器、对应回归测试、T11C.1.2 交付文档和路线图记录；T11C.2 PR #50 保持 Open，未修改、合并或关闭。
- 实现：HttpCourseFactsClient 的 Published、Teacher、Chapter、Lesson 私有响应 DTO 局部增加 JsonIgnoreProperties(ignoreUnknown=true)，允许 Course 合法新增 title、sequence、previewable 等展示字段。Learning 读取的必需标量改为严格文本节点，并在裁剪事实前校验非空、访问类型、课时类型、章节/课时结构；全局 Jackson 未知字段失败配置保持开启。
- 语义：Course 公开 404 继续映射 Learning 404；公开读取 403、依赖 5xx、超时和无效响应继续安全返回 503；owner 接口 404/403 继续不可枚举为 Learning 404。没有改 Course 响应、OpenAPI、页面规格、接口版本或 T11C.2。
- 自动化结果：HttpCourseFactsClientTest 与 LearningCourseFactsHttpAcceptanceTest 定向 6/6 通过；整个 learning-service 测试 88 项执行、0 失败、16 项既有条件测试按开关跳过。HTTP 验收通过随机端口 Learning 任务列表真实入口调用 Course 形状响应，返回 200，任务/课程/课时 ID 和任务类型正确，Course 请求一次完成。
- 数据边界：未连接或修改 MySQL、Redis、RabbitMQ、OSS 或生产数据；测试只在临时 H2 learning_course_facts_http 中应用既有 V1～V14 迁移，创建一条合成报名、一条已发布 HOMEWORK、一条课程分配和一条参与者并按精确 ID 清理；没有新增 Flyway 迁移、持久 Seed 或隐私数据。
- 完整验证：`mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 通过，8 个 Reactor 模块全部成功；Learning 88 项执行、0 失败、16 项既有条件测试按开关跳过。
- 交付状态：提交 `cc32a41` 已由 [PR #51](https://github.com/chengchen-ccnu/ccnu-edulion/pull/51) 以普通 merge 合并，合并提交为 `711ec6b`；Java、Frontend、Python、OpenAPI generated source 共 8 项 CI 检查全部通过。本地 `main` 已快进同步，本地和远端 `codex/t11c1.2-course-facts-forward-compat` 均已删除。
- T11C.2 恢复门禁：最新 `main` 已以普通 merge 合入 `codex/t11c2-homework-gradebook-web`；此前课程任务 503 的已知反序列化根因已经进入该分支。PR #50 仍保持 Open，下一步只继续真实 Course 任务成功链路、教师会话成绩册/CSV、目标宽度响应式和离页清理验收，不扩大产品、OpenAPI 或后端范围。

## 2026-09-05 T11C 拆分规划与 T11C.1 前置审计

- 拆分结论：经完整产品规则访谈，原 T11C 拆为顺序实施的 T11C.1“作业规则、成绩册与 CSV 导出后端契约”和 T11C.2“课程作业/考试与教师成绩册前端”。T11C.1 先演进 OpenAPI、Learning 数据与服务端状态机并重新生成客户端；T11C.2 只能在其合并后消费生成契约，不得并行猜测 DTO。
- 作业规则：HOMEWORK 复用现有测评模型，`opensAt` 可空、`closesAt` 必填；迟交仅支持默认 `DISALLOW` 和带 `lateUntil` 的 `ALLOW_UNTIL`，不自动扣分。教师可提前或延后尚未关闭任务的截止节点，但下一有效节点距服务端当前时间至少一小时，迟交窗口至少一小时；写入须审计并使用 revision/`409`。关闭或归档后不可重开，发布后题目、分值、次数和迟交策略不可改。
- 作答与关闭：HOMEWORK 最多提交 1～5 次、默认一次；草稿不计次，每次正式提交产生不可变 attempt，学生无需等待主观题批改即可在有效窗口开始下一次，最后一次才是唯一有效版本。旧 attempt 只保留内部审计，普通学生/教师接口均不可枚举。`ALLOW_UNTIL.closesAt` 只开启迟交期，最终 `lateUntil`、`DISALLOW.closesAt` 或不可撤销的教师立即结束才锁定并结算最后非空草稿；空白草稿记缺交。
- 自动补偿：截止前已有成功保存的非空草稿立即投影为“已交，系统处理中”，绝不能因结算故障显示缺交；系统后台有限重试并持续自动补偿，不要求教师操作。处理完成前禁止人工批改、成绩/统计、公布和导出。服务恢复后仍按原服务端截止裁决，不给学生补时或接受截止后的答案。
- 应交人口：作业开放时以课程报名事实建立应交名单，开放期新报名者加入，最终关闭后冻结；退课保留历史并标记 `WITHDRAWN`，不计缺交或平均分，有效窗口内重新报名恢复应交。首期不做免交或个别延期。每门课程分配不回收的四位课程学生编号，既有报名按首次报名时间及 learnerId 稳定回填，新报名原子领取，重新报名沿用。
- 批改与可见性：客观题提交时仍可自动判分，但 HOMEWORK/EXAM 最终关闭前教师只能看提交进度、次数、时间和迟交事实，不能看答案、正确性或分数。关闭且结算完成后只批改最终 attempt；批改器允许查看正在处理的答卷和已保存单题分数，但结果列表、成绩册、统计和导出必须等单场所有最终提交 `GRADED` 后一次性显示。后续改分保留审计和 revision/`409`，不重新隐藏，原子更新最新成绩、时间和统计；学生可见性继续由 T11B 独立公布事实控制。
- 成绩册口径：只包含 HOMEWORK/EXAM，按提交状态、批改状态和迟交事实分别建模；默认课程学生编号稳定排序，支持课程/测评/类型/状态/迟交/昵称或编号/分页 URL 筛选。存在待结算、异常、待批或部分批改时不返回均分、极值或分布。完成后同时给出已批平均、缺交按零的所有学生平均、应交/准时提交/最终提交/缺交/待批、最高/最低及区间分布；退课排除。课程作业与考试分别百分制等权平均，只纳入已关闭且完成批改的任务，PRACTICE 永不进入正式成绩。
- 身份与降级：成绩册显示 IAM 昵称和 Learning 课程学生编号，不展示完整联系方式；跨域读取只能走内部 API，不得跨 Schema Join。IAM 暂不可用时保留编号和成绩事实、昵称显示部分失败，并使用有限超时与短 TTL 缓存，不复制 IAM 事实到 Learning。
- CSV：同步 UTF-8 BOM 宽表，一次一门课程、最多 5,000 名学生和 100 场已完成 HOMEWORK/EXAM。固定编号/昵称列，每场生成数值成绩、提交状态、迟交三列，并附作业/考试平均分；缺交写零，退课或非应交留空。只导出当前筛选，防公式注入，服务端复核 owner 并记录不含内容的审计；不包含联系方式、题目、答案、解析或评语，不使用 OSS、`REPORT_EXPORT`、RabbitMQ 或异步 XLSX。
- 页面边界：PRACTICE 保留 `/practice`，HOMEWORK/EXAM 移至课程上下文。已报名用户从公共课程详情进入 `/learn/:courseId/tasks`；当前 `/learn/:courseId/:lessonId` 只展示当前课时任务且不提供总览链接。课程任务作答与最终结果使用 `/learn/:courseId/tasks/:assessmentId` 及其 `/result`，内部复用既有组件。教师 `/teacher/gradebook` 只做矩阵/统计/导出，答卷深链回 `/teacher/assessments`，不复制批改器。
- 前置与验收：T11B、T12B 已合并，OpenAPI 0.20.0、Learning V13、题目快照、作答/批改/关闭/公布、报名事实、生成客户端和固定页面职责可复用；现行 EXAM-only 时间、每学习者/测评唯一 attempt、仅已有 attempt 的教师结果分页和缺失 gradebook/export 正是 T11C.1 的任务缺口。后端真实验收须覆盖多次提交、迟交、时间调整、自动/立即关闭、多实例单次结算、MySQL 故障恢复、报名/退课/重新报名、旧 attempt 404、统一显示门禁、统计/CSV 安全、owner/越权及 409/422/429/503；不需要 OSS、FFmpeg 或 RabbitMQ。
- 分支与数据：从 `main@accec558b4d94e7e26c3caa5576203fe988c04aa` 准备 `codex/t11c1-homework-gradebook-contract`。本次总控只修改规划文档，没有修改 OpenAPI、Java、Python、Vue、Flyway、生成客户端或配置，没有连接或修改 MySQL、Redis、RabbitMQ、OSS/FFmpeg，也没有创建、修改或清理 Schema、表、Seed、伪数据、消息、对象或缓存 Key。

## 2026-09-06 T11C.2 课程作业/考试与教师成绩册前端实施验证（初始实现）

- 门禁：在 `codex/t11c2-homework-gradebook-web` 确认工作区初始干净，保留规划提交 `b068cfe`；复核页面规格、T11/T11A/T11B/T12A/T12B/T12F.2/T12G.2 交付记录及 OpenAPI 0.21.0。没有修改 Java、Python、Flyway、RabbitMQ、Redis、OSS 或 OpenAPI。
- 学生端：新增 `/learn/:courseId/tasks`、`/learn/:courseId/tasks/:assessmentId` 及 `/result`，课程详情只向已报名且具备 `learning:access` 的用户提供入口；当前学习页调用 `listLearnerCourseTasks` 并传递精确 `currentLessonId`。作答页和结果页复用 T12B 的 `AttemptSaveController`、服务端时间校准、媒体授权和 `resultDisclosure` 安全投影；HOMEWORK/EXAM 不再从练习中心渲染或通过旧练习深链作答，迟交提交前显示最终版本/迟交标记提示。
- 教师端：新增受 `teaching:access` 保护的 `/teacher/gradebook`，URL 规范化并恢复 `courseId`、`assessmentId`、`type`、`submissionStatus`、`gradingStatus`、`late`、`keyword`、`page`、`size`、`sort`、`direction`；PENDING 阶段隐藏成绩、平均分、极值、分布并禁止成绩排序；IAM 昵称失败保留课程学生编号和成绩事实；记录点击复用 `/teacher/assessments?courseId=&assessmentId=&attemptId=&panel=grading`。CSV 使用生成客户端 text response 封装为 Blob，稳定 `clientRequestId`、当前筛选、可取消长请求、Content-Disposition 文件名和 Object URL finally 清理。
- 初始实现阶段自动化结果：`pnpm.cmd --dir web-apps install --frozen-lockfile` 通过；`generate:api`、`check:api`、`typecheck` 通过；Vitest 全量 39 个文件/248 个测试通过；`pnpm.cmd --dir web-apps run build` 三端通过，仅保留既有 student/teacher 大 chunk 警告；新增成绩册 API/URL 状态、课程任务 API、路由边界和 PRACTICE 过滤测试通过；`git diff --check` 通过。最终复核补充导航回归后，测试总数变为 251 项。
- 初始实现阶段真实验收边界：当时未启动或连接 Gateway、IAM、Course、Learning、Teacher Web，未创建/修改/清理数据库、表、Seed、Redis Key、RabbitMQ 消息、OSS 对象或 CSV 文件；本次最终复核已补做公共 HTTP/401/404 探针和既有 Seed 切换收口。认证浏览器项目的当前状态见下方“最终真实验收复核”。

## 2026-09-06 T11C.2 最终真实验收复核（首次尝试，内置浏览器初始化阻塞）

- 分支与范围：继续在 `codex/t11c2-homework-gradebook-web`、现有 [PR #50](https://github.com/chengchen-ccnu/ccnu-edulion/pull/50) 上工作，没有创建新分支、没有合并 PR。确认 PR 仍为 OPEN，head/base 正确，复核前工作树只有本轮导航修复两处前端修改。没有改动 Java、Python、OpenAPI、Flyway、事件、全局页面规格或后端契约。
- 导航缺陷修复：`course-tasks`、`course-task-attempt`、`course-task-result` 全部归入“我的学习”高亮组；HOMEWORK/EXAM 不再进入“练习中心”高亮组；全局导航标签、顺序和槽位未改变。新增参数化回归覆盖 3 个路由。
- 真实服务探针：复用现有 Gateway 8080、IAM 8081、Course 8082、Media 8083、Learning 8084、Operation 8085 及 student-web/teacher-web 5173/5174。固定课程公开列表/详情 200，未知公开课程 404，未认证课程任务和教师成绩册 401 且返回统一错误信封；两个前端深链接返回 200 HTML。IAM 健康 503 仅由本地 SMTP 未配置导致，数据库、Redis、ping/readiness 组件正常。
- 内置浏览器结果：按用户要求重试内置浏览器，仍在内核资源初始化阶段失败：`failed to write kernel assets: 系统找不到指定的路径。 (os error 3)`。因此以下认证态项目保持明确未验收，未用脚本、假 Token 或猜测密码替代：已报名课程详情进入任务总览、任务深链刷新/返回、作答保存/刷新恢复/提交/最后结果、多次提交只显示最后有效 attempt、未公布信息防泄露、PRACTICE 过滤、PENDING/READY 成绩册、昵称部分失败、批改跳转、CSV BOM/字段/取消、403/409/422/429/offline、响应式、键盘焦点、控制台和离页资源释放。
- 自动化结果：`pnpm.cmd --dir web-apps run generate:api`、`check:api`、`typecheck` 通过；全量 Vitest 39 个文件/251 项通过；受影响 8 个文件/62 项通过；student/teacher/admin 三端 `build` 均通过，仅有既有大 chunk 警告；`git diff --check` 通过。
- 数据与清理：只使用 `edu_platform_learning_dev` 这一明确 local/test Schema。为分离两个既有 Seed 的固定断言，执行 T11C.1 Initialize → T12B Initialize → T12B Cleanup → T11C.1 Initialize；没有创建临时 Schema、CSV 文件、Redis Key、RabbitMQ 消息、OSS 对象或其他伪数据。最终保留 T11C.1 2 场幂等演示 Seed；T12B 6 场 Seed 脚本及其 Initialize/Cleanup/Rebuild 机制保留，未删除项目既有数据脚本。
- 提交与 CI：导航修复及文档提交为 `0aee0f8`，已推送到 [PR #50](https://github.com/chengchen-ccnu/ccnu-edulion/pull/50)；最新 Java、Python、Frontend、OpenAPI generated source 共 8/8 CI 检查成功，PR 仍 OPEN 且未合并。
- 结论：前端实现、导航修复、契约生成、自动化和构建通过；本次“最终真实浏览器验收”不能标记为全绿，阻塞点是内置浏览器初始化错误以及认证 Seed 密码未进入当前安全进程。该阻塞解除后应从上述未验收清单继续，不应重复 T11C.1.1 的 5,000×100 后端容量测试。

## 2026-09-06 T11C.2 重新执行未完成的真实浏览器验收

- 分支与 PR：继续使用 `codex/t11c2-homework-gradebook-web` 和现有 [PR #50](https://github.com/chengchen-ccnu/ccnu-edulion/pull/50)，没有创建新分支、没有合并 PR。开始前 `git status` 为干净；本轮只产生学生端路由顺序、路由回归测试和三份 T11C.2 文档修改。
- 内置浏览器：上一轮 `failed to write kernel assets: 系统找不到指定的路径 (os error 3)` 本轮没有复现。通过内置浏览器复用既有学生 local/test 会话，未读取、猜测或输入密码，也未绕过 Gateway。真实页面控制台 `error/warning` 日志均为空，没有观察到新增敏感信息。
- 导航与学生入口：从 `/courses/00000000-0000-4000-8000-000000000201` 的已报名课程详情进入“课程作业与考试”后，浏览器到达 `/learn/00000000-0000-4000-8000-000000000201/tasks`；“我的学习”具有 `aria-current="page"`，“练习中心”没有高亮。原缺陷是通用 `/learn/:courseId/:lessonId` 排在三个静态课程任务路由之前，`tasks` 被当作课时 ID 并显示“课时不存在”；现已将三个静态路由前移，并以参数化路由匹配测试固定回归。
- 学生路由恢复：课程任务总览、课程任务作答和最后结果深链接分别命中 `course-tasks`、`course-task-attempt`、`course-task-result`；普通 `/learn/:courseId/:lessonId` 仍命中 `course-learning`。任务总览刷新后 URL/标题保持；浏览器返回可回到课程详情；当前课时页只显示“本课作业与考试”区域，不再提供练习中心入口。
- 学生安全边界：`/practice` 实际页面只显示 PRACTICE，固定 T11C.1 Seed 中的 HOMEWORK/EXAM 没有泄露到练习中心。任务总览、作答和结果页面均能显示服务错误态，但三个真实任务接口都返回前端“服务暂时不可用”，因此不能宣称作答开始、答案保存/刷新恢复、提交、最后有效 attempt、多次提交收敛、结果公布门禁或未公布成绩/答案防泄露已通过。
- 503 根因证据：Course 实际发布 JSON 的章节/课时包含 `id`、`title`、`sequence`、`previewable` 等字段；Learning `HttpCourseFactsClient` 的严格适配记录只声明 `Chapter(List<Lesson>)` 和 `Lesson(String id, String type)`，且严格 Jackson 开关开启。真实课程任务请求因此被适配器转为 `503 LEARNING.COURSE_DEPENDENCY_UNAVAILABLE`。这是既有 Learning→Course 后端适配实现问题，本轮按范围没有修改 Java、OpenAPI 或后端契约。
- 教师端：在同一学生会话访问 `/teacher/gradebook?courseId=00000000-0000-4000-8000-000000000201`，真实导航到 `/forbidden` 并显示 403 权限提示。没有可安全注入的教师密码，因此没有伪造 PENDING/READY、昵称降级、批改深链或 CSV 验收结论；相应成功链路仍是阻塞项。
- 交互与响应式：任务页键盘 Tab 可到达导航、返回课程详情、重试按钮，焦点轮廓可见。内置浏览器尝试 1440×900、768×1024、390×844 三组 viewport 时实际有效宽度均被固定为 473px；在该有效宽度下 `scrollWidth === clientWidth`，未观察到横向溢出，但不能据此替代三个目标宽度的完整验收。页面离开后未做成功作答/CSV 请求场景下的 AbortController、计时器和 Blob URL 实测。
- HTTP/自动化与构建：`generate:api`、`check:api`、`typecheck` 均通过；全量 Vitest 39 个文件/255 项通过，路由/导航/T11C.2 边界专项 3 个文件/47 项通过；student/teacher/admin 三端 build 通过，仅保留既有大 chunk 警告；`git diff --check` 通过。真实 HTTP 继续确认公共课程 200、未知课程 404、未认证课程任务/成绩册 401 统一错误信封。
- 数据与清理：没有创建数据库、表、临时 Schema、账号、CSV 文件、Redis Key、RabbitMQ 消息或 OSS 对象。只复用明确的 `edu_platform_learning_dev` 既有 T11C.1 2 场演示 Seed；本轮没有新增验收数据，Seed 最终状态保持不变，未产生需要额外清理的临时数据。没有重复 T11C.1.1 已通过的 5,000×100 容量验收。
- 提交与 CI：路由顺序、回归测试和文档已提交为 `051e864 fix(web): resolve course task route precedence` 并推送到 PR #50；最新两组 GitHub CI 的 Frontend、Java、Python、OpenAPI generated source 共 8/8 检查成功，PR 仍 OPEN 且未合并。
- 结论：内置浏览器问题已解除，路由冲突已修复并有真实浏览器与自动化证据；T11C.2 仍不能标记为全绿，当前未完成项由任务 API 503 后端适配故障和教师会话缺失造成。应先修复/确认后端适配实现并提供安全教师 local/test 会话，再从任务成功链路和成绩册 CSV 流程继续验收。

## 2026-09-07 T11C.2 最新代码真实验收与范围内修复

- 分支/PR：`codex/t11c2-homework-gradebook-web`、[PR #50](https://github.com/chengchen-ccnu/ccnu-edulion/pull/50)，开始时干净；保留 `ed1323c` 普通合入 T11C.1.2，不创建分支或合并 PR。
- 已重新阅读 AGENTS、完整 page-specs、交付文档、backlog、verification 与注释规范。只修改成绩册、必要 Gateway 路由/教学能力、测试 Fixture 和对应文档，未修改 OpenAPI、生成类型、领域生产逻辑或迁移。
- 旧 Learning PID 28108（9 月 5 日）已停止；包装脚本构建最新 jar 后于 09:32:13 启动 PID 86468。teacher-web 5174 启动；Gateway/IAM/Course 也重新构建并通过包装脚本启动。解决本地旧 JWT 配置及 Gateway/IAM Redis DB 不一致，不关闭会话版本校验/限流。Course 曾不可达，恢复后真实 Gateway→Learning→Course 任务列表 200。
- 学生浏览器：临时作业 A 保存、刷新恢复、09:37:44 提交；第二次从空白草稿选 B 保存、09:38:33 提交，结果刷新只显示 B。考试 C 保存、刷新继续倒计时（58:55，不重置），09:42:02 提交成功。旧 attempt API 404；最后结果 200，gradeVisible/answerVisible=false，score/correctAnswer/analysis=null，页面不泄露成绩或参考答案。
- 教师浏览器：正常登录 `teacher1@example.invalid` 显示测试教师一，学生进入真实 403；全范围 PENDING 隐藏分数及统计，HOMEWORK 为 READY。刷新恢复完整查询筛选，keyword=0005 组合筛选只剩 1 行。修复 SCORE URL 与实际排序不一致，回归先失败后通过，真实刷新显示成绩排序。点击 READY 作业的 100 分单元格真实进入批改深链并显示 learnerId 与人工批改面板；PENDING 考试深链另受下述 results 409 阻塞。
- IAM 昵称故障：暂时停止当前 IAM，真实成绩册 200、nicknameDependencyFailed=true；页面提示部分昵称不可用，编号/成绩保留。恢复后真实请求标记 false，昵称重新显示。最终 IAM PID 81992、Gateway PID 85188、Course PID 92712、Learning PID 86468；故障注入均已恢复。
- CSV：浏览器实际点击显示生成及下载开始；教师正常会话经 Gateway 下载的文件为 200、674 字节、EF BB BF、1 数据行、10 个中文字段，Content-Disposition 正常。修复 text 解码丢 BOM 的 Blob 重编码缺陷，字节回归通过。下载过快，浏览器取消没有取得可靠终态；组件测试覆盖取消 abort、迟到成功不创建文件、卸载中止和 Object URL revoke，不冒充真实内部清理观测。
- 错误：真实匿名成绩册 401；学生成绩册 403；未知课程及旧 attempt 404；已提交 draft 409；成绩册 size=0 为 422；对本轮已提交 attempt 有界 21 次提交（前 20 次冲突 409、第 21 次 429 GATEWAY.RATE_LIMITED、Retry-After=60），未新增有效提交。Course 不可达阶段 503，恢复后 200。暂停 Gateway 造成实际网络不可达，浏览器显示离线且保留矩阵，恢复后重试成功；不是设备系统离线开关测试。
- 响应式：真实浏览器读取 window.innerWidth=390、768、1440，学生考试/结果和教师成绩册按实际宽度复核。教师文档 clientWidth/scrollWidth 分别 375/375、753/753、1425/1425；没有页面横向溢出，矩阵在容器内滚动。临时 viewport 已 reset；没有用旧固定 473px 结果替代。
- 范围内修复：Gateway 补两个精确 gradebook 路由、teaching:access 与 Content-Disposition；CSV 单 BOM；取消时立即收口、迟到请求不覆盖新状态；SCORE 深链确认 READY 后重读，PENDING 保留非成绩排序，清空课程中止旧请求。新增实际 Vue 组件测试并恢复 URL mocks，注释人工检查通过。
- 测试命令：`pnpm.cmd --dir web-apps run typecheck`、`run test`、`run build`、`run check:api` 全通过；40 文件/269 项、三端类型/生产构建、客户端无漂移，仅既有大 chunk 警告。包装脚本内 `mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl learning-service,gateway-service -am verify` 通过；Gateway 22 项含 1 跳过、Learning 89 项含 17 跳过、shared-kernel 1 项，零失败。显式 Fixture 初始化/报告/清理单独通过。没有重复容量压测。
- 临时数据：未创建数据库/表/迁移/账号/课程/题目。专用 edu_platform_learning_dev 新增两场固定临时任务、2 份题目快照、分配/应交名单及 3 attempt/答案/幂等/截止结算派生行；两次错误答案产生 1 条 wrong_question 累计与 2 条 occurrence。第一次清理因保护断言回滚，核实累计恰好 2 次且无重做后补精确清理。12:29:40 CLEANUP 成功；12:30:50 REPORT 确认临时 assessment=0、attempt/错题/occurrence/本轮审计列表为空，T11C.1 演示仍 2 场。四条本轮 CSV 审计按交付文档 UUID 逐条限定 owner/course 删除，HTTP CSV 本地文件已删除。HTTP 会话正常 logout，限流 Key 自然 60 秒过期，不清除原有账号会话/审计。Fixture INITIALIZE/REPORT/CLEANUP 可复现，无生产自动 Seed。
- **尚未通过**：READY 作业批改深链已真实通过；待批考试批改深链真实返回 `409 LEARNING.RESULTS_NOT_READY`。教师页依赖分页 results 列表，但该接口要求整场无待批，OpenAPI/Controller 没有教师单 attempt GET；第一页之外 attempt 的定位也尚未完整验证。按契约不足规则停止扩展并报告，未用评分 POST 或虚构 API 绕过。真实 CSV 响应过快，连续并发取消未得到可靠已取消终态；另一次“发起 CSV 后立即跳转课程管理”真实离页无残留下载提示且浏览器 error/warn 为空，但内部请求、计时器、Blob 释放仍待专门网络级观测，组件回归不等同全部真实验收。T11C.2 不标记全验收完成。
- 完整启动、数据 UUID、复现命令、调用边界及后续依赖已同步 [交付文档](../delivery/T11C.2-homework-gradebook-web.md)、backlog、local-environment。新提交推送后的 CI 以 PR #50 实际检查为准，不沿用旧提交绿色结果。
## 2026-09-07 T11C.1.3 教师待批答卷读取契约规划与前置审计

- Git 与任务状态：从工作区干净且与 `origin/main` 一致的 `main@711ec6b6cbd3bd779003f134fe96746b8ca4fc0b` 创建 `codex/t11c1.3-teacher-attempt-read`。T11C.1、T11C.1.1、T11C.1.2 已分别通过 PR #47、#49、#51 合并；T11C.2 实现仍位于保持 Open 的 PR #50（head `4669955`），本规划不合并、关闭或修改其业务代码。
- 真实阻断：T11C.2 真实浏览器验收从成绩册进入待批 HOMEWORK/EXAM 的既有批改面板后，页面调用整场 results 接口；因为仍有待批答案，服务端按既有防泄露规则返回 `409 LEARNING.RESULTS_NOT_READY`，教师因而无法开始第一份人工批改。页面若先拉整场结果分页再按 learner 查找，也无法稳定定位第一页之外的最终 attempt。此前课程任务 503 已由 T11C.1.2 修复，本缺口是独立的读取契约缺失。
- 新契约：T11C.1.3 将 `docs/api/openapi.yaml` 从 0.21.0 向前升级到 0.21.1，新增 `GET /api/v1/teacher/attempts/{attemptId}`，响应直接复用已有 `AttemptResult`，服务实现复用已有 `LearningAssessmentService.teacherResult`，并重新生成前端客户端。该接口只读取指定的最终有效 attempt，不增加第二套结果 DTO、整场待批列表或历史 attempt 查询。
- 授权与状态：仅具有教学能力且拥有对应课程/测评的 owner 教师，可在 HOMEWORK/EXAM 整场已经关闭且草稿结算完成后读取最后有效待批 attempt；不要求该场全部人工批改完成。非 owner、旧 attempt、attempt 与课程/测评资源不匹配均安全返回 404。`courseId`、`assessmentId`、`learnerId` 和 `attemptId` 可共同存在于批改深链以恢复页面，但服务端必须从 attempt 和领域事实完成授权，不能信任查询参数。批改写入继续使用既有 revision/`409` 冲突保护。
- 防泄露边界：原整场 results 接口保持“全部人工批改完成后才返回”的既有门禁，不能因单份批改读取而放宽，避免教师成绩册或结果列表在处理中逐份暴露成绩。T11C.2 恢复后，`panel=grading&attemptId=...` 使用新单份接口，整场结果面板仍使用原 results 接口；发生保存冲突时保留输入并以同一 attempt 重新读取最新 revision。
- 实施与测试范围：后续实现只允许修改 OpenAPI 0.21.1、Learning 读取 Controller/既有服务接线、Gateway 路由或错误适配（如确有需要）、生成客户端，以及 owner、非 owner、旧 attempt、资源不匹配、整场未关闭、结算未完成、待批可读和 revision/409 回归测试。不得新增数据库迁移、第二套 DTO、Redis、RabbitMQ、OSS、批改页面或其他功能。
- 克制性判断：具体失败场景是“缺少单份教师读取端点，使受支持的待批状态只能命中必然 409 的整场结果端点”。Git 历史、OpenAPI 版本号、attempt 主键、事务、唯一约束、Java/TypeScript 类型和普通前端测试都不能让一个不存在的 HTTP 读取入口可用，因此本次向前增加最小契约是必要的；不新增 hash、平行 contract、baseline 或 gate。
- T11C.2 恢复门禁：T11C.1.3 必须先独立实施、真实验收并合并；随后把最新 `main` 以普通 merge 合入 PR #50，只消费重新生成的客户端，完成第一页之外待批 attempt 深链、owner/404、409 输入保留与其余未完成真实验收后再决定是否合并，不扩大页面范围。
- 数据与外部资源：本次总控只修改 `page-specs`、`backlog` 和 `verification` 三份规划/审计文档并做本地提交，不修改 OpenAPI、Java、Vue、生成客户端、Flyway 或运行配置，不连接或修改 MySQL、Redis、RabbitMQ、OSS，也不创建、修改或清理数据库、表、Seed、伪数据、消息、对象或缓存 Key；提交不推送。

## 2026-09-07 T11C.1.3 教师待批答卷读取契约实施验证

- 分支与门禁：当前分支为 `codex/t11c1.3-teacher-attempt-read`，保留规划提交 `6ee94cd`，实现提交为 `bd0b2a1`。PR #50 仍保持 Open，未切换、修改、合并或关闭，未修改其 Vue 业务代码。
- 契约与实现：`docs/api/openapi.yaml` 从 0.21.0 演进至 0.21.1，新增 `GET /api/v1/teacher/attempts/{attemptId}`，响应复用 `AttemptResult`。Learning Controller 只增加最小入口并调用已有 `LearningAssessmentService.teacherResult`；没有重复组装结果、第二套 DTO 或 Gateway 冗余路由。生成客户端已由 `pnpm.cmd --dir web-apps run generate:api` 更新。
- 授权与门禁：服务端从 attempt 实际关联的 assessment 解析 owner、课程和测评事实，并检查最后有效 attempt；不信任 URL 上的课程/测评/学习者上下文。关闭且结算完成后，单份接口允许 `PARTIALLY_GRADED` 待批答卷读取；非 owner、旧 attempt 和真实资源关系不匹配安全 404；未关闭/未结算仍按 `LEARNING.RESULTS_NOT_READY` 409。原整场 results 的全部人工批改门禁保持不变，人工批改 revision/409 保护保持不变。
- Learning 自动化：定向 `LearningAssessmentIntegrationTest` 26/26 通过；learning-service 全量 89 项执行、0 失败、16 项既有条件测试按开关跳过。新增/扩展覆盖 owner 单份待批 200 语义、整场 409、最终 attempt、旧 attempt 404、非 owner 404、未关闭/未结算拒绝、已批改读取和 revision/409 回归。
- Gateway 与前端自动化：Gateway 未修改配置，但全仓 Gateway 测试 21 项执行、0 失败、1 项真实环境测试跳过；`check:api` 通过且生成文件与源码一致；`typecheck` 通过；Vitest 36 文件、237 项通过；admin/student/teacher 三端 `build` 通过，仅有既有 HLS 大 chunk 警告。
- Maven 验证：标准 `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 已尝试，但当前运行中的 Gateway/Learning/Course/IAM 本地 JAR 持有 Windows 文件锁，clean 阶段无法删除 Gateway JAR；不终止用户进程。随后执行 `mvn --% -Dmaven.repo.local=.m2/repository -Dspring-boot.repackage.skip=true -f java-platform/pom.xml verify` 成功，8 个 Reactor 模块全部成功，Learning 89/0/16 skipped，Gateway 21/0/1 skipped，其他模块无失败。
- 数据边界：没有新增或修改数据库迁移、表、字段、Seed、开发/测试持久数据或外部对象；测试仅使用现有临时 H2 Fixture，进程结束后释放。没有连接 MySQL、Redis、RabbitMQ、OSS，也没有执行真实多服务 HTTP 数据验收，因此不存在需要清理的持久数据。
- 可读性：Controller 新入口补充中文注释，测试场景补充业务目的注释；生成代码未手工编辑。交付文档已记录接口边界、失败语义、测试结果、数据清理和后续依赖。
- 当前交付状态：已推送 `codex/t11c1.3-teacher-attempt-read` 并创建 [Ready PR #52](https://github.com/chengchen-ccnu/ccnu-edulion/pull/52)。PR #52 的 Java、Frontend、Python、OpenAPI generated source 检查均已通过，PR 保持 Open/CLEAN、未合并；PR #50 仍保持 Open/CLEAN。CI 已在干净工作区确认标准 Maven reactor 检查通过，本地标准 `clean verify` 仍受运行中 JAR 文件锁限制。

## 2026-09-07 PR #52 合并与 T11C.2 继续验收前置审计

- 合并与清理：[PR #52](https://github.com/chengchen-ccnu/ccnu-edulion/pull/52) 在 Ready、可合并且 Java、Frontend、Python、OpenAPI generated source 检查全部成功后，以普通 merge 合并为 `main@0639dcb0482600587bbf990c5ed54aeb99ebe73b`；本地 `main` 已快进同步，`codex/t11c1.3-teacher-attempt-read` 本地和远端分支均已清理。
- 分支合流：切回 `codex/t11c2-homework-gradebook-web`，先确认与远端 PR #50 头一致，再以普通 merge 合入最新 `main`。OpenAPI 0.21.1、Learning 单份教师 attempt 读取入口和重新生成客户端已进入该分支；冲突只发生在 `page-specs`、`backlog`、`verification`，按“保留 T11C.2 真实验收记录＋保留 T11C.1.3 契约/实施记录＋更新当前状态”解决，没有改写业务代码。
- 前置结论：T11C.1～T11C.1.3 均已合并，缺失教师单份待批答卷读取契约的阻断已解除。T11C.2 可以在现有 PR #50 内只消费生成的 `getTeacherAttemptResult`，然后继续待批 attempt 深链、第一页之外定位、owner/404、revision/409 和真实下载/离页资源清理验收；原整场 results 门禁保持不变，不得扩大页面或后端功能范围。
- 数据与外部资源：本轮只执行 GitHub 合并、Git 同步/分支清理、普通分支合流、文档冲突解决和静态前置审计；没有连接或修改 MySQL、Redis、RabbitMQ、OSS，没有创建、修改或清理数据库、表、Seed、伪数据、消息、对象或缓存 Key。

## 2026-09-07 T11C.2 T11C.1.3 合流后的最终真实验收

- 分支与范围：继续 `codex/t11c2-homework-gradebook-web` 和现有 [PR #50](https://github.com/chengchen-ccnu/ccnu-edulion/pull/50)，没有创建分支或合并 PR；本轮开始先复核 `git status`，仅保留已授权的单份读取改动。本轮只在既有教师批改面板接入生成客户端 `getTeacherAttemptResult`，并修正 T11C.2 local/test Fixture 让作业复制客观题、考试复制主观题，未修改 OpenAPI、Learning 生产逻辑、迁移或页面职责。
- 新进程：停止旧 Learning 后以包装脚本构建/启动最新 jar；Learning PID `101672`、Gateway PID `102800` 均于 2026-09-07 15:29:26 启动，分别监听 8084/8080，Gateway 继续设置 `GATEWAY_REDIS_DATABASE=IAM_REDIS_DATABASE`；teacher-web 最新代码重启后 Vite listener PID `100956` 监听 5174。没有沿用 9 月 5 日旧进程。`/api/v1/learning/courses/00000000-0000-4000-8000-000000000201/tasks` 真实 200，教师成绩册真实 200，确认 Gateway→Learning→Course 课程任务 503 已恢复。
- 教师单份读取 HTTP：HOMEWORK results `page=2&size=1` 返回 `total=2` 和最终 attempt `dda9b00a-bbe7-313a-a08b-74a70a3228ec`；同一 attempt 直接 `GET /api/v1/teacher/attempts/{attemptId}` 返回 200、`finalAttempt=true`。`teacher2@example.invalid`、已清理旧 attempt `78d26e28-b7aa-4558-a68f-f33421feea4d` 和未知资源均返回安全 404。教师页面源代码和 API 适配器测试确认批改深链不再调用整场 results 分页查找，整场 results 逻辑仍保留。
- 待批与人工批改 HTTP/浏览器：在 `edu_platform_learning_dev` 用固定 Fixture 建立主观考试后，学生真实开始、保存、提交，教师关闭整场；临时 attempt `77597015-4682-4c59-8419-fb7e2e31d62d` 单份读取为 200/PARTIALLY_GRADED，而整场 results 仍为 409 `LEARNING.RESULTS_NOT_READY`。浏览器深链真实显示主观答案和“人工批改”面板。旧 revision 批改返回 409 `LEARNING.GRADING_REVISION_CONFLICT`；页面保留 1.5 分和“保留冲突输入”，载入最新 revision 后输入仍保留；当前 revision 批改 200，重新读取为 GRADED/最新 revision。
- CSV 与离页：最新 teacher-web 真实会话并发触发 CSV 与“取消下载”，页面稳定显示“CSV 下载已取消”。随后在 CSV 请求进行中立即离开到 `/teacher/courses`，目标路由成功、无残留下载提示，浏览器 `error/warn` 为空；前端组件测试另真实断言 AbortSignal、迟到响应不创建 Blob、卸载中止和 `URL.revokeObjectURL`。
- 响应式与错误：沿用本轮真实浏览器已确认的 `window.innerWidth=390/768/1440` 和教师页 `clientWidth/scrollWidth=375/375、753/753、1425/1425`，没有用固定 473px 替代；401/403/404/409/422/429/503/offline 的现有真实探针与页面保留事实记录继续有效。
- 自动化：`pnpm.cmd --dir web-apps run check:api`、`typecheck`、`test`（40 文件/269 项）和 `build`（三端，只有既有大 chunk 警告）均通过；T11C2BrowserFixtureTest `INITIALIZE`、真实流程、`CLEANUP`、`REPORT` 均通过。修改后的 Fixture 测试注释说明输入、输出和精确清理边界，生成代码未手改。
- 数据清理：Fixture 只使用明确的 `edu_platform_learning_dev`，固定临时 assessment `0c1f5676-7ea0-3ae0-895b-2b64b6cff452`、`4aa3780e-b04a-3e8d-8936-ec353c6ce0c6`，并复制既有 T11C.1 客观/主观题快照；`CLEANUP` 后 `REPORT` 确认临时 assessment、attempt、错题和 occurrence 均为 0，T11C.1 演示 assessment 仍为 2。此前本轮产生的 5 条导出审计 UUID `9019757f-9094-4e42-9cb9-8780634be179`、`456e926d-3b65-446d-ae47-29dceb727f22`、`2ad0d068-4c1d-4684-8492-8090ce9ab4ec`、`491bf992-4b27-4657-b11f-3cc4ca0020ba`、`e8d3e3f7-e9d4-4b20-8e1c-618c9f168c2d` 已按固定 operator/course 条件逐条清理，最终报告为空；没有删除账号、报名、T11C.1 演示或其他领域数据。
- 结论：T11C.1.3 单份读取契约已在 PR #50 的现有页面真实消费，待批/分页外 attempt/owner 404/整场 409/revision 409 均通过；真实 CSV 取消、离页和组件资源清理也已取得证据。提交 `4297238` 及后续验收文档更新均已推送，PR #50 的 push/PR 两组 Java、Frontend、Python、OpenAPI generated source 共 8/8 检查全部通过，PR 保持 Open、未合并。

## 2026-09-07 PR #50 合并与 T13 前置审计

- 合并与清理：[PR #50](https://github.com/chengchen-ccnu/ccnu-edulion/pull/50) 在 Ready、可合并且 Java、Frontend、Python、OpenAPI generated source 共 8 项检查全部成功后，以普通 merge 合并为 `main@685afe8b19b69623006c389d3f0894b2a0c122a0`；本地 `main` 已快进同步，`codex/t11c2-homework-gradebook-web` 本地和远端分支均已清理。
- T11C.2 结论：课程内 HOMEWORK/EXAM、最后有效 attempt、教师成绩册、待批与分页外批改深链、owner/404、整场 results 409、防并发覆盖 revision/409、CSV 生成/取消/离页清理和三档响应式均已有真实验收与自动化证据，T11C.1～T11C.2 主链现已完整进入 `main`。
- 优先级比较：T13 的唯一显式依赖 T03 已验收，完成后直接解锁 T14 Java Agent Access，并继续解锁 T15A 独立答疑和 T15B 课程上下文答疑，是当前剩余任务中更长且更核心的关键路径。T12C 的 T11 查询投影、T12B 及此前后移原因 T12D.1/T12E 均已满足，但它只直接阻塞 T17B，继续排在 Agent 主链之后；T12H 仍需与尚未实施的 T16 协调跨领域数据保留、匿名化和审计边界；T16 范围较广，后续再独立冻结互动、运营和审核契约。
- T13 现状与门禁：现有 `edu_agent_app` 已有请求入口、BM25＋FAISS 混合检索、重排与阈值、非数学问题二次判断、三步教学降级、人工介入以及单步骤/单会话调用上限，可作为基线复用；但当前请求字段、SSE 事件、断连/取消、超时、统一错误和恢复语义尚未作为稳定平台契约完成审计。T13 只负责 Python Agent 接口与行为契约，不实现 T14 的 Java 鉴权、限流、审计和 SSE 代理，也不实现 T15A/T15B 页面。
- 准备结论：已从干净且与 `origin/main` 一致的 `main@685afe8b19b69623006c389d3f0894b2a0c122a0` 创建 `codex/t13-agent-interface-stabilization`。后续实施前应先审计并冻结最小请求/SSE/错误与终止语义，再修改 Python 实现和测试；不得创建第二套 Agent、重复 Java 领域鉴权或前端 DTO。
- 数据与外部资源：本轮只执行 GitHub 合并、Git 同步/分支清理、路线图状态更新和只读前置审计；没有连接或修改 MySQL、Redis、RabbitMQ、OSS、向量索引或模型服务，没有创建、修改或清理数据库、表、Seed、伪数据、消息、对象、缓存 Key 或检索索引。

## 2026-09-08 T10C.1 公共题库与课程练习入口契约纠偏

- 交付状态：实现提交 `2ff038e`、文档提交 `49f587c` 已通过 PR #54 合并，合并提交 `b132ff0`。后续 T10C.2 从该最新 main 创建独立工作区。
- 前置与历史：在干净的 `codex/t10c1-question-bank-entry-contract` 开工；fetch 后 HEAD、本地 `main`、`origin/main` 均为规划 PR #53 的合并提交 `921ab78`。完整复核 AGENTS、page-specs、backlog、verification、Agent 架构和 T13 规划，并审计 T10B/T12B/T11C.2 真实代码与历史。`PracticeCenterPage.vue` 从首次交付起就是测评列表，后续只加 PRACTICE 客户端过滤，没有公共题页面被删除的证据；学生缺入口不能解释为题库无数据。
- 契约与实现：OpenAPI 从 0.21.1 演进到 0.22.0。公共题列表/详情继续只返回 ACTIVE SYSTEM 根题和有序子题，要求 `learning:access`；新增当前题目安全正文媒体授权，答案/解析专用绑定在 Learning 裁剪，Media 只接受 Learning 一次性 `SYSTEM_QUESTION_CONTENT` 令牌，通用授权保持 uploader-only。课程任务新增可选 `type`，省略时仍只查 HOMEWORK/EXAM，`type=PRACTICE` 在 SQL 分页前按指定 courseId、PUBLISHED 和匹配 COURSE assignment 查询；服务再复核当前发布 Course owner 与 ACTIVE 报名。新增旧 assessment 链接安全 entry 投影，只返回复用的任务摘要，不返回题目/答案；关闭练习不被重新开放。
- 数据模型结论：现有 `assessment.course_id` 非空、创建必填 courseId、发布建立 COURSE assignment，足以表达课程练习，无需 DTO、表或迁移。旧 PRACTICE/后报名可能没有 participant；所有 GET 均保持只读，只有已经通过课程归属、报名、发布和开放校验的开始事务才为本人补/恢复名单并复用原单次 attempt。公共 SYSTEM 题只有只读浏览；除错题有限重做外，没有公共单题提交、自动评分或无课程自主组题业务契约，本任务没有另建测评模型或判分流程。
- 安全与回归：自动化覆盖 ACTIVE SYSTEM/教师私题隔离、父题/组合子题图片、答案专用图片拒绝、非 owner 通用 SYSTEM grant 404、一次性内部令牌；课程 PRACTICE 覆盖精确课程、当前 Course owner、报名、发布、assignment、未开放、关闭、跨课/错课时、旧 participant/final pointer、GET 无写入、开始/提交/结果复用；HTTP 覆盖 `type=PRACTICE`、默认 HOMEWORK/EXAM、分页上界、非法枚举/页码统一 422 和无学习能力 403。HOMEWORK/EXAM 的参与人、最后 attempt、成绩/答案公布和整场 results 门禁未放宽。
- 自动化结果：标准 Java `clean verify` 八个 Reactor 模块全部成功，合计 211 项、0 失败/错误、30 项显式真实环境测试跳过；其中 Gateway 22 项、Learning 97 项、Media 31 项。定向 Learning 50 项、Gateway 14 项、Media 14 项均通过。前端只重新生成客户端，没有 Vue 改动；`typecheck` 通过，Vitest 40 文件/269 项通过，三端生产 build 通过，仅保留既有 student/teacher 大 chunk 警告。Prism 成功解析 OpenAPI 并注册全部路由；无认证探针按全局 Bearer 约束返回 401。`git diff --check` 通过。
- 只读开发库审计：通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入本机配置，测试先严格确认 `edu_platform_learning_dev` 与 `edu_platform_media_dev`，连接设为 read-only、事务最终 rollback，未启动 Spring/Flyway/Worker。Learning 有 1,010 个 ACTIVE SYSTEM 聚合，其中 648 个根题、因此 362 个组合子题，题目版本含 1,910 条 QUESTION_IMAGE 引用；最小根题样本 `0016e621-56f6-4fdc-bab0-63b0665bfc4f` 为 SINGLE_CHOICE、0 子题、1 图片。Media 有 1,910 个 SYSTEM/QUESTION_IMAGE/READY 资产和 1,910 条 QUESTION_VERSION_MEDIA/BOUND 引用。开发库另有已发布 HOMEWORK/EXAM 各 1 场，PRACTICE 为 0；这表示当前库没有播种课程练习，不是公共题库无数据。
- 数据与可读性：没有新建或修改 Schema、表、迁移、Seed、持久伪数据、Redis Key、RabbitMQ 消息或 OSS 对象，也没有需要清理的临时数据；H2 Fixture 随测试进程释放。新增/修改的业务入口、权限裁决、兼容写入、媒体投影、只读审计和复杂测试均补充中文 Javadoc/业务注释，生成文件未手工加注释。
- T10C.2 门禁：公共题列表/详情/媒体、课程 PRACTICE 清单/详情/开始/最后结果和旧深链归属已有生成类型与后端安全证据，T10C.2 可在本 PR 合并后开工。前端必须用 `system-questions` 构建顶部题库，用 `/learning/courses/{courseId}/tasks?type=PRACTICE` 构建具体课程练习；不得继续全课程枚举后客户端筛选。由于公共单题作答与无课程自主组题仍无契约，T10C.2 只能先交付浏览入口，不能显示伪作答/评分按钮。


## 2026-09-08 T10C.2 顶部题库与课程练习入口前端

- 基线/状态：T10C.1 PR #54 已合并，最新 origin/main 为 `b132ff0`；独立 `codex/t10c2-question-bank-course-practice-ui` 完成实现、自动化、隔离浏览器及真实 Gateway/数据库/OSS 验收，实现提交 `0b0f06a` 已推送至 PR #55。固定 5173 Origin 的 OSS 浏览器题图和真实 NOT_OPEN 探针均通过，PR 满足 Ready 条件，不自动合并。
- 入口契约：第四槽“题库”仅消费公共 SYSTEM 列表/安全详情与根题媒体授权；按 URL 恢复学段/年级/题型/难度/页码。具体课程按钮进入 `/learn/:courseId/tasks?type=PRACTICE`，服务端过滤；默认作业/考试保持原路由。旧 `/practice/:assessmentId` 先取 entry 安全归属再跳课程，不采信查询中的 courseId。
- 状态/安全：首次失败不报空；切课/任务类型清除旧摘要；同范围失败保留已加载内容；未开放任务不可开始。组合子题/图片/附件复用授权与安全渲染，晚到响应不更新离开页，释放 Blob URL。不新增 DTO、迁移或评分流程，公共单题提交/自主组题仍无契约并隐藏操作。
- 实际检查：全量 typecheck 通过；Vitest 43 文件/294 项通过（含 17 项组件行为）；三端 build 通过，既有 chunk 警告保留；生成客户端无差异、diff --check 通过。隔离 Chrome 固定响应在 1440×1000、820×1100、390×844 验证分页刷新、键盘、组合图、课程未开放、旧链接及匿名深链，JS 错误与页面横向溢出均为 0。
- 证据边界：真实联调已覆盖公共图片授权、浏览器 Blob 题图、课程练习开始/提交/结果、NOT_OPEN 安全详情/开始拒绝及 HOMEWORK/EXAM 公布回归；最终结论不依赖固定响应或组件自动化替代真实 HTTP。
- 数据/注释：开发库原有 PRACTICE=0，本轮用固定、幂等、可精确清理 Fixture 临时创建一场课程练习；提交后 CLEANUP/REPORT 为 0，并精确删除本轮 Media 授权审计与 nonce。没有创建 Schema/表/迁移，没有重导或清库。新页面/API/权限投影/取消、媒体清理和 Fixture 均有中文职责注释，生成代码未手工修改。详见 [独立交付文档](../delivery/T10C.2-question-bank-course-practice-ui.md)。

### 真实 Gateway、数据库与 OSS 最终验收补充

- 最新源码 clean package 后启动隔离 Gateway/IAM/Course/Media/Learning/student-web，学生真实登录。公共列表为 648 个 ACTIVE SYSTEM 根题；分页、COMPOSITE/SINGLE_CHOICE 筛选、普通/2 子题组合安全详情、非法页 422、刷新/返回/offline partial-error、三档无溢出和键盘焦点均通过，详情无 answers/analysis。
- 含图 ACTIVE SYSTEM 样本的领域授权为 201，OSS 直接 GET 为 200/11,040 字节 PNG；3 秒短链在等待 15 秒后为 403。非绑定 mediaId、不可见题和学习者通用媒体授权分别安全返回 404 `QUESTION.NOT_FOUND`/`MEDIA.NOT_FOUND`，均取得 traceId。
- CORS 纠偏复验：首次把 student-web 临时启动在 5188，偏离仓库固定 5173 Origin 和已有 OSS CORS，所得 `net::ERR_FAILED` 不能记为产品阻断。保留已有 5173 Vite，通过验收浏览器上下文加载本分支资产并保持页面 Origin 为 `http://localhost:5173` 后，真实 OSS 题图成功显示，DOM 使用 `blob:`；站内离开详情后 `createObjectURL=1`、`revokeObjectURL=1`，无 OSS CORS 错误。无需新增 5188 CORS。
- 开发库 PRACTICE 原为 0；安全 Fixture 在 `edu_platform_learning_dev` 固定创建一场一题、一参与人的课程练习。指定课程只返回该 PRACTICE，常规入口只返回既有 HOMEWORK/EXAM 各一场；真实开始、草稿保存、提交、结果及旧链接归属跳转通过。结果为 SUBMITTED、gradeVisible=false、answerVisible=true。未认证 401，跨课程 404；既有 HOMEWORK/EXAM 结果仍 gradeVisible=false、answerVisible=false，无 score/答案/解析泄漏。
- NOT_OPEN：既有 HOMEWORK/EXAM 均为 CLOSED，因此 Fixture 另建一场未来一天开放的固定 HOMEWORK 探针，不修改原任务。默认课程列表返回 `NOT_OPEN`；详情 200 且 questions 为空、description 为空；开始返回 409 `LEARNING.ASSESSMENT_NOT_OPEN`，traceId `96e193057b204036a7f87549aae8916e`。PRACTICE 筛选仍只返回本课练习。
- 清理：Fixture CLEANUP/REPORT 确认 PRACTICE、NOT_OPEN HOMEWORK、attempt、answer、wrong occurrence 全为 0；首次验收按明确 ID 删除 Media 13 条 access grant audit 和 5 条 nonce，5173 纠偏复验删除 6 条 audit/6 条 nonce，NOT_OPEN/课程回归再删除 5 条 audit/5 条 nonce，三次残留均为 0。公共题库、课程、报名、原 HOMEWORK/EXAM、媒体资产/绑定及 OSS 对象均保留。隔离 18080～18084、5188 已释放，用户原有 5173/5174 进程未停止。
- 自动化复核：全量 Java `clean verify` 的 8 个 Reactor 模块全部成功、0 失败/错误；安全 Fixture 的独立 `Report` 入口再次通过并报告 assessment/attempt/answer 均为 0。前端 typecheck、43 文件/294 项测试和三端 build 继续通过。
- 结论：T10C.2 真实最终验收全部通过，PR #55 可标记 Ready；既有 5173/5174 OSS CORS 足够，无需新增 5188。完整状态码、traceId、样本和启动/清理说明见 [交付文档](../delivery/T10C.2-question-bank-course-practice-ui.md)。

## 2026-09-08 PR #55 合并与 T13.1 前置审计

- 合并与同步：PR #55 在真实最终验收完成、Ready 且两组 Java、Frontend、Python、OpenAPI 共 8 项检查全绿后，以普通 merge 合并为 `main@35f67674250fb971adc2f955c4e752415a9fea6c`。合并后的 main CI `34236380629` 也已成功；本地 main 已快进并与 origin/main 一致。
- 清理：已确认两个工作区均干净后，精确移除 `C:/Users/ChenCheng/.codex/worktrees/t10c2-ui`，并删除 T10C.1/T10C.2 本地和远端分支。没有删除项目文件、业务数据、公共题、课程、报名、媒体或 OSS 对象；历史提交继续由 main 与 PR 保留。
- T13.1 依赖：总控 Agent 规划 PR #53、T03、T10B、T11C.2、T10C.1 和 T10C.2 均已合并；`docs/architecture/agent-platform-design.md` 已进入 main，既有 Python Agent 和测试基线可供协议审计。T13.1 不依赖 Qdrant、Neo4j、重排模型或真实聊天模型运行，这些属于 T13.2/T13.3 的后续前置。
- 开工边界：T13.1 只创建并验证内部请求/响应、流事件、错误/终态、取消、`messageId`/`runId`、预算、可信上下文/私有状态、工具白名单和索引事件协议；不得实现动态 Agent、Java Agent Access、数据库迁移、Vue、Qdrant/Neo4j 索引或模型调用。当前已从干净且与远端一致的 main 创建 `codex/t13.1-agent-internal-contract`。
- 数据：本次总控仅执行 Git 同步、分支/worktree 清理和文档状态更新，没有连接或修改 MySQL、Redis、RabbitMQ、OSS、Qdrant、Neo4j，也没有创建表、Seed、伪数据、消息、缓存 Key、媒体或索引。

## 2026-09-09 T13.1 Agent 内部协议与工具契约

- 门禁：开始时工作区干净，分支为 `codex/t13.1-agent-internal-contract`，HEAD 为准备提交 `49590fd`；fetch 后本地 `main` 与 `origin/main` 均为 PR #55 合并提交 `35f6767`。Git 历史、GitHub PR #53 和现有路线图共同确认 T03、T10B、T11C.2、T10C.1、T10C.2 均已合并。完整阅读 Agent 设计、访谈记录、页面规格、backlog、verification 和 T13 规划后才开始写契约。
- 旧实现审计：只读取 `edu_agent_app`、`server.py`、两份现有 Python 测试及依赖声明，没有读取模型权重、FAISS 索引或题库 JSON。可复用点是 Pydantic 结构化输出、SSE 适配思路、检索清洗和失败也计数的调用上限；旧 `thread_id` 幂等、FAISS/PostgreSQL 检查点、80 层递归、30 次会话调用与人工转交不进入新协议。
- 内部 HTTP：新增 `docs/api/agent-internal.yaml` OpenAPI 3.1/v1，只定义 Java Agent Access → Python 的 run 流与幂等取消，不修改浏览器外部 `docs/api/openapi.yaml`。协议区分 conversationId/messageId/runId/clientRequestId/traceId，冻结同逻辑请求不重复建消息/执行、同会话单活动 run/409 不排队、可信上下文与 Python 私有状态隔离、短期媒体 URL 秘密处理，以及首事件/心跳/空闲/总时限/慢客户端终止语义。
- 流、意图和工具：SSE 使用 9 种可判别事件、连续 sequence 和唯一 `FINAL_RESULT`；终态为 COMPLETED/DEGRADED/LIMIT_REACHED/CANCELLED/FAILED。冻结 7 类一级意图、14 个二级意图、5 个角色、受约束槽位和最多两个有序子请求；10 个工具位置全部只读，调用需 toolCallId/超时并同时受 Java 工具白名单和资源范围限制。USER_HELP_SEARCH 在 T13.4 前只返回不可用，不宣称完整帮助能力。
- 预算：请求内的首版可配置默认值为 12 编排步骤、8 次 LLM（其中 1 次预留最终降级）、10 次工具尝试、2 次专家转交、同工具同参数 2 次、总时限约 90 秒；教学补救固定依次为重述、简单例子、回退前置知识。失败重试、视觉、总结和记忆提取均计入预算，到限额使用已有可靠信息并以 DEGRADED/LIMIT_REACHED 结束。
- 索引事件：新增 `agent-index-events.v1.yaml`，沿用统一事件信封和 at-least-once/Outbox/eventId 幂等语义，覆盖题目、知识点、课程文字和后续学生记忆 UPSERT/DELETE。消息只携带事实源、稳定 ID、sourceRevision、JUNIOR、可见范围和最小授权元数据；消费者拒绝旧 revision 覆盖新投影或复活较新 tombstone。真实 Outbox/RabbitMQ/Qdrant/Neo4j/Worker/重建留给 T14.1/T13.2/T14.2。
- 示例与校验：HTTP/工具/意图/SSE 和索引事件正反例均为仓库 YAML，并由 `tests/test_agent_internal_contract.py` 使用正式 Schema 校验；跨事件测试另检查全部本地引用、事件/终态完整集合、唯一终态、连续序号、取消后终止、最多两个子请求、白名单资源不能提升、eventId 重复 no-op 与旧 revision 拒绝。新增专项 16/16、完整 Python 24/24 和字节码编译已通过；wheel、diff 检查和 GitHub CI 结果以 T13.1 交付文档最终记录为准。
- 数据与边界：没有安装或连接 Qdrant、Neo4j、模型、MySQL、Redis、RabbitMQ、OSS；没有创建/修改数据库、表、迁移、Seed、伪数据、缓存 Key、消息、媒体、向量 collection 或图节点。没有实现 LangGraph 动态编排、LLM 调用、Index Worker、Java Agent Access、Vue 页面或外部前端 DTO。
- PR 与 CI：实现提交 `bc018867a093a662376c35aba3a63d9f27eea1f6` 已按规则执行 `git push origin HEAD`，Ready [PR #56](https://github.com/chengchen-ccnu/ccnu-edulion/pull/56) 已创建。首轮 Java、Frontend、外部 OpenAPI 检查成功；Python 因既有 job 不安装依赖而缺少 `yaml` 失败。CI 适配提交 `e387cef` 新增独立 `requirements-contract.txt` 和契约测试依赖安装步骤，并在隔离环境以 `jsonschema 4.25.1`、`PyYAML 6.0.3` 验证专项 16/16、全量 24/24 通过；该提交触发的 push/PR 两组共 8 项 Java、Python、Frontend、外部 OpenAPI 检查全部成功，不自行合并。

## 2026-09-09 PR #56 合并与 T14.1 Agent Access 会话授权基础

- 基线：T13.1 PR #56 在两组 Java、Python、Frontend、OpenAPI 共 8 项检查全绿后以普通 merge 合并为 `main@a4b2aebc5e696b32eefca9c496b6f786a2cc1593`；本地 main 快进后从该提交创建 `codex/t14.1-agent-access-foundation`，开工前工作区干净。
- 契约：外部 OpenAPI 升级至 0.23.0，增加本人会话/消息/run SSE/恢复/取消；内部 `agent-internal.yaml` v1.0.1 兼容修正普通对话可不带 grade；新增 `agent-index-source-internal.yaml` 1.0.0，冻结三类稳定分页安全投影和服务令牌边界。生成客户端由同一外部 OpenAPI 重新生成。
- Java Agent Access：新增 8086 独立模块与 Flyway V1，保存会话、消息、run、公开事件、取消和安全工具审计；owner、`learning:access`、请求指纹、单活动 run、领域上下文先授权、短期媒体 URL 不落库、逐事件校验/持久化/SSE 代理、恢复不重跑、取消幂等、断线取消和启动孤儿 FAILED 收口均由测试覆盖。
- 事实源：Learning 仅输出 ACTIVE SYSTEM 初中数学题安全投影与启用知识点/直接前置，Course 仅输出当前已发布非空 ARTICLE；内部接口要求固定调用方和专用令牌，Gateway 不路由。题目、知识点、课程正文变更在原领域事务写最小 Outbox。独立 `question_index_revision` 解决根题 UPSERT 后转组合子题 DELETE 必须拥有更高 revision、又不能改变业务 revision 的具体顺序问题。
- 数据与环境：本轮没有连接或创建真实 MySQL Schema，没有修改开发库、Redis、RabbitMQ、OSS、Qdrant 或 Neo4j，也没有持久 Seed/伪数据。H2 Fixture 随测试进程释放。初始化脚本新增 `AGENT_MYSQL_DATABASE` 和 DPAPI 加密的 Agent Index 服务令牌，包装器仅对子命令注入；本轮不要求新增 Python 包。
- 验证：Java 全量 `clean verify` 的 9 个 Reactor 模块全部成功，共 222 项、0 失败、0 错误、31 项显式真实环境测试跳过；Agent Access 5 项全部通过。Python `compileall`、全量 25/25（契约专项 17 项）和 wheel 构建通过。前端生成、typecheck、43 文件/294 项测试及三端 build 通过，只有既有大 chunk 警告。PowerShell 两个环境脚本语法解析、4 份 Agent YAML 解析和 diff 检查通过。未运行的真实外部依赖验收不记为通过，CI 结果在 PR 创建后继续记录。
- 提交与 PR：实现提交 `a5ac059` 与交付状态提交 `e510bd7` 均已按规则执行 `git push origin HEAD`；Ready [PR #57](https://github.com/chengchen-ccnu/ccnu-edulion/pull/57) 已创建、未合并。`e510bd7` 的 push/PR 两轮 Java、Python、Frontend、OpenAPI 共 8 项检查全部成功；只有 GitHub Actions 自身 Node 20 action 运行时弃用提示，不影响项目构建结论。

## 2026-09-09 PR #57 合并与 T13.2 前置审计

- 合并与清理：[PR #57](https://github.com/chengchen-ccnu/ccnu-edulion/pull/57) 在 Ready、可合并且 Java、Python、Frontend、OpenAPI 两轮共 8 项检查全部成功后，以普通 merge 合并为 `main@9afc1ae7ba5569805690ff4fad6e6a8feaacc275`；本地 `main` 已快进同步，T14.1 本地和远端分支均已删除。
- Qdrant：Docker 容器实际返回 1.19.1，HTTP 6333、gRPC 6334 均监听，`/collections` 为 `status=ok` 且 0 个 collection；容器使用 Docker volume `qdrant-storage`，没有创建、删除或修改 collection。仓库根目录原有未跟踪 `Qdrant/` 不是该挂载卷，已仅加入本仓库 `.git/info/exclude` 防误提交，文件保持原样。
- 本地模型：`bge-base-zh` 使用 `D:\Anaconda\envs\langgraph` 在 CPU 完成 2 条中文文本编码，形状为 2×768、数值有限、样本余弦为 0.804332。`bge-reranker-base` 使用 PyTorch 2.4.1+cu124/Transformers 4.57.5 离线加载为 XLM-R，返回 2 个有限分数且相关文本排在无关文本之前；一次性探测脚本执行后已删除，未写入 Git。
- Neo4j 与 RabbitMQ：Neo4j Community 5.26.19 Windows 服务、7474/7687 端口正常；由于共享环境尚无 Neo4j 用户/密码，认证查询明确失败，T13.2 真实验收前必须增加 DPAPI 或同等级加密加载并由开发者输入现有密码。RabbitMQ 4.3.4 服务和 5672/15672 端口正常，但 CLI 存在当前用户 Erlang cookie 不一致；T13.2 仍需通过应用连接验证发布确认、消费、有限重试和 DLQ，不能把端口监听当成消息链路通过。
- Worker 门禁：重新运行本地初始化后已生成 DPAPI 加密的 Agent Index Worker 服务令牌，包装器可注入且 `AGENT_INDEX_API_ENABLED=true`。当前系统默认 Python 不具备 Worker 依赖，现有 `langgraph` 环境也没有 `qdrant-client`；Qdrant client、Worker 配置、Neo4j 安全凭据加载和正式启动入口属于 T13.2 实现范围，不以临时全局安装替代依赖声明。
- 准备结论：T13.1、T14.1 与物理服务/模型资产前置已满足，可从 `main@9afc1ae` 开始 T13.2；Neo4j 认证和 RabbitMQ 应用链路是最终真实验收门禁，不是编写 Worker 的前置。已准备 `codex/t13.2-retrieval-index-worker`，只承接检索与独立索引 Worker，不实现 T13.3 动态 Agent、T14.2 记忆或前端。
- 数据边界：本轮没有连接或修改 MySQL、Redis、OSS，没有创建 Qdrant collection、Neo4j 节点/关系或 RabbitMQ 消息/队列，也没有创建 Seed 或伪数据；只做健康/只读列表和本地模型推理。Agent Index DPAPI 令牌是本机缺失安全配置，不进入仓库。

## 2026-09-09 T13.2 Qdrant/Neo4j 检索与独立索引 Worker

- 基线与规则：分支 `codex/t13.2-retrieval-index-worker` 确认包含 `main@9afc1ae` 和规划提交 `2f265bc`。开工时唯一修改为用户确认的 AGENTS 本地凭据规则，差异无其他内容，已先独立提交为 `f50bcba`，后续业务实现未混入该规则提交。
- 独立 Worker：`python -m edu_agent_app index-worker` 在导入在线 Agent/uvicorn 前分流，提供 init、consume、rebuild、reconcile 和 namespace 精确 cleanup。依赖锁定 `qdrant-client==1.19.0`、`pika==1.3.2`，项目/CI Python 最低版本为 3.10；复用本地 SentenceTransformers/Transformers/Neo4j，不下载 BGE-M3，不新增 Elasticsearch、Milvus、Kafka、PostgreSQL 或 FAISS 链路。
- 索引与检索：Qdrant 1.19.1 的四个固定 collection 均为 768 维 dense + `qdrant/bm25` sparse；BM25 使用 IDF modifier、`multilingual` tokenizer 并关闭语言 stemmer/stopwords。真实流水线按 dense 30 + BM25 30 → RRF(k=60) → 本地 reranker 25 → 集合/意图阈值 → 0～8 条可靠候选，空召回不填充。collection metadata 和 payload 同时记录 Schema/模型版本，版本漂移拒绝混写。
- 增量与重建：Rabbit v1 payload 严格白名单拒绝正文/答案/URL/秘密；Worker 使用固定内部调用方/专用令牌回读 Java 投影。SQLite 保存 eventId、实体最高 revision、tombstone 和分页检查点；重复/旧事件 no-op，DELETE 后旧 UPSERT 不能复活。主队列单活、手动 ACK，重试/DLQ 先 publisher confirm 再 ACK，失败不伪装成功。重建每页不超过 200，页批量编码、有限并发、整页检查点、恢复和只读对账，不读 Java MySQL/原始 44,441 JSON，也不自动删除差异。
- 权限与图：题目只写 ACTIVE SYSTEM JUNIOR MATH 安全投影，抽查 payload 无答案/解析；课程查询强制精确 course/version/lesson 和 bool 重授权标志，缺范围或旧版本不退化全库；记忆强制全局 userId 且 T14.2 前只使用协议替身。Neo4j 仅写受控 Worker 标签/有向前置关系，固定参数化 Cypher、深度 1～3、节点/超时有界，连接故障返回 degraded 且公共 Qdrant 检索继续。
- 真实重建与恢复：首次三路重建因开发机 Learning 小连接池瞬时 HTTP 500 中断；知识点 1,386 项和空课程源的检查点已完成，题目未越过失败页。以并发 1 重启后从检查点恢复并完成 648 道题，随后实现 Java 5xx/连接错误最多 3 次有限退避。最终 Qdrant 为题目 648、知识点 1,386、课程 0、记忆 0；Neo4j Worker namespace 为 1,386 节点/2,054 关系。原有 3,671 节点/2,107 关系全部保留，全库最终精确为 5,057/4,161。
- 真实检索：中文术语样本阈值前 25/后 8，dense/BM25/RRF/reranker 分别为 30/30/42/25；LaTeX 样本为 30/15/40/25，阈值后 8；相近但错误谓词最终错误候选为 0；非数学“今天午饭吃什么”阈值前 25、后 0。检索/重排典型耗时分别约 8～66 ms / 117～181 ms，总耗时约 183～505 ms，正文未写日志或本验证记录。
- Rabbit/临时清理：真实 Pika 链路完成 publisher confirm、手动 ACK、两次 TTL 重试、第三次 DLQ 及手动重投读取。唯一后缀的验收 exchange、主/DLQ/四 retry 队列全部精确删除；课程过滤验收的唯一临时 namespace 1 个点已删除并确认 0。生产 3 个 exchange 与主/DLQ/四 retry 队列幂等声明后保留，结束时 6 队列均 0 消息/0 消费者；没有删除其他 Rabbit 队列、Qdrant collection 或来源不明 Neo4j 数据。最终新进程因尚无 RabbitMQ DPAPI 文件而无法再次认证，未把该轮写成通过；再次启动前须交互初始化安全凭据。
- Java/数据库事实：真实内部 API 题目/知识点分页可读，Course 当前返回 0 个已发布非空 ARTICLE；T13.2 未跨域造课程/报名/退课数据，故不宣称真实课程用户状态闭环。启动已合并的 T14.1 服务时，`edu_platform_course_dev` 从 V4 前进 V5（outbox `exchange_name`、`course_text_index_revision`），`edu_platform_learning_dev` 从 V14 前进 V15（`question_index_revision`）；没有新增 T13.2 MySQL 迁移、Seed 或业务伪数据。
- 自动化：Java 全量 `clean verify` 9 模块、222 项通过，0 失败/错误、31 项显式真实环境测试跳过，总耗时 5 分 36 秒。Python compileall 通过；全量 47 项中 40 项通过、7 项真实测试按显式开关跳过；启动 Java 事实源并打开开关后，真实专项最终 7/7 通过、19.891 秒。Java 服务停止后的一次误触发因端口不可达失败，按前置条件重启后的最终轮次通过。wheel 与 3 个 PowerShell 脚本语法解析通过；diff 检查在提交前最终复核。GitHub CI/PR 尚未创建，不提前写成通过。完整命令、配置、清理和风险见 [T13.2 交付文档](../delivery/T13.2-retrieval-index-worker.md)。
- 提交与 PR：规则提交 `f50bcba` 保持独立，业务实现提交为 `3511e6c`，首次交付状态提交为 `2777115`，PR 记录提交为 `80f6f4d`；分支已按规则推送并创建 Ready [PR #58](https://github.com/chengchen-ccnu/ccnu-edulion/pull/58)，保持 Open、未合并。`80f6f4d` 的 push/pull_request 两组 Java、Python、Frontend、OpenAPI 共 8 项检查全部成功，0 失败/取消/跳过。

## 2026-09-09 PR #58 合并与 T13.3 前置审计

- 合并与清理：[PR #58](https://github.com/chengchen-ccnu/ccnu-edulion/pull/58) 在 Ready、可合并且 Java、Python、Frontend、OpenAPI 两轮共 8 项检查全部成功后，以普通 merge 合并为 `main@df9568034a88f7a6623fe9ff143e1b6641b4d5a9`；本地 `main` 已快进同步，T13.2 本地和远端分支均已删除。
- 协议门禁：T13.1 `agent-internal.yaml`、T14.1 Agent Access HTTP/SSE 调用与 T13.2 `HybridSearchService`/Index Worker 均已进入同一 main。使用本机 Python 环境执行 `tests.test_agent_internal_contract` 与 `tests.test_hybrid_index_retrieval` 共 20 项全部通过，覆盖唯一终态、取消/并发/预算、工具范围、索引事件和 dense/BM25/RRF/reranker 四阶段；没有修改协议或运行时代码。
- Python 依赖：`D:\Anaconda\envs\langgraph` 已可导入 qdrant-client、Pika、Neo4j、SentenceTransformers 和 Transformers，足以开始运行时与检索适配开发；T13.3 仍须通过项目依赖声明和 CI，而不能依赖这台机器的偶然全局状态。
- 模型配置：首次通过 `Invoke-WithLocalEnvironment.ps1` 检查时共享环境尚无模型配置；用户随后补齐阿里云百炼 API Key、OpenAI 兼容 Base URL、旧/新聊天与视觉模型名。只读取进程变量且不输出秘密：极短文本调用返回 200、响应非空、正常 stop、29 Token；内存生成的 16×16 红色 PNG 视觉调用返回 200、正确识别红色、正常 stop、91 Token，服务实际报告模型均为 `qwen3.8-flash`。这只证明凭据、地域、兼容端点和基础多模态可用，不能替代 T13.3 的流式、超时、取消、结构化输出和完整用量验收。
- 准备结论：T13.1、T13.2、T14.1 的代码和协议依赖已经满足，已从干净 `main@df95680` 准备 `codex/t13.3-dynamic-multi-agent-runtime`。该分支只承接动态五角色、多轮状态、意图/槽位、受控工具、检索接入、预算/降级、流/取消和多模态；不实现 T14.2 真实长期记忆事实、T13.4 帮助知识库、前端或自主组题。
- 数据边界：本轮只执行 Git 合并/同步/分支清理、配置存在性检查和 20 项纯自动化测试；没有创建、修改或清理 MySQL/Redis 数据、Qdrant 点、Neo4j 节点/关系、RabbitMQ 队列/消息、OSS 对象或伪数据。T13.2 已保留的本机派生索引保持原样。

## 2026-09-09 T13.3 动态多 Agent 与多轮运行时

- 协议与运行时：Python 内部入口严格解析 `agent-internal.yaml` v1，请求、五角色、一级/14 个二级意图、槽位、工具和 SSE/唯一终态不另建枚举。Coordinator 使用明确规则优先、结构化模型兜底和最多一次 JSON 修复；只顺序处理最多两个子请求。旧固定认知诊断/解题图、FAISS、人工转交、LangGraph 递归与 PostgreSQL checkpoint 已从运行时代码、测试和依赖移除。
- 有界执行：所有 run 共用 12 步、8 次 LLM、10 次工具、2 次转交、同工具同参数 2 次和约 90 秒的下发预算。离线回归覆盖每一种上限、第三子请求截断、角色/意图/grant/scope 越权拒绝、连续三次“不懂”的换表述/简单例子/前置知识顺序、Platform Help/T14.3 写入边界，以及同会话第二个并发 run 的 409。
- SSE 与故障：`RUN_ACCEPTED` 同步成为首事件，后续 sequence 连续且仅有一个 `FINAL_RESULT`。心跳、总/空闲超时、慢客户端、断线和取消均停止后续模型/工具；`COMPLETED`、`DEGRADED`、`LIMIT_REACHED`、`CANCELLED`、`FAILED` 全覆盖，超时/取消保留已成功文本并标为中断或简化。测试中发现并修复慢客户端生产协程异常未回收问题。
- 检索与安全：在线工具复用 T13.2 三集合 dense + multilingual BM25 + RRF + 本地 reranker，Neo4j 仅做有界前置知识。离线覆盖成功、空、异常、超时和降级；真实专项 3/3 通过（17.936 秒），覆盖 Qdrant Schema/安全 payload、四阶段与空命中、Neo4j 正常及关闭后降级。课程候选再次授权没有冻结的 Python→Java 端点，因此未自创 API；未注入 verifier 时课程命中 fail-closed 全部剔除，不回退全库。这是明确后续契约风险。
- 多轮与视觉：进程内最近消息结合 Java 已授权摘要；有限压缩保留公式、未解决问题、明确偏好和来源标题，清除 URL/签名/Token，不写工具原始结果、隐藏画像或受限正文，仅产出 T14.2 候选记忆。图片只消费 Java 授权 media/grant 和短期访问，复核 MIME/大小/过期/重定向，视觉不可靠时澄清；Python 不接 OSS SDK。
- 自动化结果：Python compileall 通过；默认全量 57 项中 48 项通过、9 项显式真实环境测试跳过、0 失败。百炼真实专项 2/2 通过：服务模型均为 `qwen3.8-flash`，文本 1 次、76/66 Token、1848ms；内存 PNG 视觉 1 次、171/98 Token、2288ms、可靠标记为真，未输出完整回答。Agent Access `clean verify` 的 3 模块成功，shared-kernel 1 项与 Agent Access 5 项通过；wheel `edu_math_agent-0.2.0-py3-none-any.whl` 构建成功，包内容审计确认不再携带旧运行时文件；`git diff --check` 通过。关键实现提交 `8875b88` 经 Ready [PR #59](https://github.com/chengchen-ccnu/ccnu-edulion/pull/59) 的 8 项 CI 全绿后合并，最终合并事实见下节。
- 数据边界：未创建或修改 MySQL/Redis 数据、Qdrant 点、Neo4j 节点/关系、RabbitMQ 消息/队列、OSS 对象或伪数据；真实索引专项只读 T13.2 已保留派生数据，Java 使用进程内 H2 并随 JVM 释放，视觉 PNG 只在内存存在。详细实现、命令、演示和风险见 [T13.3 交付文档](../delivery/T13.3-dynamic-multi-agent-runtime.md)。

## 2026-09-10 PR #59 合并与 T14.2 前置审计

- 合并与清理：[PR #59](https://github.com/chengchen-ccnu/ccnu-edulion/pull/59) 在 Ready、可合并且 Java、Python、Frontend、OpenAPI 两轮共 8 项检查全部成功后，以普通 merge 合并为 `main@05050fca1c4f95aad27c6960d105d361e1e05796`；本地 `main` 已快进同步，T13.3 本地和远端分支均已删除。
- 课程授权缺口：T13.3 的 `COURSE_TEXT_SEARCH` 已按 course/version/lesson 过滤，但最终候选只经过可注入 `course_verifier`；T14.1 没有冻结 Python→Java 批量复核端点。默认 verifier 缺失时全部剔除是正确 fail-closed 行为，但不能作为课程答疑完成态。
- 恢复与 Learning 事实缺口：Agent Access 能分页读取 MySQL 消息，却未把有界最近消息正文传入内部 run；Python 仅保留进程内最近轮次。内部协议预留四类学生事实工具，Java 尚未授予/实现读取适配，Python 未注入 reader 时返回空。
- 记忆与用量缺口：Python 已产生候选记忆及模型/视觉/工具/Token 统计，但公开 SSE 有意不承载 Java 私有数据，当前也没有幂等内部结果上报。Agent Access 仅有 T14.1 六张基础表，无摘要、记忆、证据、主题、偏好、用量、安全审计或记忆 Outbox。
- Redis 与真实流边界：Agent Access 当前未引入 Redis；同会话活动槽由 MySQL 唯一约束保护，进程内 map 只持有本实例上游句柄。基础 WebClient 流、事件先落库、取消和部分检查点已存在，但多实例租约、用户总并发/额度、Redis 故障恢复、真实背压和供应商取消尚未闭环。
- 拆分结论：旧 T14.2 无法独立验收，拆成 T14.2.1 授权上下文与恢复契约、T14.2.2 长期记忆事实/画像/索引、T14.2.3 Redis 运行治理与真实链路。已从干净 `main@05050fc` 准备 `codex/t14.2.1-agent-context-contract`；本分支只提交规划与审计，不实现业务代码。
- 页面与数据：拆分不改变现有路由或页面职责，因此未修改 `page-specs.md`。本轮没有连接或修改 MySQL、Redis、Qdrant、Neo4j、RabbitMQ、OSS，也没有创建伪数据或临时业务数据。

## 2026-09-10 T14.2.1 Agent 授权上下文与恢复契约闭环

- 协议：`agent-internal.yaml` 与 `agent-index-source-internal.yaml` 向前升至 1.1.0；旧 v1 请求不含 `recentMessages` 仍通过 Schema。新回调请求不含 userId，课程 `sourceRevision>=1`，用量、阶段耗时和候选记忆均有字段/数量/长度上限。
- owner 与课程：Python 只交 runId，Agent Access 从 `ai_run.owner_user_id` 反查 owner，再由 Course 精确校验 entity/course/version/lesson/revision、当前发布版本和 ACTIVE 报名或公开访问。退课、下架、旧版本、资源不匹配、依赖异常均剔除或 503，不回退全课程搜索。
- 恢复：Agent Access 从 `ai_message` 读取当前消息之前的有界窗口，默认 6 轮/16000 字符，按 sequenceNo 升序，保留 COMPLETE/PARTIAL；排除当前消息、上下文 JSON、临时 URL/Token/签名、工具原始结果和隐藏画像。Python 收到该窗口后不叠加进程内旧窗口。
- Learning 防泄漏：四个 USER_SELF 工具只读取当前 run owner。提交只认 `participant.final_attempt_id`，成绩还要求 `grade_publication_status=PUBLISHED`；错题不读取题目快照，所有响应不含官方答案/解析，资源 ID 只能缩小 owner 结果。
- 私有报告：Agent Access Flyway V2 在 `ai_run` 有界暂存完整结果；仅终态可写，同 reportId/正文精确相同幂等重放，不同内容 409。公开 SSE、消息和 INFO 日志均无模型/视觉/工具计数、Token、阶段耗时或候选记忆；长期规范化属于 T14.2.2。
- 自动化：PowerShell 初始化/包装脚本语法通过；Python compileall、wheel 与全量 59 个测试通过，9 个显式真实依赖专项跳过。Java clean 回归 shared-kernel 1、Course 34（跳过 3）、Learning 102（跳过 19）、Agent Access 10，均 0 failure/0 error；三个服务均在随机 localhost 端口通过真实 Spring/Tomcat HTTP 入口验收，另验证领域 HTTP 超时安全映射。两份内部 YAML 解析/Schema 正反例通过；公共 OpenAPI 客户端重生成无差异且 api-client typecheck 通过。
- 数据：只使用 JVM 进程内 H2 与 Python 内存替身；固定合成 UUID Fixture 按测试生命周期精确清理，进程退出后数据库释放。未连接或修改开发 MySQL、Redis、RabbitMQ、Qdrant、Neo4j、OSS 或收费模型。为隔离 Python 补装仓库已声明的 `pika==1.3.2` 后既有 RabbitMQ 单测恢复通过。
- 边界：没有实现长期记忆表、画像、衰减、Outbox、Qdrant 记忆写入、Redis 租约/额度、多实例治理、Vue、T13.4 或 T14.3。真实多服务+模型+索引长链路及上报跨进程可靠性仍由 T14.2.2/T14.2.3 承接。
- 交付：实现提交 `d58bee6` 已推送到 `codex/t14.2.1-agent-context-contract`；Ready PR #60 后续在 8 项 CI 全部成功后合并，最终事实见下节。

## 2026-09-10 PR #60 合并与 T14.2.2 前置审计

- 合并与清理：[PR #60](https://github.com/chengchen-ccnu/ccnu-edulion/pull/60) 在 Ready、可合并且 Java、Python、Frontend、OpenAPI 两轮共 8 项检查全部成功后，以普通 merge 合并为 `main@5810fae47bdb9b92bf650bca6ef469428a8a90e0`；本地 `main` 已快进同步，T14.2.1 本地和远端分支均已删除。
- 报告与迁移：Agent Access 当前迁移精确为 V1、V2；V2 只在 `ai_run` 增加唯一 `result_report_id`、有界私有 JSON 和时间，终态后同 ID/同正文幂等。T14.2.2 必须从 V3 向前迁移，并在单个 Agent Access 事务中把尚未规范化的报告落为用量、审计和候选记忆事实；不能改写 V1/V2，也不能把暂存 JSON 当长期查询模型。
- 事件与事实源：`StudentMemoryIndexProjectionChanged` v1 已冻结稳定 ID、全局 `subjectUserId`、revision、UPSERT/DELETE 和 USER_PRIVATE 边界，但仍标记为 `RESERVED_FOR_T14.2`。现有 `agent-index-source-internal.yaml` 没有记忆分页/单实体安全投影，Agent Access 也没有记忆 Outbox；T14.2.2 需向前补齐并复用既有 eventId/revision/tombstone 规则。
- Worker 与 Qdrant：Index Worker 已绑定 `agent.index.student-memory.changed.v1`，Qdrant 1.19.1 的 `student_memories` collection、768 维 dense/BM25 和 `user_id` payload 索引存在且当前 0 点；生产 `MemoryProjectionProvider` 仍明确抛出未接通错误，只有协议替身测试。T14.2.2 应实现 Agent Access HTTP Provider，不另建 collection、向量库或第二套消费者。
- RabbitMQ：本机 RabbitMQ Windows 服务为 Running 且 5672 可达；当前用户尚无 `rabbitmq.credential.clixml`，统一包装器不能为 Worker 注入应用用户名/密码。该项不阻塞 V3/Java/Python 开发，但在真实 Outbox→RabbitMQ→Worker→Qdrant 验收前必须运行 `scripts/Initialize-LocalRabbitMq.ps1`，不得把密码写入仓库或命令参数。
- 数据边界：MySQL 是摘要、记忆、证据、主题等级、偏好、逐 run 用量、安全审计与 Outbox 的事实源；Qdrant 只保存可重建的 ACTIVE 安全记忆投影。聊天候选只算弱证据，正式作答/批改为强证据；“懂了”不能直接形成掌握，旧批改被纠正时新 revision 必须撤销旧证据效力。明确偏好持续到用户修改，推断偏好可衰减；主题只保存少量有证据等级，不为全部知识点预建状态。
- 权限与保留：所有事实使用 IAM 全局 userId；来源受限记忆保留 course/version 等稳定范围，召回前仍需当前授权。索引不得包含完整聊天、答案、解析、成绩、隐藏画像、Token、URL 或提示词。普通经历与推断偏好按类型衰减，曾掌握证据只降低当前可信度而不按时间改成未掌握；原始消息 180 天清理与媒体引用释放仍由 T14.2.3 验收。
- 准备结论：代码依赖满足，已从干净 `main@5810fae` 准备 `codex/t14.2.2-memory-profile-index-sync`。T14.2.2 不实现 Redis 租约/额度、多实例 SSE、Vue、帮助知识库或自主组题；RabbitMQ 凭据是最终真实验收门禁而非开工阻断。
- 环境与数据：为适配 T14.2.1，本机初始化器新增了 DPAPI 加密的 Agent Access 内部令牌，并在未跟踪 `local.env` 补齐非敏感启用开关；没有读取或输出秘密。Docker Desktop/Qdrant 已恢复运行，只读确认 collection 为 0 点；没有连接 MySQL、写 RabbitMQ、修改 Qdrant/Neo4j/OSS 或创建伪数据。

## 2026-09-10 T14.2.2 长期记忆事实、画像与索引同步

- 事实与迁移：只新增 Agent Access Flyway V3，V1/V2 未修改。V3 建立全局投影 revision、会话摘要、长期记忆、证据、少量主题等级、明确/推断偏好、逐 run 用量、安全审计和 Agent Index Outbox；所有 owner 均为 IAM 全局 userId，未跨 Schema 建外键或查询表。
- 归一化与证据：首次终态报告保存后在外层事务内用 savepoint 归一化；解析/校验失败回滚全部派生写入，保留原报告和脱敏 FAILED 状态供有界重试。run/report 唯一约束及来源 revision 保证重复处理不重复计量、更新画像或产生同 revision 事件。聊天候选只写 WEAK；“我懂了”、单次“我不会”、未知类型、秘密/URL/工具原始结果均不能形成掌握。正式作答/批改只接受 Java 结构化 STRONG 证据入口，旧 revision no-op，新 revision 修正当前效力并保留历史掌握。
- 衰减与授权：推断偏好、未解决卡点和自述重复卡点按类型到期，明确偏好持续到同类修改；到期写 EXPIRED 与 DELETE tombstone。主题 90 天未验证只把可信度降为 LOW，不删除等级或历史掌握。在线注入只读 owner 本人事实；课程来源记忆必须与当次 Course 已授权的 course/version/lesson 完全相同，缺失或依赖失败 fail-closed。
- 契约与消息：`agent-index-source-internal.yaml` 向前升至 1.2.0，新增受固定调用方/专用令牌保护的记忆分页及单实体 USER_PRIVATE 投影；事件仍为 `StudentMemoryIndexProjectionChanged` v1。Agent Access Outbox 使用 mandatory、持久消息、publisher confirm/return、有限退避与失败保留；Worker 生产 Memory Provider 复用 eventId、revision、乱序/tombstone、retry/DLQ、重投、重建和现有 `student_memories`，未新增消息链或 collection。
- 自动化：标准 Java 全仓 `clean verify` 9 模块成功，239 项执行、206 通过、33 项显式真实依赖测试跳过、0 失败/错误；新增 publisher 定向回归后 Agent Access 19/19 通过。Python compileall 通过，全量 60 项中 51 通过、9 项真实依赖测试按开关跳过、0 失败。真实 MySQL 专项 2/2 通过；V1→V2→V3、重复启动、强证据修正及安全投影均验证。真实 Qdrant 记忆专项 1/1 通过并精确清理临时 namespace；真实 Agent Access HTTP 空投影分页返回 200。
- 真实长链：使用当前用户 DPAPI RabbitMQ 应用凭据及 `D:\Anaconda\envs\langgraph\python.exe` 完成。RabbitMQ 专项 1/1（43.106 秒）覆盖 confirm、非法消息三次有限重试、DLQ、重投和临时拓扑清理；真实 Agent Access HTTP 向 MySQL 写入两轮终态 run 报告，精确重放不重复计量，4 个 Outbox 均为 PUBLISHED。Worker 对 Qdrant 执行 3 次 UPSERT、1 次 DELETE，验证 owner 隔离、revision 更新和 tombstone 不复活；独立临时 namespace/SQLite checkpoint 从 Agent Access 事实源重建 2 个 ACTIVE 投影成功。
- 数据与清理：保留用途明确的 `edu_platform_agent_dev` 及 Flyway V1/V2/V3 表，没有账号、课程、题目或长期业务伪数据。长链创建的随机会话、run、记忆、证据、偏好、摘要、用量和 Outbox 已按稳定 ID 精确删除，相关 MySQL 逐表为 0；Qdrant 验收 owner 为 0，Worker SQLite 删除 4 个 eventId/3 个实体状态，RabbitMQ 主队列、DLQ 和 4 个 retry 队列共 6 个队列均为 0。未写 Redis、OSS 或 Neo4j 图数据；全局记忆 revision 仅保留允许的单调序列空洞。
- 边界与风险：没有 Redis 租约/额度、多实例 SSE、Vue、帮助知识库、自主组题、第二套消息链或新向量库。冻结契约尚无 Learning 主动强证据推送，本任务只提供结构化 Java 事务入口并拒绝从答案/成绩文本猜测等级；若后续自动接入，必须先冻结稳定主题、等级、公布权限和 revision 契约。完整命令、配置、演示与风险见 [T14.2.2 交付文档](../delivery/T14.2.2-memory-profile-index-sync.md)。
- 交付：前置审计 `9750fab`、实现提交 `3532733`、首次交付记录 `253d94f` 与真实长链记录 `c19955e` 均已推送；[PR #61](https://github.com/chengchen-ccnu/ccnu-edulion/pull/61) 的实现与真实验收记录均通过两轮 Java、Python、Frontend、OpenAPI 共 8 项 GitHub CI 检查，最终合并事实见下一节。

## 2026-09-10 PR #61 合并与 T14.2.3 前置审计

- 合并与清理：[PR #61](https://github.com/chengchen-ccnu/ccnu-edulion/pull/61) 在真实长链通过且最新两轮 Java、Python、Frontend、OpenAPI 共 8 项 CI 全部成功后，以普通 merge 合并为 `main@9b2ce10b4ceb55d4d2ce0e7bd7046e07e3185af9`；本地 `main` 已快进同步，T14.2.2 本地和远端分支均已删除。
- 现状缺口：Agent Access 尚未引入 Redis，活动执行仅存在当前 JVM 的 `ConcurrentHashMap`；MySQL 的 `uk_ai_run_active_conversation` 能阻止同会话重复 run，但不能提供用户跨会话总并发、分钟/日额度或执行实例归属。当前 `@PostConstruct` 会把查询到的全部活动 run 直接标记 FAILED，多实例启动时可能错误终止仍由其他实例运行的请求；幂等重放命中另一实例时也只能返回已保存快照，不能可靠跟随其后续事件。
- 已冻结实现边界：Redis 只保存有 TTL 的租约、取消标记、最近 run 安全元数据和原子额度计数，本地专用 DB 为 6；不保存消息正文、回答、授权上下文、Token 或唯一业务事实。MySQL 的会话、消息、run、事件、用量和唯一约束继续权威。新运行在 Redis 不可用时于写业务事实前返回 503；读取、已保存事件恢复和幂等取消继续读取 MySQL。持有中的实例失去租约后必须停止新增模型/工具调用并保存唯一中断终态，不能假装完成或启动第二次运行。
- 跨实例与额度：租约获取、续期和 token 匹配释放必须原子；同 clientRequestId 重放不重复占用额度。错误实例不得直接收口他人 run；只有租约确认过期后才能把孤儿 run 终止。跨实例恢复只重放/跟随已持久化事件，不重新调用 Python。用户级并发和分钟/日预算使用可配置默认值，真实验收用低阈值覆盖 429、TTL 回收与故障恢复，不把 Redis 计数替代 T14.2.2 的最终用量事实。
- SSE 与保留：继续沿用现有 OpenAPI 0.23.0 和内部协议 1.1.0 的事件、唯一终态、断线取消和部分检查点；若只补充错误语义应向前兼容，不创建第二套 DTO。原始消息默认 180 天的清理必须保留审计/聚合边界，并在同事务写已有媒体引用释放 Outbox；不得解析临时 URL、跨服务查表或直接操作 OSS。
- 环境：本机 Redis 5.0.14.1 服务 Running，`127.0.0.1:6379` 可达，DB 6 当前为 0 key；MySQL、RabbitMQ、Qdrant、Neo4j、BGE、reranker、百炼 Chat/Vision 和所需内部令牌均已通过安全包装器确认可用。审计发现 `local.env` 的非敏感 `VISION_MODEL_NAME` 曾粘连下一配置，已修正为 `qwen3.8-flash`，未读取或输出密钥。
- 准备结论：依赖满足，任务保持单个 T14.2.3；已从干净 `main@9b2ce10` 创建 `codex/t14.2.3-agent-runtime-governance`。本分支当前只提交合并状态、前置审计和交付计划，不实现 Java、Python、Flyway、Redis Key、API、事件或 Vue。
- 数据：前置审计只执行 Redis PING/DBSIZE 和环境配置存在性检查，没有写 Redis Key、MySQL 行、RabbitMQ 消息、Qdrant 点、Neo4j 节点、OSS 对象或伪数据。T14.2.2 真实验收数据已按上一节记录精确清理。

## 2026-09-10 T14.2.3 Redis 运行治理与真实 Agent 链路

- 事实与治理：Agent Access 增加 Redis 5 兼容短 Lua，原子完成幂等预留、单用户跨会话并发、分钟/日额度、run 租约绑定/续租/错 token 释放、取消和失败回退。Redis DB 6 只有 TTL 运行元数据，MySQL 的唯一约束、消息、run、事件、用量和记忆仍是事实源。新 run 在 Redis 不可用时于写消息/run 前 503，超额统一 429。
- 多实例：启动及默认每 15 秒的低频恢复，仅对超过两倍租约窗口、Redis 明确无租约且 MySQL 仍活动的 run 补写条件 FAILED；有效租约或 Redis 故障都不误杀。非 owner 实例的取消写有界标记，重连按浏览器实际收到的 sequence 每 250 ms 有界跟随 MySQL 事件，最长 30 秒，不启动 Python 或第二次模型调用。
- 保留与媒体：仅新增 Agent Access Flyway V4，建立 `contentState`、稳定 AI 消息媒体引用、Media Outbox 和 `(created_at,id)` 单例游标。默认 180 天清理仅清空原正文/上下文，保留审计、run/事件/用量和归一化记忆；实际 BOUND 图片与清理事务同时写 `MediaReferenceReleased`。OpenAPI 向前升至 0.23.1，仅增可选 `contentState`/`aiQuestionMediaIds`并重新生成客户端；内部 9 类 SSE 协议不变。
- 自动化：Agent Access/H2 命令通过，V1→V4、运行/恢复/取消/权限/保留/Outbox 覆盖无失败；真实 Redis 专项 1/1 通过并精确清理。Python `compileall` 通过，全量 60 项中 51 通过、9 项真实开关跳过；前端 typecheck 连同 OpenAPI 生成全部通过，未修改 Vue。
- 真实链路：使用安全包装器、`D:\Anaconda\envs\langgraph\python.exe`、Java `18086/18087` 两实例与 Python `8000`。唯一低成本初中代数请求在首个 `TEXT_DELTA` 后断开；Python 仅 1 次 run POST/1 次 cancel，MySQL 终态 `CANCELLED`、助手 `PARTIAL`、32 个连续事件且仅 1 个 `FINAL_RESULT`。B 实例使用同 `clientRequestId` 重放同 run 的 32 个已存事件，Python 无第二次 run POST。另以 Java `18088`+错误 Redis 端口实验验证新 run 为 503、0 消息/run，已存会话读仍正常。验收未输出完整问答、Token、API Key 或临时 URL。
- 数据与清理：用途明确的 `edu_platform_agent_dev` 由 V3 前进 V4，不创建数据库/Seed。固定验收范围精确删除 1 会话、2 消息、1 run、32 事件和 1 用量，范围复核 0；无媒体引用/Rabbit 消息/Qdrant 点/Neo4j 图/OSS 对象。Redis 专项按随机前缀与已知 Key 清理，未 `FLUSHDB`、未清空表或删除固定 collection。
- 边界：不承诺已发给模型供应商的请求不计费。生产需确认额度数值与 Redis 容量/高可用性；T13.4 仍负责用户帮助事实，T15A 仅消费本任务的流、停止、恢复、图片 ID 和正文保留投影，不在前端重建治理逻辑。完整命令、Key/TTL、配置、演示与风险见 [T14.2.3 交付文档](../delivery/T14.2.3-agent-runtime-governance.md)。
- 交付：实现提交 `9085ff9`、交付记录提交 `33e3feb` 与 CI 记录提交 `d9afebd` 均已推送；[PR #62](https://github.com/chengchen-ccnu/ccnu-edulion/pull/62) 的最新 push/pull_request 两轮共 8 项 CI 全部成功，最终合并事实见下一节。

## 2026-09-11 PR #62 合并与 T13.4 前置审计

- 合并与清理：[PR #62](https://github.com/chengchen-ccnu/ccnu-edulion/pull/62) 在 Ready、可合并且最新两轮 Java、Python、Frontend、OpenAPI 共 8 项 CI 全部成功后，以普通 merge 合并为 `main@6577abfc8eab45b4f0aedd99428692e25a950f55`；本地 `main` 已快进同步，T14.2.3 本地和远端分支均已删除。
- 既有预留：内部协议 1.1.0 已有 `PLATFORM_HELP` 角色、`USER_HELP_SEARCH` 工具、`HELP_KB` scope、`USER_HELP` evidence/reference 类型与 SearchArguments；Python 角色白名单也只允许 Platform Help 使用该工具。这些基础类型可向前演进，不需要第二套工具或外部浏览器接口。
- 当前缺口：规则意图目前把平台问题路由为 `PLATFORM_HELP + UNSUPPORTED`，而 `UNSUPPORTED` 不允许工具；运行时对 Platform Help 直接返回“T13.4 尚未接入”，`ToolExecutor` 也固定拒绝。Java 的可信上下文没有授予 `USER_HELP_SEARCH`，内部 Controller/客户端没有帮助检索回调。这四处必须在同一任务闭环，否则只添加帮助文件仍然不可用。
- 事实源方案：遵循 `agent-platform-design.md`，首期帮助知识随 `agent-access-service` 以版本化 JSON 资源发布，由 Git 审查和 manifest 选择当前版本；不放入 operation-service，不新建 MySQL 表或管理后台。历史文件不可原地改写，新内容新增版本并更新 manifest；启动和测试必须校验唯一 ID、版本、语言、关键词、正文和路由白名单。
- 查询与回答：新增按 `runId`、固定 Runtime 身份和内部令牌保护的只读帮助检索。Java 从 MySQL run 反查 owner 后才查询当前帮助版本，并返回有界 `USER_HELP` 安全证据。强规则命中直接使用已维护正文，不消耗模型；复合/自然语言问题可把有限命中材料交给现有模型组织答案。无可靠命中时明确不知道并建议用户换一种描述，禁止模型凭经验猜按钮、路由或未交付功能。
- 契约边界：内部协议应向前增加明确的 `PLATFORM_USAGE_HELP` 二级意图、帮助查询 DTO，以及 `AuthorizedReference` 的可选白名单相对路由；现有 v1 run/SSE/取消兼容。只允许仓库已交付的公共页面相对路径，禁止外部 URL、`/internal/**`、服务端 API、动态占位 ID、page-specs 原文、roadmap、测试账号、权限枚举和“建设中”能力。
- 范围判断：实现集中在 Agent Access 资源加载/只读内部 API、Python AgentAccessClient/工具/意图/回答与契约测试，范围可独立验收，不再拆分。T13.4 不修改 Vue、公共 `/help` 页面、数据库迁移、Redis、RabbitMQ、OSS、Qdrant/Neo4j 索引或 T14.3 自主组题。
- 前置与数据：T14.1、T13.3、T14.2.1～T14.2.3 均已合并，Agent Access↔Python 私有身份和真实 SSE 可复用；百炼配置可用于一个有界复杂帮助问题验收。审计没有连接或修改 MySQL、Redis、RabbitMQ、Qdrant、Neo4j、OSS，没有创建伪数据或调用收费模型。
- 准备结论：前置满足，已从干净 `main@6577abf` 创建 `codex/t13.4-user-help-knowledge-base`。当前分支只提交 T14.2.3 合并状态、T13.4 前置审计和交付计划，不实现业务代码、不推送。

## 2026-09-11 T13.4 用户帮助知识库与 Help 专家接入

- 版本化事实：Agent Access 新增 manifest、Draft 2020-12 JSON Schema 和 `2026.09.1` 中文帮助版本，共 11 篇文章，覆盖登录/注册/验证码/找回密码、账号安全、课程与我的学习、视频/图文/资料、题库边界、作业考试、成绩答案、进度和 AI 边界。启动加载严格检查结构、版本、唯一 ID/规则、正文和精确路由白名单；失败时阻止启动，不回退模型猜测。
- 契约与授权：内部协议向前升至 1.2.0，新增 `PLATFORM_USAGE_HELP` 与 `POST /internal/v1/agent/runs/{runId}/user-help:search`，请求只含有界 `query/limit`。现有固定 `edu-agent-runtime` caller、内部令牌和 MySQL run owner 反查继续生效；合法学生 run 获得 `USER_HELP_SEARCH + HELP_KB`，返回最多 5 条 `USER_HELP` 安全证据和可选白名单相对路由，不返回权重、内部路径、owner 或未命中内容。
- Python 闭环：移除 `UNSUPPORTED` 映射和固定未接入降级，AgentAccessClient/ToolExecutor 接通唯一帮助工具。强规则命中直接使用维护正文且不调用模型；复杂问题仅把有限帮助正文作为不可信数据交给现有模型；空命中明确请求补充页面与目标操作。五角色、工具/意图/scope 白名单、预算、SSE、取消、部分回答和用量上报保持既有边界。
- 自动化：Java 全仓 `clean verify` 9 模块成功，253 项执行、219 通过、34 项真实依赖开关跳过、0 failure/error；Agent Access 33 项中 30 通过、3 跳过。Python `compileall`、wheel 成功，全量 66 项中 57 通过、9 项真实开关跳过、0 failure；内部协议/资源/动态运行时定向 42 项通过。外部 OpenAPI 客户端重生成无差异，未修改 Vue，`git diff --check` 通过。
- 真实链路：通过 `scripts/Invoke-WithLocalEnvironment.ps1` 启动 Java Agent Access 与 Python runtime。高精度登录帮助为 `COMPLETED`、0 次模型、1 次帮助工具；复合进度/成绩帮助为 `COMPLETED`、1 次真实百炼模型、1 次帮助工具。验收显式关闭在线检索、Redis 治理、索引发布、保留任务和 Media Outbox 发布，只输出状态与计数，未输出完整问题/回答、提示词、Token、密钥或内部正文。
- 数据与清理：没有创建数据库、表、迁移、账号、课程、题目或长期 Seed。真实验收仅在已有 `edu_platform_agent_dev` 创建随机 owner 的 2 个会话及其消息/run/事件/用量，`finally` 按 owner 和外键顺序精确删除全部关联事实，复核残留为 0；未写 Redis、RabbitMQ、Qdrant、Neo4j 或 OSS。
- 边界与后续：历史帮助修订必须新增版本文件并更新 manifest，不得原地改写；当前小规模规范化匹配不扩展为新向量索引。T15A 只消费既有 SSE、安全来源和相对路由，不读取知识库文件或复制帮助规则。完整启动、测试、演示、清理和风险见 [T13.4 交付文档](../delivery/T13.4-user-help-knowledge-base.md)。
- 交付：实现提交 `a992a89`、验收记录提交 `0fd8790` 与 PR 记录提交 `9734a3d` 均已推送；[PR #63](https://github.com/chengchen-ccnu/ccnu-edulion/pull/63) 的最新 push/pull_request 两轮共 8 项 CI 全部成功，最终合并事实见下一节。

## 2026-09-11 PR #63 合并与 T15A 前置审计

- 合并与清理：[PR #63](https://github.com/chengchen-ccnu/ccnu-edulion/pull/63) 在 Ready、可合并且最新两轮 Java、Python、Frontend、OpenAPI 共 8 项 CI 全部成功后，以普通 merge 合并为 `main@c6bd8f60213377781be4e32e7eb1b64016cab12e`；本地 `main` 已快进同步，T13.4 本地和远端分支均已删除。
- 已满足依赖：T13.1～T13.4、T14.1、T14.2.1～T14.2.3 和 T07 均已合并；Agent Access 已具备 owner 会话/消息、运行、保存事件、停止、恢复、Redis 治理、180 天保留、AI 图片引用和 Help 来源，Python 动态运行时与真实百炼链路也已验收。
- SSE 阻断：外部 OpenAPI 0.23.1 的 `AgentClientStreamEvent.payload` 仍是无字段 object。内部协议虽有类型，但浏览器只能以外部 OpenAPI 为事实源；T15A 若手写 `delta/stage/reference/finalAnswer` 会形成第二套 DTO，并且无法由生成类型保证 9 类事件的失败和空值语义。
- 偏好阻断：Agent Access 已有 `ai_preference` 及明确/推断事实，但外部没有本人明确讲解偏好读写接口。T15A 目标要求学生修改讲解偏好，同时禁止展示画像；用普通聊天消息代替设置无法提供 revision、409、默认值或稳定 UI 状态。
- 重新生成阻断：现有发送接口的新 key 会创建新用户消息，旧 key 只恢复原 run；没有“基于原消息及其授权快照启动新 run”的命令。目标设计明确重新生成需要新 run、计入用量且恢复不调用模型，前端无法可靠推导该语义；V1 的用户消息到 run 唯一约束也需向前处理，不能改写旧迁移。
- 图片阻断：OpenAPI 与 Agent Access 已预留 `AI_QUESTION_IMAGE`，但 `MediaPurposePolicy.ENABLED` 没有该用途，当前学习账号创建上传会话会得到用途未启用，故“图片提问”不能由 T15A 单独完成。新策略须按 `learning:access` 而非固定角色开放，并保持 owner、用途、READY、MIME、大小和回收边界。
- 拆分结论：上述四项是同一个“浏览器消费稳定契约”缺口，集中在外部 OpenAPI、Agent Access 和 Media 的最小向前实现，可独立验收；记录为 T14.2.4，不把后端补口塞入 T15A。T14.2.4 不实现 Vue、第二套 SSE、画像查看、悬浮答疑、自主组题或新的基础设施。
- 准备结论：T15A 前置不满足，因此没有创建 `codex/t15a-student-ai-chat-web`。已从干净 `main@c6bd8f6` 准备 `codex/t14.2.4-agent-web-contract`；当前分支只提交 T13.4 合并状态、T15A 前置审计和 T14.2.4 计划，不实现业务代码、不推送。
- 数据：本次合并与只读审计未连接或修改 MySQL、Redis、RabbitMQ、Qdrant、Neo4j、OSS，未调用模型，也未创建数据库、表、伪数据、消息、索引点或媒体对象。

## 2026-09-11 T14.2.4 Agent 浏览器消费契约补口

- SSE 与生成客户端：外部 OpenAPI 升至 0.23.2，以九个 `eventType const` 分支把公开流冻结为可收窄 `oneOf`；Java 在持久化和回显前严格校验信封、各类 payload、字段上限、授权来源、安全错误和终态组合。生成 TypeScript 的类型级编译测试覆盖全部九类，浏览器无需手写 DTO。
- 偏好与重新生成：新增本人 `learning:access` 的明确讲解偏好 GET/PUT，默认 `GUIDED_STEP_BY_STEP`，CAS revision 与稳定命令 key 同时防止覆盖和重试重复。最后一问重新生成只读取已存正文/授权快照并重新授权精确 ID，新增 assistant/run、保留旧回答，重复 key 恢复同 run，继续经过 Redis 配额、租约、取消和 MySQL 用量事实。
- 图片：Media 启用 `AI_QUESTION_IMAGE` 的 JPG/JPEG、PNG、WebP 单对象上传，最大 10 MiB；权限由 `learning:access` 决定，资产 scope 为 `USER_PRIVATE`，读取和 Agent 绑定继续校验 owner、用途、READY。Agent Access 不接 OSS SDK，其他用途策略不变。
- 迁移：Agent Access V5 先增加普通消息索引，再解除单消息单 run 唯一约束，新增重新生成请求唯一键和偏好命令幂等表；Media V6 向前扩展 scope 检查约束。旧迁移未改写，无跨服务外键或共享事实。
- 自动化：Java 全仓 `clean verify` 9 模块成功，共 264 项执行、229 通过、35 项真实开关跳过，Agent Access 42 项中 39 通过、3 项跳过，Media 33 项中 25 通过、8 项跳过；Python 使用项目 `langgraph` 环境后 `compileall`、wheel 成功，全量 66 项中 57 通过、9 项真实开关跳过；前端 generate/typecheck、43 文件 294 项 Vitest 和三个应用 build 全过。默认 Anaconda 解释器缺声明依赖的首次失败已如实记录，不影响项目环境结果。
- 真实链路：安全包装器启动 Gateway、Media/OSS、Agent Access、Python runtime/model。真实 `USER_PRIVATE/READY` PNG 随问题运行，原问题与一次重新生成共 1 条用户消息、2 条 assistant 消息、2 个 run；同重生成 key 重放相同 run。偏好更新为 `COMPLETE_SOLUTION` revision 1，后续新 run 完成；SSE 含阶段、文本和唯一终态，验收输出无敏感 payload。
- 数据与清理：只在既有 `edu_platform_agent_dev`、`edu_platform_media_dev` 保留 Flyway V5/V6 结构迁移，不创建数据库、账号、课程、题目或长期 Seed。随机 Agent/MySQL、Media/MySQL、Redis key 与 OSS 精确前缀对象均在 `finally` 删除，复核三类验收残留均为 0；所有本地服务已停止。
- 边界：没有实现 Vue、悬浮答疑、画像查看、自主组题、第二套 SSE/媒体客户端或新基础设施。T15A 必须等待本 PR 合并后从最新 main 开工。完整命令、演示与风险见 [T14.2.4 交付文档](../delivery/T14.2.4-agent-web-contract.md)。
