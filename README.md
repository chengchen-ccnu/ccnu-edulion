# EDU 在线网课平台

本仓库是在线网课平台 Monorepo：

```text
java-platform/   Gateway、领域服务、Agent Access 与 shared-kernel
edu_agent_app/   T13 动态数学 Agent 在线运行时和独立索引 Worker
web-apps/        学生端、教师端、管理端与共享 packages
docs/            OpenAPI、事件、架构、交付与路线图事实来源
```

仓库规则和完整验证命令见 `AGENTS.md`，本地依赖与凭据约定见
`docs/development/local-environment.md`。外部 REST API 以 `docs/api/openapi.yaml` 为唯一事实源，
Java 与 Python 内部协议以 `docs/api/agent-internal.yaml` 为事实源。

## Python Agent

在线入口实现 T13.3 动态五角色运行时：Coordinator 先做规则＋结构化模型意图/槽位识别，
然后在 Math Tutor、Practice Coach、Study Planner、Platform Help 中选择一个专家；一次最多处理
两个有序子请求，工具、模型、转交和时限都受 T13.1 每轮预算约束。Platform Help 在 T13.4 前
明确不可用。旧固定认知诊断、固定解题图、FAISS、人工教师转交、PostgreSQL checkpoint 和
无界递归已从在线实现及依赖中移除。

```text
edu_agent_app/
  api.py                 T13.1 内部 HTTP/SSE、取消、冲突与安全错误
  config.py              百炼模型和 T13.2 在线检索装配
  runtime/               意图、预算、五角色、只读工具、多轮压缩和候选记忆
  indexing/              Qdrant/Neo4j 混合检索与独立 Index Worker
  __main__.py            在线服务和 index-worker 双启动入口
tests/                    契约、运行时、检索和 Worker 测试
```

在线检索严格复用 T13.2 的 Qdrant dense＋multilingual BM25＋RRF＋本地 reranker；Neo4j 只做
有界前置关系增强。课程候选在进入模型前必须通过 Java 复核适配器；当前冻结契约尚未提供该
回调端点，因此未配置复核器时 fail-closed 剔除课程候选，不退化为全库搜索。

## 本地运行

先按本地环境文档初始化安全配置，再通过统一包装器运行：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    python -m pip install -e .
    python -m edu_agent_app
}
```

在线服务监听 8000，只供 `agent-access-service` 调用。聊天和视觉分别读取
`CHAT_MODEL_NAME`、`VISION_MODEL_NAME`；API Key 只从安全包装环境注入。独立索引 Worker：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    python -m edu_agent_app index-worker
}
```

## 测试

```powershell
python -m compileall -q edu_agent_app server.py tests
python -m unittest discover -s tests -v
python -m pip wheel --no-deps --wheel-dir .m2/python-wheels .
```
