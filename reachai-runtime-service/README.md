# ReachAI Runtime Service

`reachai-runtime-service` 是 ReachAI Runtime Host，默认端口 `18604`。它拥有 Agent、Workflow、GraphSpec、执行、交互、Trace/RunOps、EvalOps 和运行期状态；管理端通过 Control 使用这些公共能力，不直接访问本端口。

## 职责边界

本服务拥有：

- `runtime_agent` 稳定 Agent 及不可变配置版本；
- Workflow working copy、发布版本、GraphSpec/Canvas、资源绑定和凭据；
- AgentScope Supervisor、Workflow-as-Tool 选择、有限重规划和策略执行；
- GraphSpec executor、节点能力目录、调试、暂停/恢复和人工确认；
- Runtime 会话状态、上下文工程、Tool 结果卸载与保留/Legal Hold；
- 根 Run、Trace Span、Tool/Guard 证据、回放、比较与诊断；
- Agent Eval 与 EvalOps v2 的数据集版本、评估器、实验、任务和得分；
- Agent Skill 精确绑定、状态复核后的内容寻址缓存和 AgentScope 载入；
- A2A 本地执行和远端委派的 Runtime adapter。

本服务不拥有平台用户/Skill 目录/A2A 产品数据（Control）、Capability 目录（Capability）、知识数据（Knowledge）或模型实例（Model）。同库阶段也必须通过 internal API/client 协作。

## 核心执行链路

```text
Agent
  -> ACTIVE Agent 配置版本
  -> AgentScope Supervisor
  -> 0 / 1 / 多个固定版本 Workflow-as-Tool
  -> GraphSpec executor
  -> Capability / Knowledge / Model / Page Bridge / Remote Agent
  -> Run + Trace + Guard/Tool evidence
```

`GraphSpec` 是 Workflow 的运行语义；`canvas_json` 只保存布局。Runtime 只执行发布版本快照，不能在调用时静默切到最新 Workflow。

## 主要接口

这些路径由 Runtime owning，但管理端调用应先经过 Control：

| 路径族 | 用途 |
| --- | --- |
| `/api/agents/**` | Agent 身份、配置版本和精确绑定 |
| `/api/workflows/**` | Workflow CRUD、working copy、节点目录、发布与版本 |
| `/api/workflows/studio/**` | 调试、AI Proposal generate/edit |
| `/api/runtime/agents/execute*` | Supervisor 同步、详细和 SSE 执行 |
| `/api/runtime/debug-sessions/**`、`/api/runtime/interactions/**` | GraphSpec 调试和暂停/恢复 |
| `/api/traces/**`、`/api/runops/**`、`/api/trace-center/**` | Trace、RunOps、诊断、比较和回放 |
| `/api/runtime/evals/**` | Agent Eval 与 EvalOps v2 |
| `/internal/runtime/**` | Control/其他 owning service 使用的 HMAC internal contract |

精确 owner/consumer 见 [Internal API Contracts](../docs/architecture/internal-api-contracts.md)。

## 下游依赖

| 变量 | 默认本地目标 | 用途 |
| --- | --- | --- |
| `CONTROL_SERVICE_URL` | `http://localhost:18603` | Page Bridge、Skill 目录复核、A2A 远端目录等 Control-owned 事实 |
| `CAPABILITY_SERVICE_URL` | `http://localhost:18605` | 能力目录、运行时调用投影与执行 |
| `KNOWLEDGE_SERVICE_URL` | `http://localhost:18602` | 结构化检索 hits、记忆投影 |
| `MODEL_SERVICE_URL` | `http://localhost:18601` | Chat、Embedding/Rerank 相关模型调用 |

Runtime 不直接复用其他服务的 Mapper、Entity 或业务实现类。

## 主要代码区域

| Package | 责任 |
| --- | --- |
| `runtime.agent` | Agent 与配置版本、Skill 绑定 |
| `runtime.workflow` / `runtime.api` | Workflow 生命周期、Studio 与公共 facade |
| `runtime.execution` | GraphSpec 执行、节点 kernel、事件和 Trace 投影 |
| `runtime.supervisor` | AgentScope Supervisor、策略、审批和执行证据 |
| `runtime.runops` / `runtime.trace` | 根 Run、Span、查询、诊断、回放和比较 |
| `runtime.eval` | Agent Eval 与 EvalOps v2 |
| `runtime.memory` | 会话状态、保留、Legal Hold 和擦除 |
| `runtime.client` / `runtime.internal` | owning service client 与 internal API |
| `runtime.a2a` | A2A 执行/委派 Runtime adapter |

## 配置

配置事实源是 `src/main/resources/application.yml`：

- 数据库和服务 URL：`AI_MYSQL_*`、`*_SERVICE_URL`；
- 内部认证/传输：`REACHAI_INTERNAL_*`；
- Workflow 凭据：属性 `agent.workflow-credential-secret`，环境变量使用 Spring relaxed binding 的 `AGENT_WORKFLOW_CREDENTIAL_SECRET`；默认本地模式使用公开开发值，生产/Kubernetes 必须注入；
- Skill cache：`RUNTIME_SKILL_*`；生产必须使用受控、持久、可写目录；
- 会话与上下文：`RUNTIME_SESSION_MEMORY_*`、`RUNTIME_CONTEXT_*`；
- HTTP egress：`RUNTIME_HTTP_EGRESS_*`；
- 保留与交互 worker：`RUNTIME_SESSION_RETENTION_*`、`RUNTIME_INTERACTION_EXPIRY_*`。

启用生产 worker 或持久化能力前，必须先完成相应 SQL、密钥、存储和目标环境演练。

## 构建、启动与验证

```powershell
mvn -pl reachai-runtime-service -am -DskipTests compile
mvn -pl reachai-runtime-service -am test
Set-Location reachai-runtime-service
mvn spring-boot:run
```

健康检查：`GET http://localhost:18604/actuator/health`。

```powershell
node scripts/check-internal-api-contracts.mjs
node scripts/check-service-table-ownership.mjs
node scripts/check-backend-domain-dependencies.mjs
```

EvalOps、A2A 和 Agent Skill 在当前共享工作树中仍有演进内容；必须在稳定源码快照重跑目标/全量测试，并将构建、SQL、进程和浏览器证据分开记录。

## 进一步阅读

- [Workflow 语义契约](../docs/architecture/workflow-semantic-contract.md)
- [Agent Supervisor Runtime](../docs/architecture/agent-supervisor-runtime.md)
- [Workflow INTERACTION Runtime](../docs/architecture/workflow-interaction-runtime.md)
- [Runtime 上下文工程](../docs/architecture/runtime-context-engineering.md)
- [运行治理与开放协议](../docs/04-运行治理与开放协议.md)
