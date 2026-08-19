# T10A 历史 JSON 目录上传修复交付说明

## 任务目标与功能边界

本任务修复教师端 `/teacher/question-imports` 的历史 JSON 目录选择：浏览器不再用完整相对路径生成 `externalId`，并能从不同选择层级识别教材版本和年级。经用户随后明确授权，同一修复任务扩展只读 PowerShell 迁移脚本，使其可以在不提高每批 20、每任务 1000 上限的前提下编排全部来源文件，并增加通过 Gateway 汇总已完成 DryRun 持久化结果的安全工具。任务创建和批次 HTTP 失败诊断仅保留状态码、统一错误 `code`、`traceId` 和 `fieldErrors` 的字段/原因。修复不改变页面路由、Java 领域行为，也不修改原题库文件。

## 根因与实现

原实现把 `webkitRelativePath` 去掉 `.json` 后作为 `externalId`。目录选择会得到 `题库/北师大版/北师大版八年级上/text/<文件名>`，而 learning-service 要求 `externalId` 与 JSON 内 `exerciseID` 相等，因此批次在内容解析前返回 422。

修复后的流程为：

1. 浏览器只读取教师显式选择且不超过 256 KiB 的单题 JSON 对象。
2. 优先把 JSON 内 `exerciseID` 原样作为 `externalId`；字段缺失时才使用不含扩展名的文件名。超过 160 字符、前后带空白或为空的 ID 在客户端明确拒绝，不通过截断制造不一致。
3. 相对路径仍规范化并提交，不读取或提交客户端绝对路径。从路径所有目录段中识别包含“七/八/九年级”的年级目录，并优先取其前方含“版”的教材目录；如果版本和年级合并在同一目录名，则保留该目录原文。
4. 兼容既有合成的“版本/年级/文件”三层来源；仅选择单文件且无法恢复目录时继续提交 `UNKNOWN`，由既有人工复核流程处理。
5. 页面为不合法 `exerciseID` 和相对路径提供可理解的中文错误，不继续提交后续批次。

脚本全量模式额外执行以下流程：稳定排序并生成只含相对路径/文件元数据的清单指纹；显式 `AllItems` 后按 `TaskSize` 切成多个任务；正式导入还必须提供 `ConfirmFullMigration`；任务和批次 UUID 由运行指纹/序号确定；每个确认批次后原子写本机检查点，失败时停止并支持相同命令续传。检查点位于来源目录之外，且不含令牌、绝对源路径或题目内容。`Cleanup` 可以读取 Import 检查点逐任务调用既有精确删除端点。

安全诊断将 HTTP 响应视为不可信输入：不读取到统一错误结构时只输出可用状态码；识别成功时也忽略 `message/path/timestamp` 和额外字段。`code`、`traceId` 和字段路径经格式白名单验证，字段原因再遮蔽当前进程 Token、Bearer/JWT、绝对路径、题干、答案、解析和结构化原始 JSON。失败 catch 不写检查点、不清空 `activeTask`、不调用清理端点；相同命令仍从原任务和批次续传。

真实全量 dry-run 随后暴露 Windows PowerShell 5.1 的请求编码差异：将 JSON 字符串直接交给 `Invoke-RestMethod` 时会按本机 ANSI 代码页发送，中文可能被替换，也可能产生后端无法按 UTF-8 解码的字节，最终以 `items[n].json` 的 422 表现出来。任务创建体和批次体现在统一转为 UTF-8 无 BOM 字节后再发送，不改变 API 字段、任务或检查点语义。

## API、数据库、环境与数据

本任务复用既有 `POST /api/v1/teacher/legacy-question-imports`、批次、复核查询和精确清理接口，只澄清 OpenAPI 对“单任务上限与显式多任务编排”的说明；没有修改字段、事件 Schema、数据库迁移、表或长期环境变量。安全汇总脚本从检查点内存读取任务 ID，经 Gateway 调用 `GET /api/v1/teacher/question-imports/{importTaskId}` 和分页 `GET /api/v1/teacher/question-imports/{importTaskId}/items`，只输出聚合结果，不查询数据库、不返回完整 ID 或复核投影。

全量 DryRun 经 Gateway 创建了 45 个持久化导入任务及支撑复核投影的记录，但 `dryRun=true`，没有创建正式题目、媒体或 OSS 对象；这些 DryRun 任务、复核记录和检查点按本轮要求保留，不执行清理。汇总认证只接受当前进程 `EDU_MIGRATION_ACCESS_TOKEN`；首次运行被 Windows 执行策略阻止，随后仅对当前 PowerShell 进程使用 `ExecutionPolicy Bypass`，进程退出即失效，没有保存账号、密码或 Token。

为定位目录适配问题，只读检查了用户明确指定的 1 份 JSON；为复现 Windows PowerShell 编码问题，只读取稳定排序后的前 3 份 JSON 并仅输出字节数、类型和属性数量等结构统计。文件均未被复制、修改或提交；没有批量读取 44,441 份题库正文。自动化测试仅使用临时合成 JSON。

## 测试命令与实际结果

- `pnpm.cmd --dir web-apps run test -- apps/teacher-web/src/legacy-import.test.ts`：由于当前脚本参数传递方式，实际执行完整 Vitest；18 个测试文件、121 项全部通过，其中历史导入专项 8 项通过。
- `pnpm.cmd --dir web-apps --filter @edu/teacher-web run typecheck`：通过。
- `pnpm.cmd --dir web-apps run typecheck`：OpenAPI 客户端重新生成且 8 个 workspace 类型检查通过，无生成漂移。
- `pnpm.cmd --dir web-apps run build`：沙箱内 Vite/esbuild 因 `spawn EPERM` 失败；获批在沙箱外复跑后管理端、学生端、教师端三个生产构建全部通过。仅保留既有大 chunk 警告。
- Windows PowerShell 5.1 + 本地 HTTP Fixture：5 个临时合成文件按 `TaskSize=2` 自动拆成 3 个任务，dry-run 处理 5/5，正式导入 5/5，完成态重跑不重复处理，按 Import 检查点清理 3 个任务；检查点不含虚构令牌、绝对来源根或题目正文。
- 断点恢复 Fixture：在第 2 个批次注入一次 503，首次运行保留 1 个已确认批次且不推进源索引；完全相同命令重跑后恢复并完成 5/5、共 3 个任务。另以 `TaskSize=3` 验证恢复后完成 5/5、共 2 个任务。
- `pwsh` 中执行两组 Pester 脚本测试：13/13 通过。其中迁移诊断与编码 7 项，安全汇总 6 项；汇总覆盖多任务分页、计数、issueCode 组合、401、422、空任务、中途 503、无部分结果、敏感投影不泄漏，以及只调用 Gateway GET、不遍历题库、不写检查点或调用清理端点。
- Windows PowerShell 5.1 + 本地 TCP 捕获：以稳定排序后的前 3 份来源构造批次，请求字节可被严格 UTF-8 解码、可解析为 3 个对象、第 3 个对象保留 17 个属性，且没有 BOM；未向真实 API 提交。
- Windows PowerShell 5.1 实际 dot-source 新诊断函数：解析成功，保持原迁移命令的运行兼容性。
- Windows PowerShell 5.1 语法检查：迁移、诊断、汇总及两组测试共 5 个脚本全部通过；汇总脚本保留 UTF-8 BOM 以兼容 5.1 对中文脚本的读取。
- 真实全量 DryRun：只读来源清单共 44,441 项，按每任务最多 1,000 项完成 45 个任务；最终 `processed=44,441`、`VALID=29,127`、`NEEDS_REVIEW=15,314`、`FAILED=0`、`SKIPPED=0`、`imported=0`。检查点 `completed=true`、无活动任务，并确认不含 Token、题目字段或绝对路径。此结果只证明预检完成，不代表正式题目已创建。
- 真实安全汇总：在短期 Token 当前进程内经 Gateway 只读完成，17 种 issueCode 组合合计恰为 15,314，和 NEEDS_REVIEW 总数一致；执行完成后 Token 已从进程环境移除。
- `git diff --check`：通过。

测试覆盖从完整“题库”根目录、教材版本目录、年级目录和通用三层目录选择文件；覆盖嵌入 `exerciseID` 优先、缺失时文件名回退、超长 ID 拒绝、相对路径不泄漏绝对路径，以及既有批次/任务上限。

## DryRun 安全汇总结果

outcome 以 44,441 条为分母：

| outcome | 数量 | 比例 |
|---|---:|---:|
| `VALID` | 29,127 | 65.5408% |
| `NEEDS_REVIEW` | 15,314 | 34.4592% |
| `FAILED` | 0 | 0% |
| `SKIPPED` | 0 | 0% |

单个 issueCode 的比例同样以全部 44,441 条为分母；“占复核项”以 15,314 条 NEEDS_REVIEW 为分母。一个复核项可以同时出现多个问题码，因此问题码次数合计为 16,676，不能直接相加为复核题数。

| issueCode | 出现次数 | 占全部 | 占复核项 |
|---|---:|---:|---:|
| `PATH_JSON_METADATA_CONFLICT` | 5,793 | 13.0353% | 37.8281% |
| `SUBQUESTION_ANSWER_MISSING` | 4,827 | 10.8616% | 31.5202% |
| `IMPLICIT_MULTI_PART` | 3,375 | 7.5943% | 22.0387% |
| `EMBEDDED_OPTIONS_AMBIGUOUS` | 2,471 | 5.5602% | 16.1356% |
| `FIELD_INVALID` | 173 | 0.3893% | 1.1297% |
| `HTML_UNSAFE` | 37 | 0.0833% | 0.2416% |

问题码组合完整分布如下；单问题码 13,954 条（复核项的 91.1192%），多问题码 1,360 条（8.8808%）。

| issueCode 组合 | 数量 | 占全部 |
|---|---:|---:|
| `PATH_JSON_METADATA_CONFLICT` | 4,488 | 10.0988% |
| `SUBQUESTION_ANSWER_MISSING` | 4,056 | 9.1267% |
| `IMPLICIT_MULTI_PART` | 3,141 | 7.0678% |
| `EMBEDDED_OPTIONS_AMBIGUOUS` | 2,128 | 4.7884% |
| `PATH_JSON_METADATA_CONFLICT + SUBQUESTION_ANSWER_MISSING` | 747 | 1.6809% |
| `EMBEDDED_OPTIONS_AMBIGUOUS + PATH_JSON_METADATA_CONFLICT` | 321 | 0.7223% |
| `IMPLICIT_MULTI_PART + PATH_JSON_METADATA_CONFLICT` | 223 | 0.5018% |
| `FIELD_INVALID` | 126 | 0.2835% |
| `FIELD_INVALID + SUBQUESTION_ANSWER_MISSING` | 22 | 0.0495% |
| `EMBEDDED_OPTIONS_AMBIGUOUS + FIELD_INVALID` | 21 | 0.0473% |
| `HTML_UNSAFE` | 15 | 0.0338% |
| `HTML_UNSAFE + IMPLICIT_MULTI_PART` | 10 | 0.0225% |
| `HTML_UNSAFE + PATH_JSON_METADATA_CONFLICT` | 9 | 0.0203% |
| `FIELD_INVALID + PATH_JSON_METADATA_CONFLICT` | 3 | 0.0068% |
| `HTML_UNSAFE + SUBQUESTION_ANSWER_MISSING` | 2 | 0.0045% |
| `EMBEDDED_OPTIONS_AMBIGUOUS + FIELD_INVALID + PATH_JSON_METADATA_CONFLICT` | 1 | 0.0023% |
| `HTML_UNSAFE + IMPLICIT_MULTI_PART + PATH_JSON_METADATA_CONFLICT` | 1 | 0.0023% |

`TYPE_UNSUPPORTED`、`SOURCE_CONTENT_CONFLICT` 和 `EXTERNAL_IMAGE_TRANSFER_FAILED` 本次均为 0。DryRun 不执行外部图片转存，因此图片问题为 0 不能作为正式图片迁移已通过的证据。

## 分类处置与优先级

| 类别 | 当前判断 | 后续规则边界 |
|---|---|---|
| `PATH_JSON_METADATA_CONFLICT` | **部分可确定性适配** | 先通过既有复核投影抽查有界样本，建立明确的教材版本/年级别名表；仅当路径值与 JSON 值规范化后唯一等价时自动通过。真实年级冲突、版本冲突和 `UNKNOWN` 不得用目录值覆盖 JSON 或反向覆盖，仍需人工确认。 |
| `EMBEDDED_OPTIONS_AMBIGUOUS` | **部分可确定性适配** | 只扩展经样本证明的显式选项标记；仍必须满足 A～H 连续、有序、数量一致、每项非空且答案落在选项集合。不得按空格、视觉位置或不连续字母猜拆。 |
| `SUBQUESTION_ANSWER_MISSING` | **默认人工复核** | 当前问题码还可能覆盖子题题型不支持、题干为空、子题 HTML 不安全或子题选项无法拆分。应先契约先行细分原因，再只为父答案存在连续、完整、与子题数严格一致的格式增加适配；无法一一对应时不得补答案。 |
| `IMPLICIT_MULTI_PART` | **必须人工复核** | 仅凭题干出现多个编号不能安全决定父子题边界、题型和答案归属。可提供结构化修正预览，但不得直接拆成组合题。 |
| `FIELD_INVALID` | **默认人工复核** | 先细分缺年级、难度、题干、答案等原因；只有评审通过的闭集别名可映射。不得填默认答案、默认题型或默认难度来提高通过率。 |
| `HTML_UNSAFE` | **必须人工复核** | 保持现有 HTML 白名单和危险结构拦截。即使清洗后有文本，也必须确认被移除内容不改变题义；不得降低标签、属性、脚本、嵌入对象或图片安全校验。 |
| 多问题码组合 | **必须人工复核** | 多问题组合不进入首轮自动规则；必须逐项确认所有问题均已解决，不能因其中一个元数据问题可归一化就放行整题。 |

建议按以下顺序推进，当前尚未执行：

1. **P0：保留基线。** 保留现有 45 个任务、复核记录和已完成检查点；不修改原始 JSON，不执行 Import。
2. **P1：低风险规则。** 先评审有界样本，实施“元数据唯一等价归一化”和“额外显式选项标记”两类确定性规则，并为成功、拒绝和组合情况补 Java 单元/集成测试。单码 `PATH_JSON_METADATA_CONFLICT` 与 `EMBEDDED_OPTIONS_AMBIGUOUS` 共 6,616 条，只是候选上限，不代表可全部自动转为 VALID。
3. **P2：提高诊断精度。** 在 OpenAPI 先行评审后细分 `SUBQUESTION_ANSWER_MISSING` 和 `FIELD_INVALID` 的真实原因，再决定是否存在严格的一一对应答案格式；不以扩大正则为目标。
4. **P3：重新 DryRun。** 解析规则变更后使用新的 `SourceSystem`/独立检查点创建全新 DryRun，避免确定性请求 ID 重放旧结果；旧任务不得删除或重建。再次运行安全汇总，比较 VALID、各 issueCode 和组合变化，并抽查“新转 VALID”项。
5. **P4：人工复核。** 优先处理全部 `HTML_UNSAFE`、`FIELD_INVALID`、多问题码组合和真实元数据冲突；随后处理子题答案、隐式多问和无法严格拆分的选项。只能使用既有结构化修正或确认跳过 API。
6. **决策门禁。** 新 DryRun 和人工抽查经用户确认前，继续禁止 `-Action Import`、`-ConfirmFullMigration`、图片转存和正式题目创建。

## 人工演示步骤

1. 启动 Gateway、IAM、learning-service 和教师端，使用具备 `teaching:access` 的账号进入 `/teacher/question-imports`。
2. 保持 dry-run，点击“选择目录”，选择历史题库的教材版本目录或年级目录；首次只保留不超过 5 个显式样本。
3. 创建任务并确认请求不再因 `externalId` 与 `exerciseID` 不一致返回 422。
4. 在任务详情区分 `VALID` 与数据本身导致的 `NEEDS_REVIEW`；目录/JSON 年级真实冲突、内嵌选项歧义或图片问题仍不得自动放行。
5. 只有 dry-run 结果经人工确认后才能执行实际导入；含图题仍要求 media-service 外部图片精确主机白名单。

本轮已通过真实 Gateway 完成全量 DryRun，但未执行正式 Import。上述浏览器步骤仍可用于抽查复核页面；不得把 DryRun 的 `VALID` 数描述为已创建题目数。

## 注释、风险与后续依赖

新增 ID 和目录解析函数均使用中文 TSDoc 说明输入、输出、回退和失败语义。自动生成代码未手工修改。

新增 PowerShell 诊断、批次敏感值收集和安全汇总函数均注明输入、输出、内存边界和失败语义。安全诊断不保存也不返回完整响应；当后端未返回统一错误体时，`code/traceId/fieldErrors` 为空，需使用 HTTP 状态码和服务端日志中的安全 Trace 进一步排查，不会为了诊断而回显异常全文。汇总脚本仅在全部任务读取成功后输出结果，中途 401/422/503 不输出部分统计。

浏览器修复只解决目录选择的 ID/路径适配；脚本全量模式只解决任务编排和恢复。历史数据中真实存在的目录与 JSON 年级冲突、重复或缺损选项、外部图片转存失败仍会进入既有人工复核，不能自动放行；`AllItems` 的“全部”指全部提交处理，实际创建以 `importedCount` 为准。全量执行可能超过短期令牌寿命，需更新当前进程令牌后续传；运行期间必须冻结来源目录。T11 后端不依赖本修复，T18 发布验收必须包含目录 dry-run 和全量检查点恢复回归。

## 分支与发布

- 分支：`codex/t10a-legacy-json-directory-upload-fix`
- 基线：`main@0abc39c`
- HTTP 安全与 UTF-8 修复提交：`e1dabe6`
- DryRun 安全汇总提交：`a1c98b5`
- PR：待创建与验收
