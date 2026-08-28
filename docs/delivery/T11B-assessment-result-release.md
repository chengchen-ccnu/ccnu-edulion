# T11B assessment result release delivery

## 任务目标与边界

T11B 在既有 `learning-service` 内补齐 T12B 无法由前端安全推导的领域事实：不可撤销的整场关闭、最后成功草稿的有界结算、成绩与参考答案/解析独立公布、公布后重新批改，以及四种学生结果安全投影。外部契约先从 OpenAPI 0.15.0 向前演进到 0.16.0，再实现后端并重新生成 TypeScript 客户端。

本任务没有实现 T12B Vue 页面或新路由，没有拆分 learning-service，没有增加逐学生/定时公布、撤回公布、批量评分、复杂监考、T12C 学情、Redis/RabbitMQ 可见性事实、跨 Schema 查询/Join/外键或第二套 DTO。

## 主要架构与调用流程

教师列表/详情先从 learning Schema 聚合当前 `DRAFT` 数量，供未来 T12B 二次确认。关闭命令要求 `teaching:access`、测评 owner、`PUBLISHED`、`OPEN`、`clientRequestId` 和 `expectedRevision`；同一事务冻结受影响草稿数、写入 `CLOSED` 和结算状态并推进 revision。关闭事实一旦落库，新 attempt、草稿恢复和答案保存立即被拒绝。

关闭后的 `AssessmentSettlementProcessor` 每轮最多领取 10 场、每场最多处理 50 份草稿。每份草稿只读取服务端最后成功保存答案，以 attempt ID 派生确定性结算请求 ID，并在批次事务内提交、自动判分和推进剩余数量。失败只记录安全异常类型，采用 1/5/30/120/300 秒有限退避；第五次后写 `FAILED` 并停止自动领取，草稿与失败事实仍保留供运维诊断，且绝不会把结果标为就绪。进程中断会由事务回滚和数据库中的 `PENDING/PROCESSING` 事实恢复，不使用无限循环或无界事务。

成绩与参考答案分别保存策略、公布状态和时间。普通练习的 `AFTER_SUBMISSION` 在本人提交后公布答案；作业/考试只能在整场关闭后按 `AFTER_ASSESSMENT_GRADED` 自动公布、按 `MANUAL_AFTER_CLOSE` 手动公布或保持 `NEVER`。旧 `AFTER_GRADED` 作为兼容输入规范化为安全的整场批改完成策略。成绩可在关闭、草稿清零且人工批改清零后自动或手动公布。两类事实都只能由 `UNPUBLISHED` 单向推进到 `PUBLISHED`。

结果投影以 `gradeVisible` 和 `answerVisible` 明确表达可见性。未公布成绩时，作答状态投影为 `SUBMITTED`，总分、逐题分数、对错、评分状态和成绩更新时间均为契约规定的 `null`；未公布答案时，参考答案和解析为 `null`。本人答案、提交时间和冻结试卷始终保留。归档或取消报名后仍按 attempt owner 读取同一冻结结果。成绩公布后重新批改只追加 `manual_grading_record`、推进 attempt revision 和 `grade_updated_at`，不改变原公布时间或删除历史。

## API 契约

OpenAPI 0.16.0 新增：

- `POST /api/v1/teacher/assessments/{assessmentId}/closure`：不可撤销关闭，返回冻结影响人数与当前教师投影。
- `POST /api/v1/teacher/assessments/{assessmentId}/grade-publication`：满足关闭、结算和批改资格后手动公布成绩。
- `POST /api/v1/teacher/assessments/{assessmentId}/answer-publication`：关闭且草稿清零后手动公布答案，不要求主观题完成批改。

测评创建/更新增加 `gradePublicationPolicy`，`AnswerPublicationPolicy` 向前扩展；教师投影增加关闭/结算、待结算、待批人数/题数、两类公布状态/时间和资格。`AttemptResult` 增加两个可见性字段与 `gradeUpdatedAt`，所有隐藏字段采用显式 `null`。所有写命令都通过 Gateway 既有教师路由，要求教学能力并在服务端校验 owner、资格、幂等和 revision。

## 数据库迁移

只新增 Learning `V11__add_assessment_result_release.sql`，不改写 V9/V10：

- `assessment` 新增向前兼容的 `answer_release_policy`、成绩策略、关闭/结算状态、关闭时间/影响数、有限重试信息、两类公布状态/时间及到期索引和 CHECK 约束。
- 保留 V9 `answer_publication_policy` 兼容列及原约束；写入时同步安全旧值，新策略以 `answer_release_policy` 为事实源。
- `assessment_attempt` 新增 `grade_updated_at`，历史已评分作答从 `graded_at` 回填。

没有新建数据库、业务表、Seed、跨 Schema 外键或消息可见性投影。

## 环境变量与启动

- `LEARNING_ASSESSMENT_SETTLEMENT_POLL_DELAY`：关闭后草稿结算轮询间隔，默认 `2s`；测试 profile 固定为 `1h` 并由测试显式调用批次，避免后台调度争抢 Fixture。

本地启动和真实迁移继续经 `scripts/Invoke-WithLocalEnvironment.ps1` 注入凭据。示例：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:LEARNING_ASSESSMENT_SETTLEMENT_POLL_DELAY = '2s'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl learning-service -am spring-boot:run
}
```

## 测试命令与实际结果

- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl learning-service -am -DskipTests compile`：通过。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl learning-service -am test`：56 项中 52 项通过、4 个显式真实环境项按开关跳过；随后增加有限失败上限用例并纳入最终完整 Java 校验。
- `pnpm.cmd --dir web-apps run generate:api`：成功生成 OpenAPI 0.16.0 客户端。
- `pnpm.cmd --dir web-apps run typecheck`：3 个应用与 5 个共享包通过。
- `pnpm.cmd --dir web-apps run test`：沙箱内因 esbuild `spawn EPERM` 未启动；批准的沙箱外同命令 23 个文件、140 项全部通过。
- `pnpm.cmd --dir web-apps run build`：沙箱内同因 `spawn EPERM` 未启动；批准的沙箱外三端构建通过，仅保留既有 HLS 523.17 kB chunk 提示。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：最终复跑 8 个 Reactor 模块全部 `BUILD SUCCESS`，总耗时 1 分 47 秒；Gateway 12/12、Learning 57 项中 53 项通过且 4 个真实环境项按开关跳过、Media 25 项中 21 项通过且 4 个真实环境项按开关跳过，其余模块无失败。

自动化覆盖教学能力、owner、幂等重放/冲突、重复关闭/公布、revision 409、非法策略 422、关闭前泄题阻断、草稿影响数与最后保存答案结算、待人工批改、自动/手动公布、五次有限失败、重新批改审计和归档读取。学生响应序列化明确验证“均未公布、仅成绩、仅答案、全部公布”四态，未授权侧只有 `null` 且不包含参考答案、解析或评分数据值。

## 专用 MySQL 验收与数据清理

通过凭据包装脚本在用途明确的 `edu_platform_learning_dev` 执行 `LearningMysqlMigrationAcceptanceTest`。Flyway 从 V10 正常前进到 V11；4 项中 3 项通过，只有需要 `LEGACY_QUESTION_ROOT` 的历史题库用例按设计跳过。用例以随机 UUID 创建一份 EXAM、一个 GRADED attempt、一条错题和一次 redo，验证 V11 关闭、公布、`grade_updated_at` 与最多五次结算尝试约束后，按 redo → wrong → attempt → assessment 精确逆序删除并逐项断言为 0。

真实验收没有创建数据库或新 Seed，没有修改/删除现有正式题库、课程、用户、媒体、消息或对象。保留的数据变化只有 `edu_platform_learning_dev` 的 V11 Schema 和 Flyway 成功历史。重新验收使用同一包装命令即可生成新的随机 Fixture 并自动清理；本任务没有 local seed，因而没有 seed 重建/清理命令。

## 人工演示步骤

1. 以具备 `teaching:access` 的测评 owner 查询教师详情，记录 `activeAttemptCount` 并展示高风险确认。
2. 使用当前 revision 和新 `clientRequestId` 调用关闭；确认返回冻结影响数，学生端立即无法开始、恢复或保存草稿。
3. 轮询教师详情，确认 `pendingSettlementCount` 逐批下降；清零前 `resultsReady=false`，待批计数准确。
4. 对同一已提交作答依次观察四态：初始两侧隐藏；手动公布答案后仅答案可见；完成批改并公布成绩后全部可见；另以 `NEVER` 答案策略演示仅成绩可见。
5. 成绩公布后用最新 attempt revision 重新批改，确认学生直接看到新分数和 `gradeUpdatedAt`，公布时间不变且人工评分审计新增一行。
6. 归档测评并取消报名，确认作答 owner 仍能读取符合公布规则的冻结结果。

## 已知风险与后续依赖

- 持续失败五次的结算会停在 `FAILED`，不会继续自动重试或误判就绪；当前首期没有新增管理恢复 API，需运维诊断根因后再制定受控恢复步骤。T12B 应明确显示失败状态，而非伪装完成。
- 结算吞吐固定为每轮最多 10 场、每场 50 份，属于首期安全上限；大规模容量压测和动态调参不在本任务。
- 前端构建保留既有 HLS chunk 体积警告，与 T11B 无关。
- T12B 仍需在既有路由内消费生成客户端完成二次确认、状态展示和四态结果 UI；本任务没有创建 Vue 页面或新路由。

## 可读性、分支与发布

关闭/结算/公布状态机、权限/owner、幂等、revision、有限重试、安全投影和重新批改均补充通俗中文 Javadoc/注释；OpenAPI 和 V11 SQL 对空值、兼容列、单向状态及风险作了说明。生成代码未手工修改。

分支为 `codex/t11b-assessment-result-release`。关键提交和 Ready PR 信息在发布完成后由同一任务补写；本任务不会自行合并。
