# ADR-0002: Local infrastructure and OSS object storage

- 状态：Accepted
- 日期：2026-07-16

## 背景

当前开发机已经运行 MySQL、Redis 和 RabbitMQ，但不依赖 Docker。平台的生产对象存储确定使用阿里云 OSS，而早期设计稿中的本地 MinIO/Docker Compose 假设与当前环境不一致。

## 决策

- Java 继续以 JDK 17 作为本地和 CI 基线。外部基础设施只在显式启用 `local` profile 时连接，默认构建不要求任何本地服务。
- 本地复用 `127.0.0.1:3306` 的 MySQL 和 `127.0.0.1:6379` 的 Redis；连接参数和凭据只从环境变量读取。本任务只提供 JDBC/Redis 健康检查，不创建 Schema、业务表或业务接口。
- 各领域服务保持独立 MySQL Schema 边界；当前共享管理员账号只允许作为临时本地开发配置，后续应迁移到最小权限的服务独立账号。
- 生产对象存储采用阿里云 OSS，不引入 MinIO。`media-service` 拥有所有业务文件的上传会话、对象元数据和授权边界；T07 才实现 OSS SDK、短期直传/访问授权和通用资产状态机，课程封面、视频、题目附件、AI 图片、头像、资质材料、运营图片及导入/导出文件的完整收口见 [ADR-0004](./0004-oss-media-asset-lifecycle.md)。
- 长期 AccessKey 只能存在于服务端密钥管理或运行时身份中。浏览器不得获得长期 AccessKey；浏览器侧授权必须是后端签发、范围受限且短期有效的 URL 或 STS 凭据。
- 可靠事件统一采用 RabbitMQ 和 Spring AMQP，具体拓扑与投递语义见 [ADR-0005](./0005-rabbitmq-messaging.md)。
- 本地开发和 CI 不引入 Docker、Kubernetes 或其他系统软件。

## 后果

- 开发者需要在启动 `local` profile 前自行提供已有 Schema 名和本机凭据；仓库无法开箱创建数据库。
- MySQL/Redis Actuator 组件可验证连接状态，但不代表业务表、缓存策略或消息链路已经实现。
- OSS 无法在没有明确测试 Bucket 授权时做上传验证；T02 只验证配置边界。
- T07 已对 RabbitMQ 生产、消费、确认、有限重试、死信、幂等和 Outbox 补投完成真实环境验收；后续领域事件继续复用同一可靠消息约束。
