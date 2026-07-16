# ADR-0001: Monorepo and runtime baseline

- 状态：Accepted
- 日期：2026-07-16

## 决策

采用单仓库三栈结构：`java-platform` Maven 多模块、根目录现有 Python Agent 包、`web-apps` pnpm workspace。外部 REST 契约由 `docs/api/openapi.yaml` 单一管理，浏览器统一经 Gateway。

Java 本地可验证基线暂设为 Java 17、Spring Boot 4.1.0、Spring Cloud 2025.1.2，因为当前工作站只有 JDK 17，且该组合官方最低要求为 Java 17。总体架构的生产建议仍是 Java 21 LTS；升级 JDK 时不得改变 API 语义，并应在独立基础设施任务完成 CI/toolchain 验证。

Python Agent 保持现有源码位置和行为，只增加标准模块/console 入口；不移动大型模型和索引资产。前端使用 Vue 3 + Vite，OpenAPI TypeScript 类型与 Prism Mock 均读取同一 Schema。

## 后果

- 各 Java 服务可独立打包运行，但本轮没有数据库、服务注册或业务 Controller。
- Python 完整启动仍依赖 `.env`、模型、FAISS 索引和可选外部服务；无外部资产的验证范围是编译与现有纯单元测试。
- T04/T05 可分别实现 IAM 与课程契约，任何字段变化必须先更新契约。
