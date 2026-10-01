# Domain event contracts

本目录是领域事件 Schema 的唯一事实来源。每个事件文件必须版本化，并包含统一信封：`eventId`、`eventType`、`eventVersion`、`aggregateId`、`occurredAt`、`traceId`、`payload`。生产者使用本地事务 + Outbox；消费者按 `eventId` 持久化幂等，禁止通过事件共享整表快照。

T07 首次冻结 [`media-events.v1.yaml`](./media-events.v1.yaml)。RabbitMQ 资源如下：

| 资源 | 名称 | 类型/绑定 |
|---|---|---|
| 领域事件 Exchange | `edu.media.events.v1` | durable topic |
| 媒体死信 Exchange | `edu.media.dlx.v1` | durable direct |
| 上传后处理 Queue | `edu.media.processing.v1` | durable classic；绑定 `media.uploaded.v1`；DLX routing key `media.processing.dead.v1` |
| 引用台账 Queue | `edu.media.references.v1` | durable classic；绑定 `media.reference.bound.v1` 与 `media.reference.released.v1`；DLX routing key `media.references.dead.v1` |
| 生命周期审计 Queue | `edu.media.lifecycle.v1` | durable classic；绑定 `media.ready.v1`、`media.rejected.v1`、`media.failed.v1` 与 `media.deleted.v1`；DLX routing key `media.lifecycle.dead.v1` |
| 统一死信 Queue | `edu.media.dlq.v1` | durable classic；绑定三个 `*.dead.v1` routing key |

本地消费者成功返回后由 Spring AMQP 确认消息；失败采用最多 3 次、指数退避的进程内有限重试，超限后拒绝且不重新入队，由 Broker 投递 DLQ。进程在确认前退出时由 RabbitMQ 重新投递。Outbox 只有在 mandatory publish 可路由且收到 publisher confirm 后标记成功；MQ 不可用、nack 或 return 均保留记录并有限退避补投。DLQ 不自动回流，避免毒消息无限循环；人工补偿必须先修复原因，再使用原 `eventId` 重放，由消费幂等表保护。

消息和数据库只保存稳定媒体 ID、业务引用 ID 与处理事实，禁止保存 Bucket、Object Key、AccessKey 或短期签名 URL。

T09 另行冻结 [`learning-question-events.v1.yaml`](./learning-question-events.v1.yaml)，用于 learning-service 内部异步题库导入：

| 资源 | 名称 | 类型/绑定 |
|---|---|---|
| 学习域事件 Exchange | `edu.learning.events.v1` | durable topic |
| 题库导入 Queue | `edu.learning.question-import.v1` | durable classic；绑定 `question.import.requested.v1`；DLX routing key `question.import.dead.v1` |
| 学习域死信 Exchange | `edu.learning.dlx.v1` | durable direct |
| 题库导入死信 Queue | `edu.learning.question-import.dlq.v1` | durable classic；绑定 `question.import.dead.v1` |

导入任务与 `QuestionImportRequested` Outbox 在 learning-service 本地事务内提交。消费者使用手动确认语义、最多 3 次指数退避和 `eventId` 持久化幂等；不可恢复异常或超限消息进入 DLQ，Recoverer 将任务标为 `FAILED` 并保留人工重放证据。CSV/XLSX 源文件只通过 media-service 的短期 GET 授权读取；事件、数据库和日志均不得保存签名 URL。任务进入终态后仍保留 `QUESTION_IMPORT_SOURCE` 引用至配置的保留期，到期任务再以本地 Outbox 发布 `MediaReferenceReleased`，不能在消费完成时立即删除对象。

T09A 新增 [`learning-question-events.v2.yaml`](./learning-question-events.v2.yaml)，不改写 v1。每个最多 20 项的历史 JSON 批次在 learning-service 内完成后，以本地 Outbox 向 durable topic `edu.learning.events.v2` 发布 `question.legacy-import.batch.processed.v2`。事件只含 importTaskId、batchId、dryRun 和聚合计数，不含题干、答案、原始 JSON、路径、外部 URL 或媒体签名。当前没有业务消费者；后续迁移审计消费者必须自建队列并按 `eventId` 幂等，不能把 v2 绑定到 v1 CSV/XLSX Worker。

T11 冻结 [`learning-enrollment-events.v1.yaml`](./learning-enrollment-events.v1.yaml)，用于课程播放授权所需的真实报名投影：

| 资源 | 名称 | 类型/绑定 |
|---|---|---|
| 报名事件 Exchange | `edu.learning.enrollment.events.v1` | durable topic |
| Course 报名投影 Queue | `edu.course.enrollment-access.v1` | durable classic；绑定 `learning.enrollment.access-changed.v1`；DLX routing key `course.enrollment-access.dead.v1` |
| Course 报名死信 Exchange | `edu.course.enrollment.dlx.v1` | durable direct |
| Course 报名死信 Queue | `edu.course.enrollment-access.dlq.v1` | durable classic；绑定 `course.enrollment-access.dead.v1` |

报名创建、重新激活或取消与 `EnrollmentAccessChanged` Outbox 在 learning-service 本地事务内提交。course-service 最多 3 次指数退避消费，先以 `eventId` 持久化幂等，再按事件 `changedAt` 单调更新本地 `(learnerId, courseId)` 投影；重复或乱序消息不会让旧状态覆盖新状态。报名投影只用于当前发布课程的 `ENROLLMENT_REQUIRED` 非试看播放判断，不复制课程表，也不向 learning-service 发起同步回调。超限消息进入 DLQ，不自动回流；RabbitMQ 不可用时 Learning Outbox 保留并按既有限次策略补投。

T13.1 冻结 [`agent-index-events.v1.yaml`](./agent-index-events.v1.yaml)，统一承载题目、知识点、课程文字和后续学生记忆的派生索引变化通知。事件只携带稳定 ID、事实源 revision、学段、可见范围和最小授权元数据，不复制题干、答案、课程正文、记忆正文、签名 URL 或隐私。真实 Outbox、RabbitMQ 资源、Qdrant/Neo4j 消费、DLQ 和重建由 T14.1/T13.2/T14.2 实现；消费者必须按 `eventId` 幂等，并拒绝旧 revision 覆盖新投影或复活较新的删除 tombstone。

T12C.0 冻结 [`course-lesson-content-events.v1.yaml`](./course-lesson-content-events.v1.yaml)，由 Course 发布事务向 Learning 同步完整的当前发布课时内容身份：

| 资源 | 名称 | 类型/绑定 |
|---|---|---|
| 课时内容 Exchange | `edu.course.lesson-content.events.v1` | durable topic |
| Learning 当前内容 Queue | `edu.learning.lesson-content.v1` | durable classic；绑定 `course.lesson-content.published.v1`；DLX routing key `learning.lesson-content.dead.v1` |
| Learning 当前内容死信 Exchange | `edu.learning.lesson-content.dlx.v1` | durable direct |
| Learning 当前内容死信 Queue | `edu.learning.lesson-content.dlq.v1` | durable classic；绑定 `learning.lesson-content.dead.v1` |

Course 与发布事务一并写 Outbox；Learning 总计最多尝试 3 次，按 `eventId` 持久化幂等并以 `publicationRevision` 拒绝乱序旧事件。完整课时映射原子替换当前投影，消息不含 mediaId、正文、学习事实或存储信息。Outbox 未及时送达时，Learning 只可通过受控 Course HTTP 事实有限补齐，不得跨 Schema 查询。

T16.1 新增 [`course-availability-events.v1.yaml`](./course-availability-events.v1.yaml)，由 Course 系统暂停/恢复事务发布 `CourseAvailabilityChanged`：

| 资源 | 名称 | 类型/绑定 |
|---|---|---|
| 可用性 Exchange | `edu.course.availability.events.v1` | durable topic |
| Learning 可用性 Queue | `edu.learning.availability.v1` | durable classic；绑定 `course.availability.changed.v1` |
| 可用性死信 Exchange | `edu.learning.availability.dlx.v1` | durable direct |
| 可用性死信 Queue | `edu.learning.availability.dlq.v1` | durable classic；绑定 `learning.availability.dead.v1` |

事件只含课程/周期 ID、revision、paused 和区间起止，不含撤权原因、课程正文或媒体信息；暂停时 resumedAt 必须显式为 null。复用 Course 既有有确认 Outbox，Learning 最多 3 次尝试后 DLQ，以 eventId 持久化幂等。消费者读取受保护 Course 当前事实并在本域课程锁下追平区间，不以迟到 payload 覆盖恢复状态或重复顺延。Course 故障保留重试/死信；访问入口实时校验，不依赖事件及时送达。人工修复后以原 eventId 重放，不自动回流。

T16.2 新增 [`iam-teacher-review-events.v1.yaml`](./iam-teacher-review-events.v1.yaml)，IAM 决定事务同时写不可变决定、安全审计、教师能力和最小 `TeacherQualificationDecided` Outbox。事件只含申请/轮次/用户 ID 与批准或驳回状态，不含资质、联系方式或理由。`edu.iam.events.v1` durable topic 将其投递到 `edu.iam.teacher-review.decisions.v1` durable queue，配置 DLQ；T16.4 才增加消息消费者，须以 `eventId` 持久化幂等。IAM 发布使用 mandatory return、publisher confirm 与有限退避；耗尽 `FAILED` 保留受控重放证据。

同任务复用本文件开头的 [`media-events.v1.yaml`](./media-events.v1.yaml) 和 `edu.media.references.v1`：申请轮次提交写 `MediaReferenceBound`，到期释放写 `MediaReferenceReleased`，均使用 `ownerService=iam-service`、`referenceType=TEACHER_APPLICATION_ROUND` 和不可变轮次 ID。相同媒体的后续事件等待前序 Outbox 发布确认，Media 继续按 `eventId` 幂等消费。释放事件已发布只表示请求已送达；物理删除仍取决于 Media 有效引用和回收宽限期。
