# T09A 历史 JSON 题库兼容与迁移交付说明

## 任务目标与边界

本任务在已合并的 T09 CSV/XLSX 题库能力之外，增加对只读历史逐题 JSON 的受控迁移。实现不会复制、修改或提交原题库，不提供自动全量入口；每次任务最多 1000 项、每批最多 20 项，仓库脚本默认 dry-run 且并发固定为 1。T10 页面、T11 组卷/作答和 Python 智能体均不在范围内。

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

人工检查聚合计数和复核问题后，才可把 `DryRun` 改为 `Import`；本任务不授权扩大 `MaxItems` 做全量迁移。记录脚本返回的 `importTaskId`，验收完成后精确清理：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Cleanup -ImportTaskId '<task-uuid>'
```

清理只处理该任务创建且仍为 DRAFT 的题目；若题目已人工发布或修改，接口会拒绝静默删除，需要人工确认后另行处理。dry-run 不创建题目或媒体。

## 测试与实际结果

- 完整 Java `clean verify` 的 8 个 Reactor 模块通过：共 70 项测试，67 通过、3 个真实环境用例默认跳过。最终变更后定向复跑 gateway 11 项、learning 17 项和 media 18 项，均无失败。
- 专用 MySQL 验收通过：`edu_platform_learning_dev` 上 2 项测试通过，包含 V1/V2 校验、1 份真实文件 dry-run、1 份真实文件实际导入和精确清理；终端没有输出文件路径、题干、答案或 JSON。
- 自动化只提交少量虚构 Fixture，覆盖四种题型、显式子题顺序、编号父答案、空解答、HTML 清洗、LaTeX、图片转存幂等、SSRF 拒绝和任务清理。
- OpenAPI 生成和全 workspace 类型检查通过；Vitest 在沙箱内因既有 `esbuild spawn EPERM` 受阻，经批准在沙箱外复跑后 13 个文件 91 项全通过，三端生产构建通过。两个 PowerShell 脚本语法解析通过；统计脚本以 3 文件上限验证只输出聚合计数。

## 伪数据与清理/重新生成

未创建任何数据库。自动化 Fixture 只存在于随机 H2 内存库，进程结束自动释放；重复运行测试即可重新生成。真实验收复用用途明确的 `edu_platform_learning_dev`，V2 表和列作为迁移结果保留；本轮随机题目、来源引用、批次、问题、Outbox 和任务记录均按精确 ID 清理。没有修改 media 开发库、OSS 或 RabbitMQ。

## 人工演示步骤

1. 启动 Gateway、learning-service 和 media-service，确认教师身份链路可用；仅在需要实际图片时审核并配置媒体主机白名单。
2. 执行不超过 5 项的 dry-run，检查 `valid/review/skipped/failed` 聚合统计。
3. 在教师 API 中检查复核问题码；不要以题干启发式手工批量放行。
4. 经人工确认后执行同规模 Import，验证普通题或组合题子题顺序、原始难度和来源投影。
5. 使用返回的任务 ID 执行 Cleanup，并确认删除/释放计数；不得清空 Schema 或扫描删除其他题目。

## 已知风险与后续依赖

- 44,441 份全量迁移未执行，也未获得授权；吞吐、站点限流和来源质量分布仍需另行迁移计划。
- 真实外部站点图片到真实 OSS 的 E2E 未执行；当前证据为虚构下载器集成测试和 HTTP 安全单元测试。正式启用前需人工维护最小主机白名单并确认来源授权。
- `NEEDS_REVIEW` 的人工工作台属于 T10；本任务只提供后端记录与投影。
- 组合题在 T11 前仅作为题库结构存在，不代表已支持组卷、作答或判分。

## 分支、PR 与关键提交

- 分支：`codex/t09a-legacy-json-import`
- 基线：`main@71bd7d85`
- Ready PR：[#19](https://github.com/chengchen-ccnu/ccnu-edulion/pull/19)，Open、非 Draft；不得自行合并。
- 关键实现提交：`ac69319`（`feat(question): add controlled legacy JSON migration`）。

## 人工可读性检查

解析器、导入事务、幂等/重试/清理、媒体下载安全边界、外部转存和事件发布均补充了通俗中文 Javadoc；OpenAPI、事件 Schema、Flyway SQL 和配置对状态、字段、失败语义与安全风险有说明。自动生成 TypeScript 未手工添加注释。
