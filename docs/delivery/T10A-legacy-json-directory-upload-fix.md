# T10A 历史 JSON 目录上传修复交付说明

## 任务目标与功能边界

本任务修复教师端 `/teacher/question-imports` 的历史 JSON 目录选择：浏览器不再用完整相对路径生成 `externalId`，并能从不同选择层级识别教材版本和年级。经用户随后明确授权，同一修复任务扩展只读 PowerShell 迁移脚本，使其可以在不提高每批 20、每任务 1000 上限的前提下编排全部来源文件。任务创建和批次 HTTP 失败诊断仅保留状态码、统一错误 `code`、`traceId` 和 `fieldErrors` 的字段/原因。修复不改变页面路由、Java 领域行为，也不修改原题库文件。

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

本任务复用既有 `POST /api/v1/teacher/legacy-question-imports`、批次、复核查询和精确清理接口，只澄清 OpenAPI 对“单任务上限与显式多任务编排”的说明；没有修改字段、事件 Schema、数据库迁移、表、环境变量或启动方式。全量 DryRun 经 Gateway 创建了 45 个持久化导入任务及对应复核记录，但 `dryRun=true`，没有创建正式题目、媒体、OSS 对象或导入来源引用；这些 DryRun 任务与复核记录按本轮要求保留，不执行清理。

为定位目录适配问题，只读检查了用户明确指定的 1 份 JSON；为复现 Windows PowerShell 编码问题，只读取稳定排序后的前 3 份 JSON 并仅输出字节数、类型和属性数量等结构统计。文件均未被复制、修改或提交；没有批量读取 44,441 份题库正文。自动化测试仅使用临时合成 JSON。

## 测试命令与实际结果

- `pnpm.cmd --dir web-apps run test -- apps/teacher-web/src/legacy-import.test.ts`：由于当前脚本参数传递方式，实际执行完整 Vitest；18 个测试文件、121 项全部通过，其中历史导入专项 8 项通过。
- `pnpm.cmd --dir web-apps --filter @edu/teacher-web run typecheck`：通过。
- `pnpm.cmd --dir web-apps run typecheck`：OpenAPI 客户端重新生成且 8 个 workspace 类型检查通过，无生成漂移。
- `pnpm.cmd --dir web-apps run build`：沙箱内 Vite/esbuild 因 `spawn EPERM` 失败；获批在沙箱外复跑后管理端、学生端、教师端三个生产构建全部通过。仅保留既有大 chunk 警告。
- Windows PowerShell 5.1 + 本地 HTTP Fixture：5 个临时合成文件按 `TaskSize=2` 自动拆成 3 个任务，dry-run 处理 5/5，正式导入 5/5，完成态重跑不重复处理，按 Import 检查点清理 3 个任务；检查点不含虚构令牌、绝对来源根或题目正文。
- 断点恢复 Fixture：在第 2 个批次注入一次 503，首次运行保留 1 个已确认批次且不推进源索引；完全相同命令重跑后恢复并完成 5/5、共 3 个任务。另以 `TaskSize=3` 验证恢复后完成 5/5、共 2 个任务。
- `pwsh` 中执行 Pester 脚本测试：7/7 通过。覆盖统一 401/422、非 JSON/非统一响应、Token/JWT/路径/题目文本/原始 JSON 脱敏，真实本地 TCP HTTP 任务创建/批次失败下仅四类诊断字段和原检查点保留，以及创建/批次请求显式使用 UTF-8 无 BOM 字节。
- Windows PowerShell 5.1 + 本地 TCP 捕获：以稳定排序后的前 3 份来源构造批次，请求字节可被严格 UTF-8 解码、可解析为 3 个对象、第 3 个对象保留 17 个属性，且没有 BOM；未向真实 API 提交。
- Windows PowerShell 5.1 实际 dot-source 新诊断函数：解析成功，保持原迁移命令的运行兼容性。
- 真实全量 DryRun：只读来源清单共 44,441 项，按每任务最多 1,000 项完成 45 个任务；最终 `processed=44,441`、`VALID=29,127`、`NEEDS_REVIEW=15,314`、`FAILED=0`、`SKIPPED=0`、`imported=0`。检查点 `completed=true`、无活动任务，并确认不含 Token、题目字段或绝对路径。此结果只证明预检完成，不代表正式题目已创建。
- `git diff --check`：通过。

测试覆盖从完整“题库”根目录、教材版本目录、年级目录和通用三层目录选择文件；覆盖嵌入 `exerciseID` 优先、缺失时文件名回退、超长 ID 拒绝、相对路径不泄漏绝对路径，以及既有批次/任务上限。

## 人工演示步骤

1. 启动 Gateway、IAM、learning-service 和教师端，使用具备 `teaching:access` 的账号进入 `/teacher/question-imports`。
2. 保持 dry-run，点击“选择目录”，选择历史题库的教材版本目录或年级目录；首次只保留不超过 5 个显式样本。
3. 创建任务并确认请求不再因 `externalId` 与 `exerciseID` 不一致返回 422。
4. 在任务详情区分 `VALID` 与数据本身导致的 `NEEDS_REVIEW`；目录/JSON 年级真实冲突、内嵌选项歧义或图片问题仍不得自动放行。
5. 只有 dry-run 结果经人工确认后才能执行实际导入；含图题仍要求 media-service 外部图片精确主机白名单。

本轮已通过真实 Gateway 完成全量 DryRun，但未执行正式 Import。上述浏览器步骤仍可用于抽查复核页面；不得把 DryRun 的 `VALID` 数描述为已创建题目数。

## 注释、风险与后续依赖

新增 ID 和目录解析函数均使用中文 TSDoc 说明输入、输出、回退和失败语义。自动生成代码未手工修改。

新增 PowerShell 诊断和批次敏感值收集函数均注明输入、输出、内存边界和脱敏目的。安全诊断不保存也不返回完整响应；当后端未返回统一错误体时，`code/traceId/fieldErrors` 为空，需使用 HTTP 状态码和服务端日志中的安全 Trace 进一步排查，不会为了诊断而回显异常全文。

浏览器修复只解决目录选择的 ID/路径适配；脚本全量模式只解决任务编排和恢复。历史数据中真实存在的目录与 JSON 年级冲突、重复或缺损选项、外部图片转存失败仍会进入既有人工复核，不能自动放行；`AllItems` 的“全部”指全部提交处理，实际创建以 `importedCount` 为准。全量执行可能超过短期令牌寿命，需更新当前进程令牌后续传；运行期间必须冻结来源目录。T11 后端不依赖本修复，T18 发布验收必须包含目录 dry-run 和全量检查点恢复回归。

## 分支与发布

- 分支：`codex/t10a-legacy-json-directory-upload-fix`
- 基线：`main@0abc39c`
- PR：待创建与验收
