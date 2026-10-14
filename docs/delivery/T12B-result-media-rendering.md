# T12B 结果页参考答案与解析图片修复

日期：2026-10-13。分支：`codex/t17c2-account-permissions-web`。实施时保留此前课程封面/孤立报名维护文件，只修改本次结果页相关代码并补交付记录。2026-10-14 按用户提交合并要求归入 [PR #90](https://github.com/chengchen-ccnu/ccnu-edulion/pull/90)，CI 与合并状态以 PR 记录为准。

## 问题与边界

用户通过 AI 创建自主练习，交卷后“参考答案与解析”显示 `[图片1]` 等文字。现场原题为“一元二次方程(x-2)(x+7)=0的根是…”，题目版本 `d3e8b8ed-fa25-41e8-994e-894e29b275c7`，测评 `99553620-e406-43ee-9541-2c99b5933790`，作答 `e4e4aad6-6f10-4975-9b17-8a3b712e7e95`。

只读核对确认：题库原解析与冻结试卷一致，快照保留四个 QUESTION_IMAGE 绑定，Media 四个资产均 READY/SUCCEEDED 且有 BOUND 引用，答案公布状态 PUBLISHED。不是 AI 组卷丢图。直接原因是 `AttemptResultPage.vue` 的参考答案、解析组件只收到 source，没有媒体白名单、旧图片映射与领域授权函数。

本轮仅修复既有结果页图片展示，涵盖自主练习和共享的课程结果路由。不变更页面职责、路由、题库数据、评分规则、AI Runtime、Java 服务、OpenAPI、事件或迁移；不追加第二套 DTO、上传或图片授权链路。

## 实现与调用流程

- `assessment-result-media.ts` 使用生成类型的 Pick，从同一冻结题目和对应结果答案分配旧图片绑定。历史导入按“题干/选项 → 解析 → 每个答案”追加媒体，各区编号可从 1 重启；按最大编号计数，重复引用不重复占位，QUESTION_IMAGE 排序不修改原数组。绑定缺失不补造 URL 或跨版本查图。
- `AttemptResultPage.vue` 为参考答案与解析传入 question.media、各区 legacyMedia、已有 resolveMedia 和 assessmentId 缓存范围，同时恢复结果题干/小问题干的旧图片标记。
- 新 `{{media:UUID}}` 标记仍由共享 SafeAssessmentContent 按当前快照白名单处理。浏览器经 Gateway 调用既有测评媒体授权；Learning 复核本人作答、答案公布事实和冻结媒体归属后，由 Media 签发短期原图授权。共享组件下载后只把 Blob URL 放进 DOM，并在内容变化/离页时中止请求、释放 URL。
- `answerVisible=false` 时仍不创建答案/解析 DOM 或请求其图片；不从评分或状态字段推断权限。授权失败沿用共享组件的分类错误、有限重试、手动重试和有界缓存。

使用既有 API：`GET /api/v1/learning/attempts/{attemptId}/result`、课程任务结果 GET，以及 `POST /api/v1/learning/assessments/{assessmentId}/media/{mediaId}/access-grants`。没有接口/类型变化；全仓 typecheck 已重新生成客户端，生成文件无差异。

## 数据、配置与启动

未创建数据库、表、迁移、伪账号、题目、试卷、作答或媒体文件；仅在已有 `edu_platform_learning_dev` 与 `edu_platform_media_dev` 进行只读定位，并通过学生正常会话验证现有作答。正常登录/刷新与媒体授权可能产生服务已有安全审计/一次性授权消费记录，不属于 Seed 数据；不绕过或清理这些安全事实。

新增测试 Fixture 只存在 Vitest 内存，测试退出即清理，无持久数据需要重新生成或删除。现有学生作答和媒体均保留，无须重新组卷或导入。未改 local.env、DPAPI 凭据或服务开关，无新增环境变量；启动方式仍为学生端 `pnpm.cmd --dir web-apps --filter @edu/student-web run dev` 和既有 Gateway/Learning/Media 服务。没有重启或停止用户正在运行的服务。

## 实际验证

| 命令 | 结果 |
|---|---|
| `pnpm.cmd --dir web-apps run typecheck` | API 生成及八个 workspace 类型检查成功 |
| `pnpm.cmd --dir web-apps run test` | 最终 66 文件、522 测试通过 |
| `pnpm.cmd --dir web-apps --filter @edu/student-web run build` | 成功；保留既有大于 500 kB 的 chunk 提示 |
| `pnpm.cmd --dir web-apps --filter @edu/student-web run typecheck` | 新增错误状态测试后的复查成功 |
| `pnpm.cmd --dir web-apps exec vitest run apps/student-web/src/attempt-result-media.test.ts` | 专项 15 测试通过 |
| `git diff --check` | 成功 |

专项真实挂载结果页，覆盖现场四张旧解析图、题干/解析/多答案重新编号、重复标记、绑定乱序、缺失绑定、新稳定占位、课程结果复用、未公布答案不读取图片、403 单图失败保留其他图并手动恢复，以及 401/404/409/422/429/503/offline 分类失败与有限请求、深链接与 URL 清理。最初切换视图测试在结果重新加载过程中过早断言，失败一项；修正测试等待页面恢复后专项与全量均通过，未修改生产加载逻辑来掩盖失败。

真实浏览器：操作者登录 student1 后，经当前运行的服务打开上述已有作答；整卷 11 张解析图片全部 HTTP 200，complete=true 且 naturalWidth/naturalHeight 非零，无遗留 `[图片N]`。第 2 题四张图恢复，硬刷新与 `view=single&question=2` 深链接恢复正确；Enter 触发题号 3 后可返回题号 2。360/768/1440 宽度均四张图可解码、document 无横向溢出，360 截图人工检查；默认视口截图复核并保存至忽略目录 `.m2/runtime-logs/attempt-result-media-fixed.png`。1440 原生截图捕获失败，只将该宽度 DOM 布局/解码检查作为证据；未宣称该截图成功，临时视口已恢复。

未在共享开发服务制造真实 401/429/离线或破坏 OSS；上述故障为明确 Mock。Java/Python 未修改，不重复运行其构建。新增模块、修改页面职责注释及复杂测试中文注释已人工复核；自动生成文件未手工修改。

## 演示与限制

1. 用 student1 打开上述结果页，选择“逐题查看”→第 2 题。
2. 确认“参考答案与解析”内显示四张公式图片，并刷新页面。
3. 用题号或上一题/下一题切换，确认其他解析图正常；原成绩、答案和作答记录不变。

本轮修复针对冻结快照中已有的图片绑定。原数据缺图或资产已删除时仍显示明确不可用状态，不能从当前题库、外部地址或 AI 生成图中补造历史结果。已公布结果页面不改变填空答案规范化或评分；这次截图中答案分隔符的判分行为不在图片修复范围内。
