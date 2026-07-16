# Domain event contracts

T01 + T03 不创建尚未评审的业务事件。本目录保留为后续事件 Schema 的唯一事实来源。

每个事件文件必须版本化，并包含统一信封：`eventId`、`eventType`、`eventVersion`、`aggregateId`、`occurredAt`、`traceId`、`payload`。生产者使用本地事务 + Outbox；消费者按 `eventId` 幂等，禁止通过事件共享整表快照。
