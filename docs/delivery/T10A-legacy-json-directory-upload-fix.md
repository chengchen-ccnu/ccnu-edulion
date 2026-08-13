# T10A 历史 JSON 目录上传修复交付说明

## 任务目标与功能边界

本任务修复教师端 `/teacher/question-imports` 的历史 JSON 目录选择：浏览器不再用完整相对路径生成 `externalId`，并能从不同选择层级识别教材版本和年级。修复不改变页面路由、OpenAPI、Java 服务、批次上限或全量迁移授权，也不修改原题库文件。

## 根因与实现

原实现把 `webkitRelativePath` 去掉 `.json` 后作为 `externalId`。目录选择会得到 `题库/北师大版/北师大版八年级上/text/<文件名>`，而 learning-service 要求 `externalId` 与 JSON 内 `exerciseID` 相等，因此批次在内容解析前返回 422。

修复后的流程为：

1. 浏览器只读取教师显式选择且不超过 256 KiB 的单题 JSON 对象。
2. 优先把 JSON 内 `exerciseID` 原样作为 `externalId`；字段缺失时才使用不含扩展名的文件名。超过 160 字符、前后带空白或为空的 ID 在客户端明确拒绝，不通过截断制造不一致。
3. 相对路径仍规范化并提交，不读取或提交客户端绝对路径。从路径所有目录段中识别包含“七/八/九年级”的年级目录，并优先取其前方含“版”的教材目录；如果版本和年级合并在同一目录名，则保留该目录原文。
4. 兼容既有合成的“版本/年级/文件”三层来源；仅选择单文件且无法恢复目录时继续提交 `UNKNOWN`，由既有人工复核流程处理。
5. 页面为不合法 `exerciseID` 和相对路径提供可理解的中文错误，不继续提交后续批次。

## API、数据库、环境与数据

本任务复用既有 `POST /api/v1/teacher/legacy-question-imports` 及批次接口，没有修改 OpenAPI、事件 Schema、数据库迁移、表、环境变量或启动方式。没有连接 MySQL、Redis、RabbitMQ、OSS 或外部图片主机，没有创建数据库、表、Seed、题目、导入任务、消息或 OSS 对象，因此没有数据清理步骤。

为定位问题，只读检查了用户明确指定的 1 份 JSON。该文件没有被复制、修改或提交；本任务没有扫描 44,441 份历史题库。自动化测试仅使用内存中的合成 JSON `File`。

## 测试命令与实际结果

- `pnpm.cmd --dir web-apps run test -- apps/teacher-web/src/legacy-import.test.ts`：由于当前脚本参数传递方式，实际执行完整 Vitest；18 个测试文件、121 项全部通过，其中历史导入专项 8 项通过。
- `pnpm.cmd --dir web-apps --filter @edu/teacher-web run typecheck`：通过。
- `pnpm.cmd --dir web-apps run typecheck`：OpenAPI 客户端重新生成且 8 个 workspace 类型检查通过，无生成漂移。
- `pnpm.cmd --dir web-apps run build`：沙箱内 Vite/esbuild 因 `spawn EPERM` 失败；获批在沙箱外复跑后管理端、学生端、教师端三个生产构建全部通过。仅保留既有大 chunk 警告。
- `git diff --check`：通过。

测试覆盖从完整“题库”根目录、教材版本目录、年级目录和通用三层目录选择文件；覆盖嵌入 `exerciseID` 优先、缺失时文件名回退、超长 ID 拒绝、相对路径不泄漏绝对路径，以及既有批次/任务上限。

## 人工演示步骤

1. 启动 Gateway、IAM、learning-service 和教师端，使用具备 `teaching:access` 的账号进入 `/teacher/question-imports`。
2. 保持 dry-run，点击“选择目录”，选择历史题库的教材版本目录或年级目录；首次只保留不超过 5 个显式样本。
3. 创建任务并确认请求不再因 `externalId` 与 `exerciseID` 不一致返回 422。
4. 在任务详情区分 `VALID` 与数据本身导致的 `NEEDS_REVIEW`；目录/JSON 年级真实冲突、内嵌选项歧义或图片问题仍不得自动放行。
5. 只有 dry-run 结果经人工确认后才能执行实际导入；含图题仍要求 media-service 外部图片精确主机白名单。

本轮没有启动真实服务或创建真实导入任务，因此上述 HTTP/浏览器步骤留作合并前人工验收，不能把自动化结果描述为真实数据已导入。

## 注释、风险与后续依赖

新增 ID 和目录解析函数均使用中文 TSDoc 说明输入、输出、回退和失败语义。自动生成代码未手工修改。

修复只解决浏览器目录选择的 ID/路径适配。历史数据中真实存在的目录与 JSON 年级冲突、重复或缺损选项、外部图片转存失败仍会进入既有人工复核；每任务 1000、每批 20 的上限保持不变，全量迁移仍需另行授权。T11 后端不依赖本修复，T18 发布验收必须包含目录 dry-run 的真实浏览器回归。

## 分支与发布

- 分支：`codex/t10a-legacy-json-directory-upload-fix`
- 基线：`main@0abc39c`
- PR：待创建与验收
