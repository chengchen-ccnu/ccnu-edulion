# T06 课程前端交付说明

## 任务目标与功能边界

T06 在既有三端前端基础和 T05 课程域上完成学生、教师两条可演示流程：

- 学生端课程列表：关键词、分类、难度、访问方式筛选，URL 查询参数和服务端分页。
- 学生端课程详情：课程介绍、讲师、线上版本、章节与课时目录、类型及试看标识。
- 教师端课程列表：本人课程分页、发布状态、未发布修改和版本信息。
- 教师端课程管理：幂等创建、元数据编辑、章节/课时新增删除与上下移动、完整目录保存、发布、重新发布和下架。
- 所有请求字段和响应类型来自 `docs/api/openapi.yaml` 生成客户端；教师请求复用 T04 统一登录态、角色/权限守卫、短期 Access Token 和一次 401 刷新重试。

本任务明确不实现视频或封面文件上传、视频播放、媒体资源绑定、题库、题目编辑、报名、学习进度、作答成绩、支付或 Python Agent。`VIDEO`、`ARTICLE`、`ASSESSMENT` 仅为 T05 已冻结的课时目录类型，不创建或绑定对应领域资源。学生详情只显示课程营销信息与目录。

## 架构与调用流程

学生链路：学生应用路由守卫校验 `STUDENT + learning:access` → 生成客户端经 Gateway 调用公开课程分类/列表/详情 → 页面只渲染当前已发布版本。筛选条件写入 `/courses` 查询参数，刷新、前进和后退可恢复当前筛选及页码。

教师链路：教师应用路由守卫校验 `TEACHER + teaching:access` → `AuthStore.authorizedRequest` 注入内存 Access Token → Gateway 校验 JWT 并重建可信身份头 → `course-service` 只查询或修改当前教师拥有的课程。领域请求返回 401 时统一认证层只刷新并重试一次，页面不保存 Token，也不维护第二套登录状态。

T05 契约缺少教师课程列表和可供表单选择的分类读取接口。T06 先兼容扩展 OpenAPI 0.4.0，再实现课程服务生产者和生成客户端消费者：

- `GET /api/v1/courses/categories`：公开读取启用分类，供学生筛选与教师表单使用。
- `GET /api/v1/teacher/courses?page=&size=`：分页读取当前教师拥有的编辑快照，Gateway 继续使用既有教师路由鉴权。

没有增加跨服务查表、跨 Schema Join 或前端直连领域服务。分类与教师列表仍由 `course-service` 读取课程 Schema，浏览器统一访问 Gateway。

## 前端路由与交互

学生端：

- `/courses`：筛选表单、加载/错误/空状态、课程卡片和分页。
- `/courses/:courseId`：课程封面、元数据、纯文本安全课程介绍和层级目录。

教师端：

- `/teacher/courses`：本人课程列表、版本/状态提示、发布和下架入口。
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

自动化测试继续使用进程内 H2 MySQL 模式 `course_test`/`course_seed_test`，Flyway 重建既有 T05 表并生成最小测试分类、教师课程和目录；测试进程结束即释放。未连接、创建或修改本机 MySQL `edu_platform_course_dev`，未创建任何持久伪数据。

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

已执行并通过：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl course-service -am test
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
```

当前结果：

- Java：完整 `clean verify` 的 8 个 Reactor 模块全部成功；Gateway 8 项、IAM 9 项、Course 8 项、shared-kernel 1 项测试通过。Course 覆盖启用分类读取、教师所有权分页、HTTP 契约、既有筛选/详情/创建/编排/发布链路。
- 前端：全部 workspace 类型检查通过；4 个 Vitest 文件共 20 项测试通过，新增覆盖学生筛选参数、分类/详情路径、教师授权列表、发布请求和领域请求 401 单次刷新重试。
- 生产构建：学生、教师、管理三端均成功。受限沙箱内 Vitest/Vite 首次因 `esbuild spawn EPERM` 失败，获准在沙箱外复跑后通过。
- OpenAPI：0.4.0 成功生成 TypeScript 客户端，生成文件纳入版本控制。
- Mock：Prism 成功加载 0.4.0；启用分类和携带 Bearer 头的教师课程列表均返回 200。

`check:api` 会在生成文件随实现提交后执行，因为该命令会比较生成文件与当前 `HEAD`；最终变更检查和前端回归会在发布前完成。

浏览器视觉检查未完成：Prism、学生端和教师端本地服务在宿主机可访问，但 Codex 内置浏览器无法连接宿主机本地服务，外部 Chrome 连接不可用。没有把构建通过标记为视觉验收；需要按下述人工步骤补验桌面与窄屏布局。

## 人工演示步骤

1. 启动 IAM、Course、Gateway 和学生/教师应用；确认学生/教师测试账号只通过未跟踪的环境秘密提供密码。
2. 学生登录后进入 `/courses`，分别使用关键词、分类、难度、访问方式筛选，验证 URL 查询参数和分页前后切换。
3. 打开课程详情，验证讲师、线上版本、章节课时顺序、类型和试看标识；确认没有播放、报名、进度、题目或 Agent 控件。
4. 教师登录后进入 `/teacher/courses`，验证只显示本人课程和正确的草稿/已发布/已下架状态。
5. 创建课程，确认分类来自启用分类下拉框；网络重试不应生成重复课程。
6. 编辑元数据；若课程已有线上版本，确认页面提示线上版本不会被直接覆盖。
7. 编排至少一个非空章节：新增课时、上下移动并保存，刷新后顺序保持一致。
8. 发布课程，使用学生端确认新线上版本；继续编辑后确认学生端仍显示旧版，二次发布后才切换。
9. 下架课程，确认学生列表和详情不可见；从教师端重新发布后恢复。
10. 用学生账号访问教师 URL，确认统一权限守卫拒绝；用匿名窗口访问教师 URL，确认跳到统一登录且保留安全 redirect。
11. 在桌面和窄屏浏览器检查列表、表单、目录编辑器没有横向溢出，键盘焦点清晰。

## 已知风险与后续依赖

- 教师列表返回完整编辑快照和目录，当前页面大小为 10；大目录课程的载荷和服务端 N+1 查询需要后续以摘要投影或批量查询优化。
- 分类只有只读列表；分类管理、停用影响评估仍不在 T06 范围。
- 浏览器视觉与真实 MySQL/Gateway 端到端演示尚需人工补验，自动化证据覆盖契约、类型、API 封装、认证重试、Java HTTP/持久化和生产构建。
- `ASSESSMENT` 和 `VIDEO` 只是目录类型。实际题目、媒体资源、播放授权、报名与进度分别依赖后续 T07/T09/T11 等任务。

## 分支、PR 与关键提交

- 分支：`feat/t06-course-web`
- Draft PR：提交并推送后创建，随后回填链接。
- 关键提交：提交后回填。
