# Skeleton verification

在仓库根目录执行：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
python -m compileall -q edu_agent_app server.py
python -m unittest discover -s tests -v
python -m pip wheel --no-deps --wheel-dir .m2/python-wheels .
pnpm.cmd --dir web-apps install --frozen-lockfile
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run check:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
```

运行入口：

```powershell
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl gateway-service -am spring-boot:run
python -m edu_agent_app
pnpm.cmd --dir web-apps run mock:api
pnpm.cmd --dir web-apps run dev:student
pnpm.cmd --dir web-apps run dev:teacher
pnpm.cmd --dir web-apps run dev:admin
```

Java 领域服务将模块名替换为 `iam-service`、`course-service`、`media-service`、`learning-service` 或 `operation-service`。PowerShell 以外的 shell 去掉 Maven 命令中的 `--%`。Python 完整运行前必须先执行 `python -m pip install -e .`，再按 `.env.example` 配置并确保外部模型/索引资产存在。旧版 pip（21.3 以前）执行 wheel 命令时可加 `--use-feature=in-tree-build`，避免复制整个 Monorepo。

## 2026-07-16 验证记录

- Java：`clean verify` 通过，8 个 Reactor 项目全部成功，`shared-kernel` 1 个测试通过；Gateway 与五个领域服务分别启动后 `/actuator/health` 均返回 `UP`。
- Python：字节码编译、8 个既有单元测试及 wheel 构建通过；相邻 BGE/FAISS 资产路径已确认存在。当前系统 Python 未安装 `requirements.txt` 中的运行时依赖，因此未在该基础环境重复进行完整 HTTP 启动。
- Frontend：冻结锁文件校验、OpenAPI 类型生成、全部 workspace 类型检查、1 个 Vitest 测试及三个应用生产构建通过。
- Mock：Prism 成功加载同一 OpenAPI；登录与课程列表请求返回契约示例响应。

## T02 基础设施说明

- CI 使用 JDK 17、Python 3.9、Node 20.19.5 和 pnpm 10.22.0，分别缓存 Maven、pip 和 pnpm 依赖。
- Java 默认构建不激活 `local` profile；本地 MySQL/Redis 只读验证和 Actuator 检查见 [`docs/development/local-environment.md`](../development/local-environment.md)。
- `pnpm.cmd --dir web-apps run check:api` 会重新生成 OpenAPI 类型，并在生成文件相对 Git 存在差异时返回失败。
- Python CI 只编译、运行纯单元测试并构建 `--no-deps` wheel，不安装运行时依赖，也不下载模型或 FAISS 索引。

## 2026-07-16 T02 验证记录

- Java：JDK 17 下 `clean verify` 通过，8 个 Reactor 项目全部成功，`shared-kernel` 1 个测试通过；IAM 未启用 profile 时保持无外部依赖启动且健康状态为 `UP`，Gateway 以 `local` profile 启动后 `/actuator/health` 返回 `UP`，Redis 组件为 `UP`。
- Python：`compileall`、8 个既有单元测试和 wheel 构建通过。当前系统 pip 较旧，实际 wheel 命令增加了 `--use-feature=in-tree-build`；构建隔离只下载 setuptools，不安装运行时依赖，也未读取模型或索引资产。
- Frontend：pnpm 10.22.0 冻结安装、OpenAPI 生成一致性、全部 workspace 类型检查、1 个 Vitest 测试和学生/教师/管理三端生产构建通过；`esbuild` 原生脚本许可可执行。
- 本地基础设施：MySQL 3306 与 Redis 6379 TCP 连通，`redis-cli PING` 返回 `PONG`；Gateway Actuator 验证了 Redis。当前进程未提供 MySQL 凭据，因此没有执行认证后的 `SELECT 1` 或领域服务数据库健康检查，也没有读取或修改任何现有数据。
- CI 配置：`.github/workflows/ci.yml` 和全部 local YAML 已通过 YAML 解析，模块 POM 已通过 XML 解析；实际 GitHub-hosted runner 结果需在分支推送后观察。

## 2026-07-17 T04 验证记录

- 契约：OpenAPI 0.2.0 已定义登录、刷新、退出和当前用户，生成的 TypeScript 类型已更新；Prism 成功加载 6 条路由，登录 Mock 返回 200、Access Token 和 HttpOnly Refresh Cookie。
- Java：`clean verify` 的 8 个 Reactor 模块全部成功；随后增强的 Gateway 定向测试 5 个通过。IAM 共 9 个测试通过，覆盖 BCrypt 登录、错误密码、禁用账号、失败限流、Refresh 过期/轮换/复用、退出、Flyway/MyBatis 和 Seed 幂等清理。
- 数据库：只在测试进程中创建 H2 MySQL 模式的 `iam_test` 与 `iam_seed_test` 内存数据库，Flyway 创建 7 张 IAM 表；没有连接或修改本机来源不明的 MySQL 数据库。真实 MySQL 迁移仍需在用途明确的 IAM 专用 Schema 中验收。
- Frontend：pnpm 10.22.0 冻结安装、OpenAPI 生成、全部 workspace 类型检查、10 个 Vitest 测试和学生/教师/管理三端生产构建通过。测试覆盖三角色登录、错误状态、单次 401 刷新重放和路由守卫。
- Python：源码字节码编译和 8 个既有单元测试通过；旧版 pip 使用 `--use-feature=in-tree-build` 后 wheel 构建成功，未修改 Python Agent 源码。
