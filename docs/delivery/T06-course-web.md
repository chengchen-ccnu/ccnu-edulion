# T06 课程前端交付说明

## 任务目标与功能边界

T06 在既有三端前端基础和 T05 课程域上完成公共课程浏览与教师教学管理两条可演示流程：

- 公共“课程中心”：游客、学生、教师和管理员均可浏览已发布且可见课程，支持关键词、分类、难度、访问方式筛选、服务端分页和课程详情。
- 教师端课程列表：本人课程分页、发布状态、未发布修改和版本信息。
- 教师端课程管理：幂等创建、元数据编辑、章节/课时新增删除与上下移动、完整目录保存、发布、重新发布和下架。
- 所有请求字段和响应类型来自 `docs/api/openapi.yaml` 生成客户端；教师请求复用 T04 统一登录态、角色/权限守卫、短期 Access Token 和一次 401 刷新重试。

“我的课程”表示真实报名、学习中或已完成课程，本次不创建占位页、不伪造报名/进度 API 或数据，留给 T11/T12。本任务同样不实现视频或封面文件上传、视频播放、媒体资源绑定、题库、题目编辑、作答成绩、支付或 Python Agent。`VIDEO`、`ARTICLE`、`ASSESSMENT` 仅为 T05 已冻结的课时目录类型，公共详情只显示课程营销信息与目录。

当前课程表单的 `coverUrl` 是阶段性兼容输入，不是最终 OSS 方案。T07 将建立通用阿里云 OSS 媒体后端；T08 必须新增 `COURSE_COVER` 上传和课程资产绑定，通过向前数据库迁移保存 `cover_media_id`，并把本页面替换为文件选择、上传进度、预览和替换交互。历史 `cover_url` 只在迁移期兼容读取，不能成为新功能继续依赖的永久设计，详见 [ADR-0004](../adr/0004-oss-media-asset-lifecycle.md)。

## 架构与调用流程

公共浏览链路：`student-web` 的 `/courses` 与 `/courses/:courseId` 不要求登录、角色或学习权限 → 生成客户端经 Gateway 调用 OpenAPI 标记 `security: []` 的课程分类/列表/详情 → 页面只渲染当前已发布版本。首次进入可在后台尝试一次 Refresh Cookie 恢复身份，用于显示工作台与退出入口；失败仍匿名浏览且不循环刷新。筛选条件写入 `/courses` 查询参数，刷新、前进和后退可恢复当前筛选及页码。

教师链路：教师应用路由守卫校验 `TEACHER + teaching:access` → `AuthStore.authorizedRequest` 注入内存 Access Token → Gateway 校验 JWT 并重建可信身份头 → `course-service` 只查询或修改当前教师拥有的课程。领域请求返回 401 时统一认证层只刷新并重试一次，页面不保存 Token，也不维护第二套登录状态。

T05 契约缺少教师课程列表和可供表单选择的分类读取接口。T06 先兼容扩展 OpenAPI 0.4.0，再实现课程服务生产者和生成客户端消费者：

- `GET /api/v1/courses/categories`：公开读取启用分类，供学生筛选与教师表单使用。
- `GET /api/v1/teacher/courses?page=&size=`：分页读取当前教师拥有的编辑快照，Gateway 继续使用既有教师路由鉴权。

没有增加跨服务查表、跨 Schema Join 或前端直连领域服务。分类与教师列表仍由 `course-service` 读取课程 Schema，浏览器统一访问 Gateway。

## 前端路由与交互

公共门户（由 `student-web` 承载）：

- `/courses`：公开的“课程中心”，包含筛选表单、加载/错误/空状态、课程卡片和分页。
- `/courses/:courseId`：公开课程封面、元数据、纯文本安全课程介绍和层级目录。
- `/login`：唯一统一登录入口，外层壳和表单均使用角色中立文案；`student-web` 只是物理承载应用。
- `/`：学生专属学习首页，继续要求 `STUDENT + learning:access`。

教师端：

- “课程中心”：通过共享 `VITE_STUDENT_WEB_URL` 构造跨应用 `/courses` 地址，不硬编码开发端口。
- `/teacher/courses`：“教学管理”，只向教师显示，并继续只读取当前教师拥有的课程。
- `/teacher/courses/new`：使用单次 `clientRequestId` 的幂等创建表单；请求失败重试仍使用同一 ID。
- `/teacher/courses/:courseId/edit`：携带 `expectedRevision` 保存元数据；编辑线上课程时明确提示正在生成或修改未发布版本。
- `/teacher/courses/:courseId/curriculum`：章节课时新增、删除、上移、下移、连续序号重建、完整替换保存及上下架；存在未保存修改时禁用发布操作。

统一错误信封被转换为页面消息和字段错误；409 显示版本冲突并要求重新加载，404 不泄露其他教师课程是否存在，422 显示服务端发布或字段校验结果。

## API 与生成客户端

唯一契约：`docs/api/openapi.yaml` 0.4.0。生成文件：`web-apps/packages/api-client/src/generated/schema.d.ts`。

T06 使用的课程接口：

- `GET /api/v1/courses/categories`
- `GET /api/v1/courses`
- `GET /api/v1/courses/{courseId}`
- `GET /api/v1/teacher/courses`
- `POST /api/v1/teacher/courses`
- `GET /api/v1/teacher/courses/{courseId}`
- `PUT /api/v1/teacher/courses/{courseId}`
- `PUT /api/v1/teacher/courses/{courseId}/curriculum`
- `PUT /api/v1/teacher/courses/{courseId}/publication`

`web-apps/packages/api-client/src/index.ts` 只导出由 OpenAPI `components`/`paths` 推导的别名和统一错误适配，不手写重复 DTO。学生/教师 `course-api.ts` 只封装生成客户端路径调用。

## 数据库表与迁移

T06 没有新增或改写数据库迁移，也没有新增业务表。新增读接口复用 T05 `V1__create_course_catalog_tables.sql` 中的 `category`、`course`、`course_version`、`chapter` 和 `lesson`：

- 启用分类按名称和 ID 排序读取。
- 教师列表按 `owner_teacher_id` 隔离，按最近更新时间和 ID 排序分页。

原 T06 后端自动化测试使用进程内 H2 MySQL 模式 `course_test`/`course_seed_test`。本次信息架构修复只修改前端与文档，没有运行 Java 测试、连接任何数据库、创建或修改数据库/表，也没有生成伪数据。

## 环境变量与启动方式

没有新增环境变量。前端继续使用：

- `VITE_GATEWAY_BASE_URL`，默认 `http://localhost:8080`。
- `VITE_STUDENT_WEB_URL`，默认 `http://localhost:5173`。
- `VITE_TEACHER_WEB_URL`，默认 `http://localhost:5174`。
- `VITE_ADMIN_WEB_URL`，默认 `http://localhost:5175`。

本地启动：

```powershell
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run mock:api
pnpm.cmd --dir web-apps run dev:student
pnpm.cmd --dir web-apps run dev:teacher
```

联调真实 Gateway、IAM 和 Course 时，必须先用 `scripts/Initialize-LocalEnvironment.ps1` 准备本地凭据，再通过 `scripts/Invoke-WithLocalEnvironment.ps1` 启动 Java 服务；不得从文档或命令行拼接秘密。

## 伪数据、清理与重新生成

T06 未新增 Seed、Fixture 文件或持久伪数据。真实联调可继续使用 T04 `student_test_01`/`teacher_test_01` 和 T05 固定课程 Seed，生成与定向清理方式见 `T05-course-catalog.md`。自动化测试数据由每个测试的 `@BeforeEach` 在 H2 内存库中重建，不需要人工清理；重新执行 Maven 测试即可重新生成。

## 测试命令与实际结果

### 2026-08-02 信息架构与统一登录修复

本次实际执行并通过：

```powershell
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run check:api
pnpm.cmd --dir web-apps run build
```

- 类型检查：全 workspace 通过；命令同时重新生成 OpenAPI TypeScript 类型。
- Vitest：6 个测试文件、34 项测试全部通过，覆盖匿名与三种角色访问课程中心、公共详情路由、教师双入口、教师登录后回跳 `/courses`、学生拒绝访问教学管理、非法 redirect、单次刷新与跨应用恢复回归。
- OpenAPI：`check:api` 通过，生成文件与 `HEAD` 一致，没有修改契约或手写重复 DTO。
- 生产构建：student、teacher、admin 三端全部通过。Vitest 和首次构建在受限沙箱内因 `esbuild spawn EPERM` 失败，获准在沙箱外原命令复跑后通过；类型检查与 `check:api` 在沙箱内直接通过。
- Java/Mock：本次没有修改 Java 或 OpenAPI 契约，因此没有运行 Maven 或 Prism，不能把历史结果当作本次新验证。
- 可读性：新增公共恢复、redirect 分类和教师导航均有中文 TSDoc/JSDoc；Vue 根壳及公共课程页面职责注释已同步纠正。

以下为原 T06 交付时的历史验证记录：

已执行并通过：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl course-service -am test
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run check:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
```

当前结果：

- Java：完整 `clean verify` 的 8 个 Reactor 模块全部成功；Gateway 8 项、IAM 9 项、Course 8 项、shared-kernel 1 项测试通过。Course 覆盖启用分类读取、教师所有权分页、HTTP 契约、既有筛选/详情/创建/编排/发布链路。
- 前端：全部 workspace 类型检查通过；4 个 Vitest 文件共 20 项测试通过，新增覆盖学生筛选参数、分类/详情路径、教师授权列表、发布请求和领域请求 401 单次刷新重试。
- 生产构建：学生、教师、管理三端均成功。受限沙箱内 Vitest/Vite 首次因 `esbuild spawn EPERM` 失败，获准在沙箱外复跑后通过。
- OpenAPI：0.4.0 成功生成 TypeScript 客户端；提交后执行 `check:api`，生成结果与 `HEAD` 一致且生成文件已纳入版本控制。
- Mock：Prism 成功加载 0.4.0；启用分类和携带 Bearer 头的教师课程列表均返回 200。

最终变更检查、完整 Java 回归、OpenAPI 一致性和前端回归均已完成。

2026-08-01，用户确认已按下述人工演示步骤完成 T06 验收，包括学生课程筛选、分页、详情目录，教师课程创建、元数据编辑、目录编排、发布/重新发布/下架，统一登录权限以及桌面与窄屏页面检查。该结论属于用户人工验收记录；Codex 未重新访问本机数据库、未读取本地凭据，也未采集人工验收截图或浏览器日志。自动化证据仍以上述 Maven、OpenAPI、Vitest、类型检查、生产构建及 GitHub CI 为准。

## 人工演示步骤

1. 启动 IAM、Course、Gateway 和学生/教师应用；确认测试账号只通过未跟踪的环境秘密提供密码。
2. 分别以匿名窗口、学生、教师和管理员身份打开 `/courses` 与一个 `/courses/{courseId}`，确认都能浏览已发布课程；有 Refresh Cookie 时页面应非阻塞恢复工作台与退出入口。
3. 打开课程详情，验证讲师、线上版本、章节课时顺序、类型和试看标识；确认没有播放、报名、进度、题目或 Agent 控件。
4. 教师导航同时显示“课程中心”和“教学管理”；学生身份不得看到教学管理。进入 `/teacher/courses` 后验证只显示本人课程和正确的草稿/已发布/已下架状态。
5. 创建课程，确认分类来自启用分类下拉框；网络重试不应生成重复课程。
6. 编辑元数据；若课程已有线上版本，确认页面提示线上版本不会被直接覆盖。
7. 编排至少一个非空章节：新增课时、上下移动并保存，刷新后顺序保持一致。
8. 发布课程，使用学生端确认新线上版本；继续编辑后确认学生端仍显示旧版，二次发布后才切换。
9. 下架课程，确认学生列表和详情不可见；从教师端重新发布后恢复。
10. 用学生账号访问教师 URL，确认统一权限守卫拒绝；用匿名窗口访问教师 URL，确认跳到角色中立的统一登录门户且保留安全 redirect；教师登录并回跳 `/courses` 不得进入 403。
11. 验证外部 URL、协议相对 URL、反斜杠和未知路径 redirect 均被拒绝。
12. 在桌面和窄屏浏览器检查列表、表单、目录编辑器没有横向溢出，键盘焦点清晰。

## 已知风险与后续依赖

- 教师列表返回完整编辑快照和目录，当前页面大小为 10；大目录课程的载荷和服务端 N+1 查询需要后续以摘要投影或批量查询优化。
- 分类只有只读列表；分类管理、停用影响评估仍不在 T06 范围。
- 浏览器视觉与真实 Gateway/MySQL 端到端流程已由用户人工确认验收通过；Codex 未留存截图或数据库日志，后续回归仍应结合自动化证据和人工演示步骤。
- `ASSESSMENT` 和 `VIDEO` 只是目录类型。实际题目、媒体资源、播放授权、报名与进度分别依赖后续 T07/T09/T11 等任务。
- T11 才实现报名与进度，并按能力模型允许教师在同一账号同时拥有 `teaching:access` 与 `learning:access`；T12 才实现有真实数据支撑的“我的课程（我的学习）”及学习中心/教师工作台切换。本次没有提前实现这些功能。
- T06 的封面 URL 输入必须由 T08 收口为 OSS 直传和稳定资产 ID；T08 验收前，课程媒体纵向链路不能标记为完成。

## 分支、PR 与关键提交

- 分支：`feat/t06-course-web`
- PR：[#6 feat: implement T06 course web](https://github.com/chengchen-ccnu/ccnu-edulion/pull/6)。
- 关键实现提交：`f1e3b139c3f6355013b1ff0b96fc3278454bfa7c`（`feat: implement T06 course web`）。
- 本次修复分支：`codex/t06-course-navigation-auth-fix`；PR 和关键提交将在推送后补充。
