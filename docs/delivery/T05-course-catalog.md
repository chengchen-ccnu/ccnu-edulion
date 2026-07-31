# T05 课程与目录后端交付说明

## 任务目标与边界

T05 在既有 Monorepo 中实现课程域后端闭环：

- 学生匿名浏览已发布课程，支持关键词、分类、难度、访问类型筛选和分页，并查询已发布版本详情与目录。
- 教师通过 Gateway 创建、编辑本人课程，整体替换章节/课时目录，发布或下架课程。
- 已发布课程版本不可原地修改；教师继续编辑时复制出新草稿，发布新草稿后归档旧版本。
- Gateway 公开学生查询，只允许 JWT 中包含 `TEACHER` 角色的用户访问教师课程路由，并重建可信身份头。
- MySQL 由 Flyway 管理，业务访问使用 MyBatis；local/test Seed 使用固定 ID，支持幂等重放和定向清理。

本任务不实现或建表：视频/媒体资源、题库、报名、购买、学习进度、答题/成绩、热点运营、通知、课程审核工作流或 Python Agent。Course 不查询 IAM 表，不建立跨 Schema 外键，只保存教师用户 ID 和课程域的讲师展示文案。

## 架构与调用流程

学生调用链为：浏览器 → Gateway 公开课程路由 → `course-service` → `published_version_id` 指向的不可变版本 → MySQL。草稿和已下架课程不会进入学生查询。

教师调用链为：浏览器携带 Access Token → Gateway 校验 JWT 与 `TEACHER` 角色 → Gateway 删除外部伪造身份头并注入 `X-User-Id`/`X-User-Roles` → `course-service` 再校验可信教师身份和课程所有权 → 草稿事务与乐观版本检查 → MySQL。

创建使用 `owner_teacher_id + clientRequestId` 唯一键实现并发幂等。元数据和目录使用 `expectedRevision` 防止覆盖并发修改；完全相同的请求重放返回当前结果。发布将当前草稿标记为 `PUBLISHED`，旧线上版本标记为 `ARCHIVED`，再原子切换课程指针；下架只改变课程可见状态，不删除历史版本。

## API

契约唯一来源为 `docs/api/openapi.yaml` 0.3.0，生成客户端位于 `web-apps/packages/api-client/src/generated/schema.d.ts`。

学生接口：

- `GET /api/v1/courses`：`page`、`size`、`keyword`、`categoryId`、`level`、`accessType`。
- `GET /api/v1/courses/{courseId}`：只返回当前已发布版本及章节课时目录。

教师接口：

- `POST /api/v1/teacher/courses`：以 `clientRequestId` 幂等创建课程草稿。
- `GET /api/v1/teacher/courses/{courseId}`：返回当前草稿；无草稿时返回最新发布版本作为编辑基线。
- `PUT /api/v1/teacher/courses/{courseId}`：替换可编辑版本的课程元数据。
- `PUT /api/v1/teacher/courses/{courseId}/curriculum`：整体替换章节和课时顺序。
- `PUT /api/v1/teacher/courses/{courseId}/publication`：目标状态只接受 `PUBLISHED` 或 `UNPUBLISHED`。

统一错误响应保持 `code`、`message`、`traceId`、`timestamp`、`path`、`fieldErrors`。主要课程错误码包括 `COURSE.NOT_FOUND`、`COURSE.CATEGORY_NOT_FOUND`、`COURSE.VERSION_CONFLICT`、`COURSE.PUBLISH_VALIDATION_FAILED` 和 `COURSE.NOT_PUBLISHED`。

## 数据库与迁移

目标 Schema 只能是课程专用 `edu_platform_course_dev`；应用不会自动创建数据库。迁移文件：

- `java-platform/course-service/src/main/resources/db/migration/V1__create_course_catalog_tables.sql`

业务表：

- `category`：课程分类编码、名称和启停状态。
- `course`：教师所有权、创建幂等键、当前草稿/发布版本指针和发布状态。
- `course_version`：不可变发布版本与可编辑草稿的元数据、生命周期、目录哈希和乐观版本。
- `chapter`：隶属具体课程版本的章节及顺序。
- `lesson`：隶属章节的课时类型、顺序和试看标志；不包含媒体、题目或学习数据。

所有外键都在课程 Schema 内部；没有跨服务外键或 Join。Flyway 还会维护自身的 `flyway_schema_history` 技术表。

## 环境变量与启动

关键变量：

- `SPRING_PROFILES_ACTIVE=local`
- `COURSE_MYSQL_DATABASE=edu_platform_course_dev`
- `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`
- `COURSE_SEED_ENABLED=false`（只有明确的 local/test Schema 才可改为 `true`）
- `COURSE_REDIS_DATABASE=2`
- `COURSE_SERVICE_URL=http://localhost:8082`（Gateway 默认值）
- Gateway/IAM 共用的 `IAM_JWT_SECRET`、`IAM_JWT_ISSUER`、`IAM_JWT_AUDIENCE`

从仓库根目录启动：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    if ($env:COURSE_MYSQL_DATABASE -ne 'edu_platform_course_dev') {
        throw '拒绝启动：不是 edu_platform_course_dev'
    }
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl course-service -am spring-boot:run
}

& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl gateway-service -am spring-boot:run
}
```

首次使用前运行 `scripts/Initialize-LocalEnvironment.ps1`，通过 Windows DPAPI 初始化本机 MySQL 凭据。Seed 密码、JWT 密钥等其他秘密仍必须通过当前进程、IDE 密钥配置或本机密钥管理工具注入，不得写入仓库或命令历史。完整说明见 `docs/development/local-environment.md`。

## 伪数据、清理与重新生成

显式设置 `COURSE_SEED_ENABLED=true` 后，local/test 初始化器会写入：

- 分类：`00000000-0000-4000-8000-000000000501`，“初中数学”。
- 课程：`00000000-0000-4000-8000-000000000201`，“七年级代数基础”，归属 T04 `teacher_test_01` 的固定用户 ID。
- 版本：固定已发布 `v1`。
- 目录：一个“有理数”章节和一个可试看 `VIDEO` 类型“正数与负数”课时；不含媒体 ID 或播放地址。

重复运行初始化器不会增加重复 Fixture。清理前必须执行 `SELECT DATABASE()` 并确认结果严格为 `edu_platform_course_dev`，再执行：

```sql
source java-platform/course-service/src/main/resources/db/seed/cleanup-course-seed.sql;
```

清理脚本只删除固定测试课程、由它派生出的草稿版本/目录，以及不再被其他课程引用的固定分类。重新生成时再次启用 Seed 并重启 `course-service`。

## 测试命令与实际结果

已执行并通过：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl gateway-service,course-service -am test
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
```

结果：

- Java 8 个 Reactor 模块成功；Gateway 8 个测试、Course 8 个测试、IAM 9 个既有测试和 shared-kernel 1 个测试通过。
- Course 测试使用 H2 MySQL 模式的 `course_test`/`course_seed_test`，覆盖迁移、MyBatis、HTTP、筛选分页、详情、所有权、乐观并发、幂等重放、不可变版本、上下架、Seed 与清理。
- OpenAPI 客户端生成和全部 workspace 类型检查通过；Vitest 15 个测试通过；学生/教师/管理三端生产构建通过。
- Prism 成功加载 OpenAPI，学生课程列表和带 Bearer 头的教师创建 Mock 均返回 200。
- Vitest/Vite 在受限沙箱内首次因 esbuild 子进程 `EPERM` 无法启动，获准在沙箱外复跑后通过。
- 2026-07-31，用户通过 DPAPI 本地凭据工作流在专用 `edu_platform_course_dev` 完成真实 MySQL 人工验收：Flyway V1、固定 Seed、匿名列表/筛选/分页/详情、教师幂等创建、目录编排、发布版本隔离、再次发布、乐观锁冲突、Gateway 角色拒绝、下架和定向清理均符合预期。该项为用户报告的人工验收结果；Codex 未读取、打印或复制本机凭据，也未在本轮重新操作数据库。

## 人工演示步骤

1. 由有权限的本地数据库管理员准备空的 `edu_platform_course_dev` 和课程服务专用账号，确认当前连接数据库名称。
2. 使用 `scripts/Initialize-LocalEnvironment.ps1` 初始化本机凭据，再通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入 local 环境；设置 `COURSE_SEED_ENABLED=true`，启动 `course-service`，确认 Flyway V1 成功且 Seed 只生成本页列出的固定数据。
3. 启动 IAM 与 Gateway，使用 T04 教师测试账号登录并获得内存 Access Token。
4. 匿名调用 `GET /api/v1/courses`，验证筛选分页；调用固定课程详情，验证只显示 `v1` 和固定目录。
5. 携带教师 Token 创建课程，保存返回的课程 ID/revision；编排目录并发布。
6. 编辑已发布课程，确认教师响应显示新草稿，而匿名详情仍为旧版本；再次发布后确认匿名详情切换到新版本。
7. 使用学生 Token 请求教师接口，确认 Gateway 返回 403；尝试访问其他教师课程，确认 Course 返回不可枚举的 404。
8. 下架课程，确认匿名列表/详情不可见；执行清理脚本并确认只移除固定 Fixture。

## 已知风险与后续依赖

- 真实 MySQL 8 的迁移、Seed 和功能链路已完成人工验收，但尚未使用生产级数据量验证分页查询和索引执行计划。
- 分类管理不在 T05 范围；除 local/test 固定分类外，部署环境需要受控的分类初始化或后续管理能力。
- `VIDEO`/`ASSESSMENT` 当前只是目录类型。媒体就绪校验、测验绑定、播放授权分别依赖后续 T07/T09/T11，T05 不创建这些资源。
- 当前教师可直接发布/下架；课程审核工作流、管理员复核和发布领域事件尚未实现。RocketMQ/Outbox 接入需要后续任务补齐。
- `ENROLLMENT_REQUIRED` 只描述课程访问类型；报名、购买和课时内容授权依赖 T11。本任务公开的是课程营销信息和目录，不返回受保护学习内容。

## 分支、PR 与关键提交

- 分支：`feat/t05-course-catalog`
- PR：[#4 feat: implement T05 course catalog backend](https://github.com/chengchen-ccnu/ccnu-edulion/pull/4)
- 关键实现提交：`323f89d7d044888ac944df195b55a25dc606d546`（`feat: implement T05 course catalog backend`）。
