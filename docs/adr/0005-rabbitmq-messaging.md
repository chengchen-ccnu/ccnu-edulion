# ADR-0005: RabbitMQ 作为平台消息队列

- 状态：Accepted
- 日期：2026-08-01

## 背景

T07 需要异步驱动媒体校验、转码、派生文件、状态通知与失败补偿，后续学习进度、通知、热点聚合和导入导出也需要统一的可靠消息基础。

当前团队主要使用 Spring Boot，首期规模不要求日志流平台或超大吞吐。RabbitMQ 具备成熟的 Spring AMQP 集成、持久队列、发布确认、消费者确认、路由、重试和死信能力，适合平台当前以任务和领域事件为主的异步链路。

## 决策

- 平台统一使用 RabbitMQ 4.x 与 Spring AMQP，不引入第二套消息实现。运行时配置统一使用 `spring.rabbitmq.*` 与 `RABBITMQ_*`。
- 所有跨服务事件继续以 `docs/events/*` 为事实来源，消息必须携带标准事件信封。Exchange、Routing Key、Queue、死信队列和版本兼容策略应由 T07 文档化，消费者不能根据 Java 类名猜测协议。
- 可靠发布采用“本地业务事务 + Outbox + 异步投递 + publisher confirm/return”。不能在数据库事务提交前直接发送消息，也不能把一次 `convertAndSend` 返回当作业务事件已经可靠送达。
- 消费语义为至少一次。消费者在业务处理成功后确认消息，按 `eventId` 持久化去重；幂等完成后重复投递必须安全。RabbitMQ 不被视为提供端到端“恰好一次”。
- 重试必须有上限并使用退避；不可恢复错误和超过次数的消息进入死信队列，保留错误原因、事件 ID、Trace ID 和人工补偿入口，禁止无限重回原队列。
- 本地单节点开发可以使用 durable classic queue；生产关键事件优先使用至少三节点 RabbitMQ 集群和 quorum queue。队列类型差异不得改变事件契约或消费者业务语义。
- MQ 不可用时，已提交的 Outbox 记录保留并稍后重投；上传完成可显示为“等待处理”，但登录、课程查询和已授权内容访问不能被 MQ 故障拖垮。

## T07 落地边界

- T07 只实现媒体域所需的通用消息基础、上传后处理任务和媒体事件，不提前实现学习进度、通知或热点消费者。
- 没有 RabbitMQ 实例时，默认关闭 MQ，Outbox 表仍是业务事实的可恢复边界；不得伪造真实 Broker 端到端成功。
- 真实 RabbitMQ 验收至少覆盖：发布确认、消费者幂等、失败有限重试、死信、进程重启后未确认消息重新投递、Outbox 补投以及 Trace ID 传播。
- T07 落地 durable topic exchange `edu.media.events.v1`、direct DLX `edu.media.dlx.v1`、处理/引用/生命周期三个 durable classic queue 及统一 DLQ；详细绑定以 `docs/events/README.md` 为准。
- RabbitMQ 用户名、密码和 TLS 材料只能通过本机加密存储或 CI Secret 注入，不得写入仓库、`.env.example` 的真实值或日志。

## 后果

- Spring Boot 服务可复用统一的 Spring AMQP 配置、事件转换、观测、重试和死信约定，降低团队学习与本地运维成本。
- 所有 Java 服务的消息配置和文档现统一为 RabbitMQ，避免运行时存在两套 MQ 认知。
- RabbitMQ 不是数据库；正式媒体状态、引用关系、作答和成绩仍以各领域 MySQL 为事实源，Redis 与队列数据都必须允许重建或补偿。
- 本地真实消息链路需要额外安装 RabbitMQ 及兼容 Erlang/OTP，或者连接明确授权的托管 RabbitMQ；仅做契约和 Mock 测试时不要求安装 Broker。

## 参考

- [Spring Boot AMQP](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)
- [RabbitMQ Windows 安装](https://www.rabbitmq.com/docs/install-windows)
- [RabbitMQ 与 Erlang/OTP 兼容矩阵](https://www.rabbitmq.com/docs/which-erlang)
- [RabbitMQ 网络端口](https://www.rabbitmq.com/docs/networking)
