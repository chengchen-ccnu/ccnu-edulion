# T09A 历史 JSON 题库兼容与迁移交付说明

## 任务目标与边界

本任务在已合并的 T09 CSV/XLSX 题库能力之外，增加对只读历史逐题 JSON 的受控迁移。T09A 初始交付不会复制、修改或提交原题库，也未提供自动全量入口；每次任务最多 1000 项、每批最多 20 项，仓库脚本默认 dry-run 且并发固定为 1。T10A 后续在不改变这些服务端上限的前提下增加显式多任务全量编排和断点续传，详见本页“全量编排扩展”。T10 页面、T11 组卷/作答和 Python 智能体均不在范围内。

程序化盘点发现 44,441 份 JSON、0 个解析失败、7,359 个显式 `subQues` 容器、28,646 份含外部图片、8,152 份空 `solution`。模型仅人工阅读 6 个代表文件，每种结构不超过 3 个；盘点和迁移脚本只输出聚合计数。

## 架构与调用流程

1. 只读 PowerShell 脚本逐文件读取 JSON，计算内容哈希和相对路径哈希，只把当前受控批次提交给 Gateway。
2. learning-service 以教师身份、任务、批次和 `sourceSystem + externalId` 校验幂等，解析并白名单清洗 HTML，同时保留 LaTeX、原始难度和目录/JSON 教材元数据。
3. 普通题创建一个草稿版本；显式 `subQues` 创建多个子题和一个 `COMPOSITE` 父题，以关系表保存顺序。解析不可靠的项只记录问题并进入 `NEEDS_REVIEW`。
4. 外部图片只传给 media-service 内部转存端点；media-service 校验 HTTPS、主机白名单、逐跳 DNS、重定向、大小、MIME 和图片魔数，写对象存储后返回稳定 `mediaId`。learning-service 不接入 OSS SDK、不保存 Bucket/Object Key/永久 URL。
5. 每项用独立嵌套事务隔离；批次响应和 v2 Outbox 事件只含 ID、状态、计数和问题码，不含题目内容。清理端点只删除指定任务创建且仍为 DRAFT 的题目图，并发布精确媒体释放事件。

## 已实现契约

- `POST /api/v1/teacher/legacy-question-imports`：创建 dry-run 或实际迁移任务。
- `POST /api/v1/teacher/legacy-question-imports/{importTaskId}/batches`：提交不超过 20 项的幂等批次。
- `DELETE /api/v1/teacher/legacy-question-imports/{importTaskId}/created-resources`：精确清理任务创建资源。
- `POST /api/v1/media/external-image-transfers`：教师身份下的外部图片安全转存。
- `learning-question-events.v2.yaml`：发布不含题目内容的处理结果事件；T09 v1 事件保持不变。

OpenAPI 0.9.0 同时为题目响应增加组合题子题和历史来源投影。前端类型由同一契约生成，没有手写平行 DTO。

## 可导入格式与人工复核

已支持 `select_single`、`select_multiple`、`fillin`、`writeout` 以及显式 `subQues`。严格满足 A～H 标号规则的内嵌选项可拆分；显式子题可使用各自答案，或在数量完全一致时使用父级编号答案。`solution` 可为空。

以下情况不会静默猜测，结果为 `NEEDS_REVIEW`：无法可靠拆分的内嵌选项、疑似隐式多问、显式子题缺少可靠答案、未知题型或结构、目录与 JSON 教材/年级冲突、HTML 含被移除的不安全结构，以及同一来源 ID 内容发生变化。路径值和 JSON 值会同时保存，等待人工确认。

## 数据库迁移

已合并的 `V1__create_question_bank_tables.sql` 未修改。新增 `V2__add_legacy_json_question_import.sql`：

- 扩展 `question_import_task` 的 JSON 来源、dry-run、复核/跳过/清理统计；CSV/XLSX 的 `source_media_id` 改为可空并由格式约束区分。
- 新增 `question_legacy_import_batch`、`question_legacy_source_ref`、`question_legacy_import_issue`、`question_version_child`。
- 来源引用保存外部 ID、内容/路径哈希、目录与 JSON 教材/年级、原始难度、结果和问题码；不保存原题 JSON、题干、外部图片 URL 或路径明文。

迁移只作用于既有专用 learning Schema，不创建数据库、不创建 T11 表、不建立跨 Schema 外键。

## 环境变量与启动

learning-service 仍只需要 `MEDIA_SERVICE_BASE_URL`。media-service 外部图片转存默认关闭：

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `MEDIA_EXTERNAL_IMAGE_IMPORT_ENABLED` | `false` | 必须显式开启实际图片转存 |
| `MEDIA_EXTERNAL_IMAGE_ALLOWED_HOSTS` | 空 | 逗号分隔的精确 HTTPS 主机白名单 |
| `MEDIA_EXTERNAL_IMAGE_MAX_BYTES` | `10485760` | 单图最大 10 MiB |
| `MEDIA_EXTERNAL_IMAGE_TIMEOUT` | `10s` | 单次 HTTP 超时 |
| `MEDIA_EXTERNAL_IMAGE_MAX_REDIRECTS` | `2` | 允许的重定向上限，每跳重新校验 |

本地服务必须通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入凭据，禁止读取或打印凭据文件。迁移脚本仅经 Gateway 调用，并从当前进程的 `EDU_MIGRATION_ACCESS_TOKEN` 读取教师短期令牌；不得通过命令参数或 `local.env` 保存 Token，完成后立即移除。启动方式见 `docs/development/local-environment.md`。

## 小样本执行与清理

先只做 dry-run：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action DryRun -SourceRoot 'I:\大模型学习\edu_agent\智能体项目\data\题库' `
  -MaxItems 5 -BatchSize 5
```

人工检查聚合计数和复核问题后，才可把 `DryRun` 改为 `Import`；T09A 初始任务不授权扩大 `MaxItems` 做全量迁移。记录脚本返回的 `importTaskId`，验收完成后精确清理：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Cleanup -ImportTaskId '<task-uuid>'
```

清理只处理该任务创建且仍为 DRAFT 的题目；若题目已人工发布或修改，接口会拒绝静默删除，需要人工确认后另行处理。dry-run 不创建题目或媒体。

### T10A 全量编排扩展

经用户明确授权，脚本新增 `-AllItems`，将稳定排序后的来源清单自动拆为多个任务；`TaskSize` 仍限定为 1～1000，`BatchSize` 仍限定为 1～20。正式全量导入必须同时指定 `-Action Import -AllItems -ConfirmFullMigration`，缺少确认会在任何请求前拒绝。脚本使用确定性任务/批次 UUID 和本机检查点，响应丢失时安全重放同一请求，令牌或网络中断后用完全相同参数恢复。

检查点保存清单/根指纹、源索引、任务 ID、下一批次和聚合计数，不保存令牌、绝对源路径、题干、答案、图片 URL 或原始 JSON；路径必须位于来源目录之外。来源文件名、大小或修改时间发生变化会改变清单指纹，脚本拒绝把旧检查点套到新清单。`Cleanup` 可读取正式 Import 检查点并逐任务调用原有精确清理接口。

全量编排只保证所有选定 JSON 都被提交一次或幂等重放，不会绕过 `NEEDS_REVIEW`、外部图片白名单或字段校验。正式创建数量以 `importedCount` 为准；其余项目仍按 T09B 流程人工修正或确认跳过。

## 测试与实际结果

- 最终完整 Java `clean verify` 的 8 个 Reactor 模块通过：共 71 项测试，67 通过、4 个真实环境用例默认跳过；media-service 共 19 项，17 通过、2 个真实环境用例默认跳过。受控真实图片用例另在显式启用真实环境后 1 项通过。
- 专用 MySQL 验收通过：`edu_platform_learning_dev` 上 2 项测试通过，包含 V1/V2 校验、1 份真实文件 dry-run、1 份真实文件实际导入和精确清理；终端没有输出文件路径、题干、答案或 JSON。
- 自动化只提交少量虚构 Fixture，覆盖四种题型、显式子题顺序、编号父答案、空解答、HTML 清洗、LaTeX、图片转存幂等、SSRF 拒绝和任务清理。
- OpenAPI 生成和全 workspace 类型检查通过；Vitest 在沙箱内因既有 `esbuild spawn EPERM` 受阻，经批准在沙箱外复跑后 13 个文件 91 项全通过，三端生产构建通过。两个 PowerShell 脚本语法解析通过；统计脚本以 3 文件上限验证只输出聚合计数。

### 2026-08-12 真实外部图片受控 E2E

- 前置条件：通过本地凭据包装脚本确认 `edu_platform_learning_dev`、`edu_platform_media_dev`、MySQL、RabbitMQ 和阿里云 OSS 配置可用；凭据、Bucket、Object Key、签名 URL 均未输出。只程序化选择并读取 1 份 `fillin` 真实 JSON，其中恰有 1 个 HTTPS 图片引用；没有复制或修改原文件，也没有输出路径、题干、答案、JSON 或完整来源 URL。
- 白名单：本轮 media-service 只允许精确主机 `v3.rawstonedu.net`，禁止重定向，没有放开任意 HTTPS 域名。Java 安全下载器实际完成 HTTPS 下载、MIME/图片魔数和大小校验并写入真实 OSS。
- 链路：请求经真实 Gateway 路由到 learning-service，再调用 media-service；生成 1 个 `QUESTION_IMAGE`、状态 `READY` 的媒体资产。Gateway 投影和 learning 表只含稳定 `mediaId`，不含外部 URL、Bucket、Object Key 或签名 URL；OSS `HEAD` 与短期签名读取成功且内容摘要一致。
- 幂等与拒绝：相同确定性请求重放返回同一 `mediaId`，媒体资产和上传会话仍各 1 行、OSS 对象未重复创建；`dry-run` 创建媒体/上传/审计记录均为 0，未生成对象或临时下载文件；对白名单外 `example.com` 的请求返回 422 `MEDIA.EXTERNAL_IMAGE_INVALID`，且没有新增上传会话。
- 事件与清理：真实 RabbitMQ 完成 `MediaReferenceBound`、`MediaReferenceReleased` 发布和幂等消费，引用状态依次为 `BOUND`、`RELEASED`。验收按精确 `importTaskId`、`questionId`、`mediaId`、`uploadId`、事件 ID 和 OSS Object Key 逆序清理；Object Key 删除后 `HEAD` 失败，6 个相关持久队列消息数均为 0，本机外部图片临时文件增量为 0。
- 聚合清理证据：`mysqlRemainingRows=0`、`ossObjectDeleted=true`、`rabbitQueuesEmpty=true`、`temporaryFilesRemaining=0`；最终复跑标识仅记录 16 位 SHA-256 前缀：任务 `ea90ccf387d38207`、题目 `be3404bf0b8b4e7b`、媒体 `01afe8b6a742604e`、上传 `aacd4baa5800f90e`、对象键 `64bf9dd968cac53b`。这些哈希只用于关联本轮验收日志，不能用于定位或删除其他数据。

该用例默认跳过，必须经人工审核单文件和唯一主机后显式运行；以下变量均在凭据包装脚本的进程内设置，不写入仓库或 `local.env`：

```powershell
$env:T09A_EXTERNAL_IMAGE_ACCEPTANCE = 'true'
$env:LEGACY_QUESTION_ROOT = '<只读历史题库根目录>'
$env:T09A_EXTERNAL_IMAGE_FILE = '<根目录内已审核的单个 JSON 绝对路径>'
$env:T09A_EXTERNAL_IMAGE_HOST = '<从该单文件审核出的唯一 HTTPS 主机>'
mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl media-service -am `
  '-Dtest=T09AExternalImageRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试拒绝非 `edu_platform_learning_dev`/`edu_platform_media_dev` Schema、通配或多主机配置；正常和异常路径都只按本轮随机 owner 与捕获的精确 ID 清理，禁止用它做全量迁移。

## 伪数据与清理/重新生成

未创建任何数据库。自动化 Fixture 只存在于随机 H2 内存库，进程结束自动释放；重复运行测试即可重新生成。真实验收复用用途明确的 `edu_platform_learning_dev` 与 `edu_platform_media_dev`，既有迁移表保留；本轮临时创建的题目、来源引用、批次、问题、Outbox、任务、媒体资产、上传会话、媒体引用、审计、RabbitMQ 消息和唯一 OSS 对象均按精确 ID/Object Key 清理。本轮没有 Seed 或持久伪数据，验收后两 Schema、本机临时目录和相关队列无本轮残留。

## 人工演示步骤

1. 启动 Gateway、learning-service 和 media-service，确认教师身份链路可用；仅在需要实际图片时审核并配置媒体主机白名单。
2. 执行不超过 5 项的 dry-run，检查 `valid/review/skipped/failed` 聚合统计。
3. 在教师 API 中检查复核问题码；不要以题干启发式手工批量放行。
4. 经人工确认后执行同规模 Import，验证普通题或组合题子题顺序、原始难度和来源投影。
5. 使用返回的任务 ID 执行 Cleanup，并确认删除/释放计数；不得清空 Schema 或扫描删除其他题目。

## 已知风险与后续依赖

- 44,441 份全量迁移未执行，也未获得授权；吞吐、站点限流和来源质量分布仍需另行迁移计划。
- 真实外部站点图片到真实 OSS 的 E2E 已只对 1 份文件、1 张图片和唯一主机执行；它不构成对整个来源站点的下载授权，也不证明 28,646 份含图文件均可迁移。全量迁移前仍须逐来源确认合法下载权限、最小白名单、限流、内容授权和失败恢复方案；不得用本次主机值扩展为通配规则。
- `NEEDS_REVIEW` 的人工工作台属于 T10；本任务只提供后端记录与投影。
- 组合题在 T11 前仅作为题库结构存在，不代表已支持组卷、作答或判分。

## 分支、PR 与关键提交

- 分支：`codex/t09a-legacy-json-import`
- 基线：`main@71bd7d85`
- PR：[#19](https://github.com/chengchen-ccnu/ccnu-edulion/pull/19) 已于 2026-08-12 合并到 `main`，合并提交为 `8295f46768c8ea77eae617b266e85f06d2445477`。
- 关键实现提交：`ac69319`（`feat(question): add controlled legacy JSON migration`）。

## 人工可读性检查

解析器、导入事务、幂等/重试/清理、媒体下载安全边界、外部转存和事件发布均补充了通俗中文 Javadoc；OpenAPI、事件 Schema、Flyway SQL 和配置对状态、字段、失败语义与安全风险有说明。自动生成 TypeScript 未手工添加注释。
