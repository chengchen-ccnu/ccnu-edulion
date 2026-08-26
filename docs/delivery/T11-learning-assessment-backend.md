# T11 learning assessment backend delivery

## 任务目标与边界

T11 在既有 `learning-service` 内实现课程报名、课时进度、学习笔记、测评管理、不可变试卷快照、作答草稿与一次性提交、客观题自动判分、主观题人工批改、成绩、错题及教师课程范围内的学生学情投影。没有拆分新微服务，没有实现 T12 页面，没有修改 Python 智能体、媒体归属或课程领域表。

课程、当前发布版本、目录课时、访问类型和 owner 仍由 `course-service` 拥有。Learning 创建报名、进度或测评前只调用版本化 Course JSON API；连接超时、5xx 或无有效响应返回 `LEARNING.COURSE_DEPENDENCY_UNAVAILABLE` 503，课程不可见或非 owner 返回 404。两个服务不跨 Schema 查询、Join、建外键或共享 Mapper。

## API 与调用流程

OpenAPI 升级为 0.14.0，新增以下能力：

- `/api/v1/learning/enrollments`：报名、取消及我的报名分页；重复 ACTIVE 报名返回原记录，取消后重报恢复原 ID，取消不删除历史。
- `/api/v1/learning/courses/{courseId}/progress` 与 `.../progress-events`：课程进度读取及 `clientEventId` 幂等上报。
- `/api/v1/learning/courses/{courseId}/lessons/{lessonId}/notes`、`/api/v1/learning/notes/{noteId}`：本人笔记 CRUD 与 revision 冲突。
- `/api/v1/teacher/assessments/**`：教师测评分页、创建、读取、草稿更新、题目排序/分值替换、发布/归档和成绩分页。
- `/api/v1/learning/assessments/**`、`/api/v1/learning/attempts/**`：学生安全试卷、开始作答、草稿恢复、一次性提交与结果。
- `/api/v1/teacher/attempts/{attemptId}/grades`：人工批改。
- `/api/v1/learning/wrong-questions`：本人错题分页。
- `/api/v1/teacher/learning/courses/{courseId}/students`：owner 课程内报名、进度和成绩汇总投影。

Gateway 将学习路由限定为 `learning:access`，T11 教学路由限定为 `teaching:access`。领域控制器再次检查可信权限头，学习资格不依赖 `STUDENT` 角色，因此同时拥有学习和教学能力的教师账号可以作为学习者报名和作答。学生只能读取本人 enrollment/progress/note/attempt/result；教师测评、批改、成绩和学情都再次按 course owner/assessment owner 验证，越权资源统一 404。

报名成功事务写 Learning 既有 `learning_outbox_event`，发布 `EnrollmentAccessChanged` v1 到 `edu.learning.enrollment.events.v1`。Course 的 `edu.course.enrollment-access.v1` 消费者按 `eventId` 幂等、按 `changedAt` 防乱序写本地投影；失败总计尝试 3 次，再经专用 DLX 进入 DLQ。`course-service` 播放授权实际查询该投影：PUBLIC、试看或 ACTIVE 报名可播放，缺少/取消报名的报名制非试看课时返回 403。该方案不存在 Course 同步回调 Learning 的循环依赖。

## 数据迁移、表和状态机

Learning 真实开发库已经应用到 V8，因此 T11 使用 `V9__add_learning_assessment_domain.sql`，绝不补写 V4、启用 out-of-order/baseline 或修改 V1～V3/V5～V8。补写 V4 会成为已到 V8 数据库中的低版本迁移，无法按正常顺序应用。Course 使用向前 V3。

Learning V9 创建：

- `enrollment`：`learner_id + course_id` 唯一；ACTIVE/CANCELED 保留历史。
- `lesson_progress`、`lesson_progress_event`：汇总事实与 `client_event_id` 唯一幂等记录。位置/完成只向前，旧事件不累计观看时间；单事件最多 300 秒，总观看时间以播放位置的有界倍数限制。
- `learning_note`：本人、课程、课时、revision。
- `assessment`、`assessment_question`、`assessment_assignment`：草稿元数据、精确 `questionVersionId`、完整题目快照、顺序/分值和 COURSE 发布范围。发布后题目行不可修改。
- `assessment_attempt`、`assessment_answer`：`DRAFT -> SUBMITTED -> PARTIALLY_GRADED/GRADED`，同一 learner/assessment 唯一。
- `manual_grading_record`：grader、时间、答案 revision、分数和评语。
- `wrong_question`、`wrong_question_occurrence`：learner/course/questionVersion 聚合，并以 attempt/question 为发生幂等键。
- `learning_idempotency_request`：创建测评和人工批改请求重放。

Course V3 创建 `course_enrollment_access` 和 `consumed_learning_event`。投影只保存播放决策所需稳定 ID、状态和时间，不复制课程或报名表，也不保存 Token、Bucket、Object Key、短期媒体 URL 或 OSS 信息。

提交事务先把 attempt 从 DRAFT 原子推进到 SUBMITTED，再保存最终答案、自动评分、错题发生记录和汇总状态；并发提交只有一个 revision 条件更新成功。相同 `clientRequestId + final answer set` 重放返回既有结果，不同请求或内容返回 409。人工批改在一个事务中校验 attempt revision、写审计记录、更新答案、幂等累计错题并重新汇总；全部必需答案有分数后才进入 GRADED。

单选/判断按规范化字符串精确比较，多选按集合精确比较且无部分分，标准填空固定使用 Unicode NFKC、首尾去空白、连续空白折叠及 `Locale.ROOT` 小写，解答题/组合题进入人工批改，不调用 LLM。试卷选择只接受 ACTIVE SYSTEM 或当前教师本人 ACTIVE TEACHER 题；快照保存完整历史版本，题库 currentVersion 后续变化不会漂移。

## 答案防泄漏与幂等指纹说明

学生试卷 DTO 在类型上不含 `answers`、`analysis` 或评分规则；组合子题也转换为既有学生安全模型。结果仅在 `AFTER_SUBMISSION`，或 `AFTER_GRADED + GRADED` 时返回答案/解析；`NEVER` 永不返回。教师 owner 结果可读取完整评分事实。错误响应、日志、事件和 Course 投影不包含答案或解析。

进度幂等只使用 `clientEventId` 唯一约束，没有 payload hash。测评创建、草稿/提交和人工批改保留 SHA-256 请求指纹，具体失败场景是客户端在超时后错误复用同一 `clientRequestId` 提交不同的长答案/批改内容：主键只能识别“见过该 ID”，无法区分可安全返回旧结果的相同重放与必须 409 的冲突重放；在幂等表复制完整答案会重复持久化最多 500×20,000 字符的内容并扩大答案暴露面。指纹只用于相等性判断，不作为业务主键、迁移校验、contract freeze、baseline 或 gate，实际答案仍由 `assessment_answer` 保存并受 owner/公布策略保护。

## 环境变量与启动

- `COURSE_SERVICE_BASE_URL`，默认 `http://127.0.0.1:8082`。
- `LEARNING_COURSE_REQUEST_TIMEOUT`，默认 `3s`。
- `RABBITMQ_ENABLED=true`：local profile 下启用既有 Learning Outbox 发布器及 Course 报名消费者。
- `LEARNING_MYSQL_DATABASE=edu_platform_learning_dev`、`COURSE_MYSQL_DATABASE=edu_platform_course_dev`：唯一允许的真实开发 Schema。

所有真实启动/迁移/验收命令必须包在 `scripts/Invoke-WithLocalEnvironment.ps1` 内；应用不会创建数据库，也不得读取或打印 DPAPI 凭据。推荐先启动 Course，再启动 Learning；交换机尚未声明时 Learning mandatory publish 会保留 PENDING 并有限重试，不丢弃报名事务。

## 自动化测试与实际结果

已执行且通过：

- `mvn ... -pl learning-service -Dtest=LearningAssessmentIntegrationTest test`：3 项，覆盖能力化教师作为学习者、报名/Outbox 幂等、进度重复/乱序、笔记 owner、SYSTEM/本人/他人私题范围、快照不漂移、草稿恢复、四类客观题评分、一次性提交、人工批改 revision、成绩/错题幂等和答案公布策略。
- `mvn ... -pl course-service -Dtest=CourseCatalogIntegrationTest,CourseEnrollmentMessagingTest test`：12 项，覆盖 ACTIVE/CANCELED/乱序/重复事件投影、报名制播放查询和 DLX/DLQ 拓扑。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：8 个 Reactor 模块全部成功，合计 105 项，99 项通过、6 项显式真实环境用例默认跳过，0 失败/错误；Learning 47 项中 44 通过/3 跳过，Course 16 项中 15 通过/1 跳过，Gateway 12/12 通过。
- `pnpm.cmd --dir web-apps run generate:api` 成功生成 OpenAPI 0.14.0 客户端。
- `pnpm.cmd --dir web-apps run typecheck`：3 个应用和 5 个共享包全部通过，命令内再次生成 API 类型。
- `git diff --check`：通过；提交后 `pnpm.cmd --dir web-apps run check:api` 通过，生成文件已跟踪且无漂移。
- 人工可读性检查：完成。新增业务类、HTTP 边界、权限、课程依赖、进度合并、试卷快照、提交/判分事务、错题幂等、Outbox 和 RabbitMQ 消费/重试均有与真实实现一致的中文 Javadoc/行内边界注释；自解释的单行 JDBC 映射未重复翻译代码。

Ready PR #28 的实现 head `7d39d6f` 已完成 GitHub Actions [CI run 32717288978](https://github.com/chengchen-ccnu/ccnu-edulion/actions/runs/32717288978)：Java/JDK 17、Frontend/Node 20.19、Python/3.9 和 OpenAPI generated source 四项全部通过。PR 创建瞬间触发的较早重复 run 32717284108 同样四项通过，无失败/取消。

## 人工/真实验收与测试数据

自动化使用进程退出即销毁的 H2 内存库，创建固定合成 UUID 覆盖两个学习者、一门课程、一个课时、一份五题测评和一次作答，没有持久 Seed。真实验收全部经 `scripts/Invoke-WithLocalEnvironment.ps1` 运行：

- `LearningMysqlMigrationAcceptanceTest`：在用途明确的 MySQL 8 `edu_platform_learning_dev` 上从已有 V8 正常前进到 V9，Flyway 历史含 8/9 且不含 4；新表全部存在。该验收沿用一个随机题库/知识点 Fixture 验证存量迁移兼容，最后按随机 ID 删除，清理断言均为 0；未运行历史题库 Import。
- `CourseEnrollmentRealEnvironmentAcceptanceTest`：`edu_platform_course_dev` 从 V2 正常前进到 V3；连接真实 RabbitMQ，验证持久主队列/DLQ、ACTIVE/CANCELED、eventId 重放与旧 `changedAt` 乱序。本轮随机 event/enrollment/learner/course 投影及消费记录全部按精确 ID 删除，结果为 0。
- 双服务真实 API 链路：创建 `teacher_test_01` 所有的报名制测试课程 `08629e70-60f8-4ff1-9250-0297f3bd8a95`、课时 `a305ab82-acb1-4a76-b945-9213b4cff82a`，教师型账号 `student_test_01` 同时携带学习/教学能力，经 Learning API 创建报名 `eb2de3da-99ed-447c-a5c0-7f0fe50af08e`。实际调用 Course 发布事实 API，Outbox 达到 `PUBLISHED`，Course 投影达到 `ACTIVE`；取消后投影达到 `CANCELED`。最后在两个 Schema 内按上述精确 ID 逆外键清理，`learningFixtureRows=0`、`courseFixtureRows=0`。

真实验收没有清空任何表，没有运行历史题库全量 Import，也没有删除或改写现有 648 道 SYSTEM 题、知识图谱、T10A/T10B 任务、媒体、OSS 对象或检查点。Learning V9 与 Course V3 迁移表作为正式开发 Schema 向前演进保留，不属于伪数据，不回滚。

## 已知风险与后续依赖

- 报名播放投影是最终一致；Outbox 尚未投递时，新报名用户会短暂收到 403。发布耗尽的 FAILED Outbox 和 DLQ 需要运维告警/人工重放能力，T11 不引入第二套 MQ。
- Course 事实读取依赖既有公开/教师 JSON 投影；依赖超时返回 503，不缓存为永久事实。大规模课程/课时查询聚合与批量 Course facts API 可在有压测证据后演进。
- 首期不实现支付、监考、复杂防作弊、多选部分分、LLM 判分、薄弱知识点推断或 Redis 作为进度事实源。
- T12A 复用报名/进度/笔记 API 填充 `/my-learning` 和既有 `/learn/...`；T12B 复用安全试卷、草稿、提交、结果、错题及教师测评/批改；T12C 复用教师学生学情投影。三个任务不得重新创建平行学习路由或手写 DTO。

## Git 交付

- 分支：`codex/t11-learning-assessment-backend`
- 契约/迁移提交：`57b1f1f`
- 实现/测试提交：`e8ecbbd`
- 文档/验收提交：`7e048d1`
- PR：[#28 T11 学习与测评后端](https://github.com/chengchen-ccnu/ccnu-edulion/pull/28)
- 合并：2026-08-26 由总控在全部检查通过后合并，`main@73fc35f0581e534e7a31718fce7fd01a9faf7a78`。
