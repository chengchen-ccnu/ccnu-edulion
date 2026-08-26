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
