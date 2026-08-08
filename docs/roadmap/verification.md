# Skeleton verification

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
- 发布：实现提交为 `f2e1d972e828759089d2192227366ddb4985059b`；[PR #14](https://github.com/chengchen-ccnu/ccnu-edulion/pull/14) 已合并，主线 merge commit 为 `2f40b762d41f780509ab388928aff84614d6b62c`。

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
