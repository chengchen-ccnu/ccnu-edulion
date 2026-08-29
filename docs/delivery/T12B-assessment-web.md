# T12B assessment web delivery

## 任务目标与边界

T12B 在冻结的信息架构内一次性交付学生测评闭环和教师测评管理。学生端启用 `/practice`、`/practice/:assessmentId`、`/attempt/:attemptId/result`、`/wrong-questions`，并在既有课程学习页增加当前课程/课时测评入口；教师端只启用 `/teacher/assessments`，课程编排页只携带 course/lesson 上下文跳转，不复制编辑器。

本任务没有把作答或结果放进公共课程详情，没有复制播放器，没有实现 T12C 学情、AI、逐学生/定时公布、撤回公布、批量评分或复杂监考。OpenAPI 保持 0.16.0，没有新增接口、数据库迁移、业务表或第二套 DTO。真实联调发现题目冻结快照中允许为空的知识点/历史来源字段在 Jackson JSON 往返时被省略，导致真实组合题快照无法恢复；经用户授权，仅在 `QuestionDtos` 序列化边界为两个既有 nullable record 显式保留 `null`，没有改变测评状态机、权限、数据库或外部契约，并补了 H2 往返回归测试。

## 架构与调用流程

- 浏览器 API 统一复用 `createGatewayClient()`；学生和教师适配器的输入输出均从 `packages/api-client/src/generated/schema.d.ts` 派生。浏览器真实验收只访问 Gateway，没有从页面直连 Learning、Media、OSS 或数据库。
- `SafeAssessmentContent` 是角色中立的共享底层组件：Markdown 由 `marked` 解析、DOMPurify 清洗、KaTeX 严格渲染；媒体占位先调用 `createAssessmentMediaAccessGrant`，再以授权响应发起受控下载并创建临时 Blob URL。离页会中止请求并撤销 URL。教师/学生整页状态、答案可见性和权限判断没有放进共享组件。
- 学生练习中心只使用服务端参与状态、当前 attempt 和 serverTime 决定“开始、继续、查看结果”。分页/course 筛选保存在 URL；学习页用契约支持的 courseId 查询后在客户端按 lessonId 展示，不向服务端发送不存在的 lessonId 参数。
- 作答页以主问题为连续区块，组合题保留有序小问，支持全部契约题型、题号导航、前后题和整卷概览。`AttemptSession` 管理防抖保存、定时兜底、稳定 clientRequestId/请求体、429 Retry-After 与 offline/5xx 有限重试、旧响应保护、服务端时间校准、页面重新可见校准、截止锁定、最后可写草稿和同一提交请求重试；离页中止请求并清理定时器。
- 结果页只读取一份 `getAttemptResult`，由 `view=all` 或 `view=single&question=n` 投影。成绩和答案分别只依赖 `gradeVisible`/`answerVisible`；未授权字段不会进入结果 DOM，不能以 CSS 隐藏代替服务端安全投影。
- 错题页在当前路由展开详情，revision/clientRequestId 驱动掌握状态更新和最多三次独立 redo；主观题 `score/correct=null` 显示“本次重做不提供自动判分”，不重开原 attempt。
- 教师页通过 assessmentId/panel/courseId/lessonId/status/page 等查询参数恢复上下文，提供草稿 CRUD、SYSTEM＋本人题检索、精确 questionVersionId/顺序/分值组卷、安全预览、发布/归档、不可撤销关闭、有界结算轮询、两类独立公布、结果和逐份人工批改。关闭确认显示 activeAttemptCount；409 保留本地表单/组卷/评分输入，教师载入最新 revision 后再确认提交。页面不可见或离页时停止结算轮询和请求。
- 真实批改联调暴露并修复两个页面状态问题：成功写入前统一清理上一轮错误提示；离开 grading 面板时清除 attemptId，避免刷新恢复逻辑把“作答结果”再次拉回人工批改。

## 使用的 API

本任务没有修改 `docs/api/openapi.yaml`，重新生成并校验 OpenAPI 0.16.0 客户端。学生端使用：

- `listLearnerAssessments`、`getLearnerAssessment`
- `startAssessmentAttempt`、`getAttemptDraft`、`saveAttemptDraft`、`submitAttempt`
- `getAttemptResult`
- `listWrongQuestions`、`getWrongQuestion`、`updateWrongQuestionMastery`、`redoWrongQuestion`
- `createAssessmentMediaAccessGrant`

教师端使用：

- `listTeacherAssessments`、`createTeacherAssessment`、`getTeacherAssessment`、`updateTeacherAssessment`、`deleteTeacherAssessment`
- `replaceAssessmentQuestions`、`updateAssessmentPublication`
- `closeTeacherAssessment`、`publishAssessmentGrades`、`publishAssessmentAnswers`
- `listAssessmentResults`、`gradeAttemptAnswers`

统一页面适配覆盖 401、403、404、409、422、429、503 和浏览器离线；真实 owner/能力事实仍由 Gateway 和 Learning 校验。教师结果只显示 learnerId，批改响应没有历史评语投影，页面不伪造姓名或历史 comment。

## 数据库、演示数据与环境

没有创建数据库、Schema、表、迁移、账号、课程、课时、题目或媒体。本任务只在用途明确的既有 `edu_platform_learning_dev` 保留一套生产禁用的 local demo seed：

- 固定教师/学习者/课程/课时分别对应 `teacher_test_01`、`student_test_01` 及明确 T12B 测试 UUID；若无现有报名，Seed 创建一条自身拥有的固定 ACTIVE enrollment，若已存在 ACTIVE 报名则复用且清理时不删除他人记录。
- 从 `KG_MATH_T10B_V3` 的三个固定稳定 source ref 解析既有 SYSTEM 单选、简答和含真实题图组合题；版本或类型不匹配立即失败，不随机抽题或复制题库正文。
- 固定保留 6 场测评、6 份 attempt、10 条冻结测评题快照、2 条错题及确定性 redo/人工批改数据，覆盖可恢复普通练习、限时考试、客观/主观/组合题、真实题图、待批场景和四种公布组合。
- 所有写入是单事务、固定 ID、可重复；`Cleanup` 删除前校验 owner/课程/标题标记，随后按外键顺序只删除 Seed 拥有的行。

执行方式：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12BAssessmentDemoSeed.ps1 -Action Initialize
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12BAssessmentDemoSeed.ps1 -Action Cleanup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12BAssessmentDemoSeed.ps1 -Action Rebuild
```

脚本内部强制通过 `scripts/Invoke-WithLocalEnvironment.ps1` 取得本地 DPAPI 凭据，拒绝非 `edu_platform_learning_dev`，并关闭与 Seed 无关的 RabbitMQ 发布。生产 profile 不会自动创建数据。学生/教师应用继续使用既有 `VITE_GATEWAY_BASE_URL`；真实验收使用隔离端口 15173/15174 → Gateway 18080 → Learning 18084/Media 18083，避免改动用户原有服务。

## 自动化测试与实际结果

已执行：

- `pnpm.cmd --dir web-apps install --frozen-lockfile`：通过，锁文件一致。
- `pnpm.cmd --dir web-apps run generate:api`：通过，OpenAPI 0.16.0 客户端重新生成。
- `pnpm.cmd --dir web-apps run check:api`：通过，生成文件受跟踪且无漂移。曾按错误脚本名执行 `check:generated`，pnpm 明确提示不存在，随后使用仓库实际 `check:api` 完成验证。
- `pnpm.cmd --dir web-apps run typecheck`：3 个应用与 5 个共享包全部通过。
- `pnpm.cmd --dir web-apps run test`：29 个测试文件、191 项全部通过。沙箱内因 Windows esbuild `spawn EPERM` 无法启动，批准的沙箱外同一命令通过。
- `pnpm.cmd --dir web-apps run build`：三端生产构建通过；沙箱内同因 `spawn EPERM` 无法启动，批准的沙箱外通过。只保留既有 HLS/入口 chunk 大于 500 kB 的 Vite 提示。
- `powershell.exe ... Invoke-T12BAssessmentDemoSeed.ps1 -Action Rebuild`：1 项通过，真实 MySQL 演示集恢复为 6 场。
- `mvn.cmd --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：8 个 Reactor 模块全部 `BUILD SUCCESS`，耗时 2 分 09 秒；Gateway 12/12、IAM 9/9、Course 17 项中 1 项显式真实环境用例跳过、Learning 59 项中 5 项显式门控用例跳过、Media 25 项中 4 项显式门控用例跳过，其余模块无失败。

自动化新增覆盖路由/能力/安全回跳、导航高亮、列表状态、自动保存/时间校准/截止锁定、双视图四态结果、错题 redo/掌握、媒体授权清理、教师 CRUD/组卷/关闭/公布/批改、有限轮询、409 保留输入，以及课程编排只跳转的边界。

## 真实浏览器验收

真实 Vue 页面通过 Gateway 完成以下流程；控制台 warning/error 均为 0，未记录 Token、签名 URL、密码或 AccessKey：

- 学生练习中心显示 6 场真实状态；分别验证“均未公布、仅成绩、仅答案、均已公布”四态，切换 `view=all`/`view=single` 后刷新可恢复，未授权成绩或答案内容不进入 DOM。
- 普通练习修改答案后显示保存状态，刷新恢复最新草稿；主观题和组合题保持连续，3/3 完成后最终保存、确认、提交并进入结果。
- 错题本显示 2 条记录；主观题独立 redo 后立即出现第二条历史、剩余次数减少，`score/correct=null` 不伪造判分，掌握状态 revision 更新成功。
- 限时考试从 15 秒倒计时开始，保存后刷新在 5 秒继续并恢复答案；服务端截止后输入锁定、自动以最后成功草稿提交，未保存题显示“未作答”。
- 教师在真实页面创建并编辑临时 HOMEWORK，加入一个 SYSTEM 组合题和一个 SYSTEM 简答题，调整顺序与 12/8 分并发布不可变试卷。关闭前页面显示 1 份进行中草稿；确认后冻结影响数为 1，结算由 PENDING 变为 COMPLETED，待批计数为 1 人/2 题。
- 教师逐题评分后结果从 PARTIALLY_GRADED/2 待批变为 GRADED/0 待批，成绩与参考答案分别独立公布。公布后再次批改，学生总分由 13 更新为 12，两类公布状态保持 PUBLISHED。
- 在评分表已录入 6.5/5.5 和本地评语后，由并发请求推进 attempt revision；浏览器提交得到 409，得分/评语完整保留，载入最新 revision 后再次提交成功。最后归档为 ARCHIVED，刷新仍恢复 assessmentId 和 publication 面板。
- 学生 375 px、教师 1024 px 和 1440 px 检查中 `scrollWidth == clientWidth`，无横向溢出；原生链接、按钮、输入和 `aria-current` 均保留。浏览器自动化能把焦点落到目标链接，但其 Enter/Tab 控制未可靠产生后续导航，因此键盘激活的最终人工复核仍列为下述已知验证缺口，自动化单测已覆盖固定顺序与焦点样式。

题图链路的真实结果需要单独说明：`createAssessmentMediaAccessGrant` 经 Gateway 成功返回短期授权，授权对象以真实请求下载得到 HTTP 200、`image/png`、2810 bytes；但 OSS 对学生源站请求没有返回 `Access-Control-Allow-Origin`，浏览器 fetch→Blob 被 CORS 正常阻止，页面显示“图片暂不可用”。实现没有把签名 URL直接放进 DOM，也没有增加 Gateway 文件代理或绕过授权。要让题图在浏览器显示，需在对应开发 Bucket 配置允许实际学生/教师前端 origin 的 GET/HEAD CORS 后复验。

## 临时数据清理

教师闭环验收使用一场标题前缀为 `[T12B验收临时]` 的随机 assessment 和一份学生草稿。验收后先按精确 assessment ID 读取并核对 owner、标题和 ARCHIVED 状态，再经 `Invoke-WithLocalEnvironment.ps1` 在 `edu_platform_learning_dev` 内以事务按幂等记录 → 错题/发生/redo → 人工批改/答案 → attempt → assignment/question → assessment 的顺序删除，最终查询剩余 assessment 为 0。没有清空表、改动既有 SYSTEM 题、媒体、课程或账号。

随后执行 Seed `Rebuild`，把浏览器验收改动过的固定草稿、考试、错题和 redo 恢复到确定初态。保留的数据只有上节明确列出的 T12B local demo seed；隔离的 15173/15174/18080/18083/18084 服务和页面均已停止。

## 人工演示步骤

1. 启动 Gateway、Learning、Media 和学生/教师应用，执行 Seed `Initialize` 或 `Rebuild`。
2. 以 `student_test_01` 打开 `/practice`，依次查看四种公布组合；进入“可恢复练习”修改答案并刷新，完成主观题/组合题后提交，在结果页切换全卷/单题。
3. 打开 `/wrong-questions`，更新掌握状态并 redo 主观错题，确认历史、剩余次数和“无自动判分”说明。
4. 打开限时考试，保存后刷新并等待截止，确认锁定和最后草稿提交。
5. 以 `teacher_test_01` 打开 `/teacher/assessments`，创建草稿、从 SYSTEM 题库加入组合题/简答题、调序计分并发布；使用一份进行中作答验证关闭影响数、结算、人工批改、独立公布、公布后重批和归档。
6. 在开发者工具确认所有前端 API 都指向 Gateway；为开发 OSS Bucket 补齐精确 origin CORS 后，复验题图 Blob 展示和离页 URL 撤销。
7. 结束临时验收后精确清理随机 assessment；演示完毕可执行 Seed `Cleanup`，需要恢复时执行 `Rebuild`。

## 已知风险与后续依赖

- 开发 OSS Bucket 当前缺少前端 origin CORS，真实题图授权和对象本身有效，但安全 Blob 展示未通过浏览器验收。这是外部存储配置依赖；不得改成长期/直接签名 URL，也不得让 Gateway 代理对象文件。
- 浏览器自动化的 Tab/Enter 激活未可靠触发导航；语义控件、固定 DOM 顺序、焦点样式和 aria 状态已自动化覆盖，但仍需人工键盘走查一次关闭确认、标签切换、题号导航和错题入口。
- Vite 对 `hls.js` 及应用入口保留大 chunk 提示；本任务没有复制播放器或引入第二套媒体客户端。性能拆分应基于 T18 数据独立处理。
- 首期仍不含 FAILED 结算管理恢复、批量评分、逐学生/定时公布、撤回、复杂监考、T12C 学情或 AI。

## 可读性与 Git 交付

新增页面、API 适配器、作答状态机、安全渲染组件、Seed 和 nullable 快照修复均有通俗中文 TSDoc/Javadoc，说明职责、输入、输出、权限、幂等/revision、有限重试、数据库/媒体副作用和离页清理；生成代码没有手工补注释。已完成人工可读性检查。

- 分支：`codex/t12b-assessment-web`
- 关键实现提交：`71ff6b46612f374823122f77ca3bcb6338db478a`
- PR：[T12B 测评闭环前端 #37](https://github.com/chengchen-ccnu/ccnu-edulion/pull/37)，Ready/Open，等待总控验收，不自行合并
