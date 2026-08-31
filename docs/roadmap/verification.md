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
- OSS 与 Seed：编排已实现生产禁用的固定 ID Initialize/Cleanup/Rebuild，按 Media→Course→Learning 初始化、逆序清理；媒体脚本合成视频、自动/自定义海报、正文图、有效最小 PDF/PPTX 与 TXT，不提交二进制。当前本机共享配置只有 OSS 访问凭据，缺少 endpoint/region/bucket；统一前置检查会在任何领域写入前失败。早先分域尝试已清掉固定 Course/Learning 数据，Media 未初始化，因此没有保留 T12F.1 Seed、OSS 对象、消息或本机文件；只保留三项迁移。不能把 H2/本地目录冒充真实 OSS，补齐明确开发 Bucket 后须重跑 Rebuild 和综合访问/精确 Cleanup。
- 可读性与边界：权限判断、Markdown 验证、处理状态机、幂等、Outbox、Seed/清理和复杂测试均增加中文 Javadoc/注释；OpenAPI/SQL 解释兼容与空值语义，生成代码未手改。本任务没有 Vue、多码率、清晰度、字幕 UI、历史海报 API、Office 转换、DRM、AI、Redis 播放缓存、第二套 OSS 客户端或跨 Schema Join/外键/事务。
- 发布：关键实现提交 `f5e03b5` 已按规则推送到 `codex/t12f1-course-content-contract`；Ready [PR #42](https://github.com/chengchen-ccnu/ccnu-edulion/pull/42) 已创建，目标 `main`，本任务不自行合并。完整实现、命令、演示和清理说明见 `docs/delivery/T12F.1-course-content-contract.md`。
