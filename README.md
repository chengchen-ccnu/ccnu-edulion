# EDU 在线网课平台

本仓库正在按 T01 + T03 建立在线网课平台 Monorepo 基线：

```text
java-platform/   Gateway、五个领域服务与 shared-kernel 的 Maven 多模块工程
edu_agent_app/   已有 Python 数学智能体（原地复用，不重新实现）
web-apps/        学生端、教师端、管理端与共享 packages
docs/            OpenAPI、事件、前端、ADR 与路线图事实来源
```

仓库级规则和完整验证命令见 `AGENTS.md`，首版 API 见 `docs/api/openapi.yaml`。本轮没有实现登录或课程业务，也没有引入支付、Elasticsearch 或 Kubernetes。

## Python Agent（精简拆分版）

这是从相邻旧项目 `../edu_agent/智能体项目` 拆分出的核心服务。代码放在当前项目，大型题库、FAISS 索引、BGE 模型和 EduAgentV7 权重继续按路径复用，避免重复复制约 GB 级资产。

## 目录

```text
edu_agent_app/
  config.py       环境变量、模型和索引加载
  state.py        LangGraph 状态与结构化输出
  policy.py       教学降级顺序与模型调用预算
  retrieval.py    BM25 + FAISS 混合检索、重排、阈值、Neo4j
  workflow.py     两级检索回退、分步教学、循环保护
  api.py          FastAPI、SSE、会话中断与恢复
  __main__.py     标准模块与 console script 入口
server.py         兼容旧启动方式的入口
tests/            不依赖外部数据库的核心规则测试
```

## 检索设计

保留原来的两级设计，但修正“检索失败就等于非数学问题”的误判：

1. 题库执行 BM25 关键词召回和 FAISS 向量召回。
2. 合并候选，以 `向量 0.55 + BM25 0.35 + 术语覆盖 0.10` 重排并应用阈值。
3. 题库没有可靠知识点时，让 LLM 预估可能的规范知识点名称。
4. 使用“原问题 + 预估知识点”在知识点库再次执行混合检索和重排。
5. 两个库都失败时单独判断数学领域：
   - 高置信度非数学问题：礼貌拒绝。
   - 仍属于数学但超出索引覆盖：标记为 `math_fallback`，允许低置信度直接解题。

默认阈值只是初始值，必须用真实题目评测集校准。检索跟踪结果会保存到 LangGraph 状态的 `retrieval_trace`，便于分析误召回和漏召回。

## 防止无限循环

同一步骤的补救顺序固定为：

1. 换一种更直白的表述；
2. 补充简单例子；
3. 回退讲解前置知识；
4. 学生仍不理解时停止循环，建议教师介入或稍后复习。

另外设置：

- `MAX_STEP_RETRIES`：单步骤最大补救次数，默认 3；
- `MAX_LLM_CALLS`：同一 `thread_id` 的模型调用预算，默认 30；
- LangGraph `recursion_limit=80`：最后一道图执行保险。

## 运行

```powershell
Copy-Item .env.example .env
conda activate langgraph
python -m pip install -e .
python -m edu_agent_app
```

`.env` 中至少需要配置模型 API 密钥。默认资产路径就是当前目录旁边的旧项目；如果移动了数据，请修改 `EDU_AGENT_ASSET_ROOT` 和 `BGE_MODEL_PATH`。

没有配置 `DATABASE_URL` 时，会使用内存 checkpoint，重启后会话消失；配置 PostgreSQL 后可以持久化会话。Neo4j 密码留空时，系统仍可运行，但第三次补救只能让模型推断前置概念。

## 测试

```powershell
conda run -n langgraph python -m unittest discover -s tests -v
```
