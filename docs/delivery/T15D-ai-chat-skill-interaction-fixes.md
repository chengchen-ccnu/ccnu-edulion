# T15D AI 会话与 Skill 交互修复交付说明

## 目标与边界

本任务修复独立 AI 页面和普通自主练习题悬浮窗的六类人工验收问题：停止按钮位置、会话重命名/用户删除、空自主练习卡、组题意图漏判、“＋”菜单与题图粘贴、以及讲解后先建议再确认调用 Skill。没有进入课程播放器、HOMEWORK/EXAM、教师/管理端，没有开放任意 Skill 名、在线安装或 Skill 商店，也没有创建第二套上传、取消或对话状态机。

Java 仍是授权、会话事实与写操作边界；Python 不连平台 MySQL、不接 OSS。浏览器只提交有限公开能力，不能传内部 Skill 名、脚本路径或工具授权。复杂状态转换、权限/幂等、上传清理、SSE 清理和 Skill 选择代码均补有通俗中文 Javadoc/docstring/TSDoc；生成客户端没有手工添加注释。

## 契约、迁移与调用流程

实施顺序为外部契约、内部契约、生成客户端、Java/Python/前端：

1. `docs/api/openapi.yaml` 由 0.23.4 升到 0.24.0。新增 `PATCH/DELETE /api/v1/ai/conversations/{conversationId}`，改名携带 `requestId/title/expectedRevision`，归档携带 `requestId/expectedRevision`；发送消息新增可选有限枚举 `selectedCapability=GENERATE_AUTONOMOUS_PRACTICE`，终态可带安全 `skillOffer`。
2. `docs/api/agent-internal.yaml` 由 1.5.0 升到 1.6.0。Java 仅把公开枚举映射为受信 `requestedSkill.skillName=public-practice-generation`；相邻上一条服务端 Offer 作为 `activeSkillOffer` 传入。Python 终态 Offer 中的内部字段由 Java 校验并投影为公开能力。
3. Agent Access Flyway V8 新增 `ai_conversation_command`。现有 `ai_conversation.create_request_id` 只覆盖创建，不能恢复改名/归档响应或识别同 requestId 不同载荷；因此用最小命令事实表保存命令类型、指纹和安全响应。没有修改 V1～V7，也没有新增会话状态字段。
4. 政名先锁定本人 ACTIVE 会话，再以 revision CAS 更新；标题 trim 后为 1～80 字符且禁止控制字符。同 requestId 同载荷重放原响应，不同载荷为 409。归档遇到 ACTIVE run 返回 409，成功只写既有 `ARCHIVED`；列表、详情、消息、run、Action、恢复和继续发送都要求 owner+ACTIVE，越权、归档和不存在统一 404。
5. Python 高精度组题规则先于普通数学标记，提取主题、1～20 数量和难度；明确否定、讨论“如何出题”和引用他人话语不触发，模糊情形沿用最多一次结构化分类，失败回普通专家回答。
6. 完成讲解且当前轮存在明确困难证据时，只生成单轮 `skillOffer` 和询问文本。Offer 阶段不发现/执行 Skill、不查询 Qdrant、不创建 Action；只有紧邻下一轮、服务端仍提供该 Offer且用户明确同意时，才按显式路径选择 Skill。拒绝、改题或错过一轮即失效。

## 页面行为

- 独立页生成期间，输入框旁原发送按钮变为“停止生成”，停止或终态恢复“发送”；仍调用既有 cancel，并保存部分回答及“回答已中断”。流 reader、abort listener、路由离开和组件卸载均清理。
- 最近会话右侧三点菜单只含重命名和删除。409 时保留标题输入并提供重载 revision；删除使用二次确认，成功立即移出列表，原深链显示安全 404。
- `PracticeSuggestionCard` 的 DOM 根节点要求真实 Action；404/空结果完全无标题、边框或等待占位。终态 Action 仍按所属消息及恢复查询展示。
- 两个输入区共用 `AiInputMenu`。菜单只显示“添加题图”和“生成自主练习”，支持方向键、Home/End、Tab、Enter/Space、Escape 与焦点恢复。显式选择以 chip 显示，可取消；只在消息被服务端接受后清除，切换/新建会话也恢复自动。
- 文件选择和剪贴板图片共用原 `AI_QUESTION_IMAGE` 上传；只接受契约已有 JPG/JPEG、PNG、WebP 与大小限制。文本粘贴不 `preventDefault`，上传错误、取消和卸载清理 AbortController 与临时 URL。

## 环境与启动

没有新增环境变量或秘密，也没有修改个人 `local.env`。联调继续使用 `scripts/Invoke-WithLocalEnvironment.ps1` 注入既有 DPAPI 凭据。新增 `scripts/Invoke-T15DBrowserEnvironment.ps1` 仅用于本地启动/停止七个既有服务，拒绝占用端口并记录它自己启动的 PID；日志写入忽略目录 `.m2/t15d-browser`。

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
  & .\scripts\Invoke-T15DBrowserEnvironment.ps1 -Action START
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
  & .\scripts\Invoke-T15DBrowserEnvironment.ps1 -Action STOP
}
```

真实题图成功直传仍要求既有 OSS/MinIO 可达；该依赖未运行时页面会取消上传并显示失败，不会生成第二套本地上传。任务结束无需从 `local.env` 删除字段；若开发者另行写入过明文模型 Key/服务 Token，仍应按仓库规则删除并迁回 DPAPI/部署 Secret。

## 实际验证结果

- Python：`compileall` 通过；`python -m unittest discover -s tests -v` 为 90 项通过、10 项按真实模型/索引/Windows 符号链接条件跳过。覆盖用户原句、近义/否定/讨论/引用、普通解题、数量边界、显式选择、Offer 同意/拒绝/改题/过期，以及 Skill 安全边界。
- Java：Agent Access 受影响模块 54 项通过、3 项按真实环境跳过；真实 MySQL `AgentMemoryMysqlAcceptanceTest` 2/2 通过，确认 V1～V8；最终全仓 `mvn ... clean verify` 9 个模块全部成功，耗时 3:07。
- 前端：`generate:api`、typecheck 通过；53 个测试文件、346 项测试通过；admin/student/teacher 三个生产构建通过，仅有既有大 chunk 警告。覆盖无 Action DOM、Stop、重复取消、断线/卸载、菜单键盘操作、单轮选择、会话 409、删除、粘贴图片与文本。
- OpenAPI：生成器可解析 0.24.0 并重复生成稳定；提交后执行 `check:api` 确认生成客户端与契约/HEAD 一致。

真实浏览器使用固定合成学生、真实 Gateway/Java/Python/前端完成：输入区 Stop 与中断保存；原用户组题句；重命名；删除确认、列表移除及归档深链 404；独立页和悬浮窗“＋”菜单；方向键/Enter；单轮选择 chip；没有 Action 时 DOM 中无卡；文本粘贴；PNG 粘贴进入原上传链；刷新/返回；390px 悬浮窗布局。验收还定位并修复 Gateway PATCH CORS 和困难短语“不明白”漏判。

两项未写成真实页面通过：本地对象存储端点未启动，PNG 直传进入预期取消/离线态，未验证成功上传；Offer 真实长回答两次在浏览器长连接中断，页面均保存“回答已中断”，未在真实页面继续完成同意/拒绝/转移/过期四分支。这些分支的服务端与 Vue 自动化测试通过，仍保留为 PR 人工复核项。

## 数据、风险与回退

只在既有专用 `edu_platform_agent_dev` 保留 V8 表结构和 Flyway 历史，没有新建数据库、账号、课程、题目、正式媒体、OSS 对象、Qdrant/Neo4j 点或永久 Seed。真实浏览器创建的 3 个会话、11 条消息、6 个 run、1 个 Action 及从属事件/命令/Skill/用量事实已按精确 ID删除并复核为 0；一个无 mediaId 的 CANCELED 上传会话及审计、9 条刷新会话和 2 条登录审计同样精确删除为 0。固定测试账号和既有题库保留，验收端口全部停止。重新验收会创建新的随机会话/requestId，不复用已清理数据。

主要风险是生产 OSS 可达性、真实模型响应时间和现有 student/teacher 大 chunk；都不是本次扩展的安全绕过。回退应用版本即可停止消费新可选字段；OpenAPI/内部契约为向前可选扩展，旧客户端仍可发送普通消息。数据库 V8 不应回写或删除，旧应用不读取该表；ARCHIVED 会话及历史事实按原保留策略继续存在。

分支：`codex/t15d-ai-chat-skill-interaction-fixes`。规划提交 `fd036dd`、`8ce001a` 已保留，实现提交为 `6cdbe2b`；[PR #72](https://github.com/chengchen-ccnu/ccnu-edulion/pull/72) 目标为 `main`，保持 Open 且不由本任务自行合并。最终 CI 状态在 PR 检查完成后补记。
