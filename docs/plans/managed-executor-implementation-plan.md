# ReachAI Managed Executor 实施计划

> 状态：P0-P4 源码与离线测试完成；P5 生产沙箱基线已落源码，目标集群、真实模型、浏览器、多租户攻击与扩展 Profile 仍待验收
>
> 更新时间：2026-08-24
>
> 当前证据：仓库已有 Codex app-server Worker、Runtime 状态机与 Worker API、Control/UI、AgentScope 异步委托、Kubernetes Job/NetworkPolicy/凭据 Broker 和 Artifact 留存实现；没有镜像 registry、Kubernetes、模型凭据或浏览器环境，因此部署态与生产安全证据仍为 `PENDING / NOT RUN`。

## 0. 当前交付矩阵

| 阶段 | Source/Contract | 离线验证 | 部署态结论 |
| --- | --- | --- | --- |
| P0 契约与威胁模型 | 完成 | 文档/路径/静态检查纳入收口 | 未代表生产安全 |
| P1 Worker | 完成 | TypeScript 编译、伪 app-server、协议、安全、Evidence 测试通过；Unix socket 用例需 Linux CI 再跑 | Image Built/真实 Codex/真实模型待验 |
| P2 Runtime | 完成 | 状态机、租约、审批、Artifact、outbox、cleanup、Kubernetes 适配器测试 | SQL 未执行，Runtime 未连接真实 Sandbox |
| P3 Control/UI | 完成 | 后端路由/服务测试、前端类型/组件/构建验证 | 真实浏览器创建到领域验收待验 |
| P4 AgentScope | 完成 | 三固定工具、双开关、生产变更拒绝、shadow Eval 和配置发布测试 | 生产自动路由保持关闭 |
| P5 生产基线 | 核心基线完成 | digest、RuntimeClass allowlist、静态/动态 NetworkPolicy、凭据 Broker、挂载隔离、RBAC/Quota 清单和过期读取测试 | CNI/gVisor/Kata/Relay/代理/攻击/擦除证据待验；Browser/MCP/Skill Script 仍锁定 |

## 1. 目标

建设 ReachAI Managed Executor，使用户可以在 ReachAI 内启动一个受治理、可观测、可取消的开放式执行任务，由按任务创建并销毁的 Codex harness Worker 在临时 Workspace 中完成代码阅读、诊断、修改、构建和测试。

完整目标同时要求：

1. 保留 GraphSpec 的确定性业务执行语义；
2. 保留 AgentScope 的业务 Supervisor、Workflow-as-Tool 和 A2A 编排职责；
3. Codex harness 只作为开放式 Workspace 执行器，不获得数据库或生产系统直连能力；
4. 执行、审批、Artifact、wall-time/资源上限、取消、清理和审计均由 ReachAI 控制面持有；模型 Token/费用硬预算由未来经部署验收的 Model Relay 持有；
5. 外部 AI Coding handoff 与平台托管执行显式区分；
6. 多租户不可信进程永远不在 `reachai-runtime-service` JVM 中运行；
7. 真实补丁、测试和浏览器证据由 Worker 或平台采集，不以模型自报代替。

## 2. 已确认的产品与架构决策

- 新增 `executionMode=EXTERNAL_CLIENT | MANAGED_SANDBOX`，与 `executorProvider=CODEX | CURSOR | TRAE | CLAUDE_CODE` 分离。
- 现有外部 AI Coding Task Protocol 保持可用；托管任务不签发伪 handoff，也不复用外部客户端 Bearer Token。
- Control 继续拥有用户任务、领域 Artifact、验收与 RBAC。
- Runtime 拥有 Managed Execution 技术状态机、Worker 租约、审批、wall-time/Artifact/集群容量上限、RunOps 和可靠事件投递；当前不把尚未验收的 Model Relay Token/费用限制计为已实现。
- Worker 是按任务创建的基础设施 Job，不是第六个 Spring Boot 业务服务，不拥有数据库。
- Worker 内的 Codex app-server 仅使用本地 stdio；不暴露远程 WebSocket。
- 首期关闭 app-server experimental API，禁止 `process/*`、`dangerFullAccess`、会话级永久批准和策略 amendment。
- 首期不经 `reachai-model-service` 转发 Codex。当前模型服务只证明了 Chat Completions，不具备已验证的 Responses/Codex 兼容层。
- 首期只返回 Patch、测试和证据，不 commit、push、开 PR、合并、部署或调用生产写能力。
- AgentScope 委托在 Direct UI 闭环之后实施，且必须是异步工具，不长时间占用原 Agent 请求。
- Skill Script 后续可复用沙箱基础设施，但使用独立 Profile 和授权；不能随 Managed Executor 自动开放。

权威目标契约见 [Managed Executor 架构契约](../architecture/managed-executor.md)，安全门禁见 [Managed Executor 威胁模型](../architecture/managed-executor-threat-model.md)。

## 3. 分阶段交付

### P0：契约、威胁模型与功能开关（源码完成）

交付物：

- 架构边界、状态机、事件 Schema 和接口契约；
- 沙箱 Profile 与资源上限；
- 威胁模型和攻击测试清单；
- 默认关闭的功能开关与项目 allowlist；
- Codex app-server 版本钉住和协议 Schema 生成约定。

完成门禁：

- 文档明确区分当前事实、目标契约和未运行验证；
- 不把 Codex 当成 GraphSpec 节点、A2A Agent 或 AgentScope 替代品；
- 不向 Worker 分发服务间共享密钥；
- `git diff --check` 和文档路径检查通过。

### P1：独立 Worker 垂直切片（源码完成）

交付物：

- TypeScript Worker；
- app-server stdio JSON-RPC 客户端；
- `initialize -> thread/start -> turn/start -> turn/completed` 生命周期；
- 审批请求捕获和 fail-closed 决策；
- 原始 app-server 事件到 `reachai.managed-execution.event.v1` 的安全投影；
- reasoning、Secret、绝对外部路径和超长输出过滤；
- Git Patch、验收命令和 Evidence Manifest 采集；
- 非 root、固定 Codex 版本的 Worker 镜像；
- 离线协议和安全单元测试。

完成门禁：

- 不需要 OpenAI 凭据即可运行全部单元测试；
- 伪 app-server 能证明握手、乱序响应、通知、审批、取消和异常退出行为；
- projector 不持久化 raw reasoning 或 command stdout；
- 默认审批 broker 返回 `cancel`；
- Artifact 的 SHA-256、base commit、命令 argv、exit code 和时间均来自 Worker 采集。

### P2：Runtime Managed Execution 控制面（源码完成）

交付物：

- `runtime_managed_execution`、event、artifact、outbox 表；
- MySQL 5.7 兼容状态机和乐观锁；
- Worker claim、heartbeat、事件批量回传、命令轮询、完成和取消协议；
- execution-scoped 短期身份；
- `SandboxBackendPort` 与 Kubernetes Job 实现；
- lease/heartbeat 过期、取消和 cleanup reconciler；
- `runtime_interaction_session(source_type=MANAGED_EXECUTOR)` 审批；
- `runtime_run(runtime_type=CODEX_HARNESS)` RunOps 投影。

完成门禁：

- 重复 create/event/complete/cancel 幂等；
- Worker 崩溃、Runtime 重启、租约过期和清理失败均有确定性恢复；
- RUNNING 后不做不安全的盲目自动重跑；
- Worker 不访问 MySQL，不持有 `REACHAI_INTERNAL_SERVICE_SECRET`；
- Runtime 关闭功能开关时不创建任何 Job。

### P3：Control Task Kernel 与管理端（源码完成，浏览器 E2E 待验）

交付物：

- AI Coding Task 增加 `executionMode`、`managedExecutionId`、`sandboxProfile`；
- 显式“开始平台托管执行”API；
- Runtime 到 Control 的 HMAC + outbox 领域事件；
- Task Kernel 状态映射；
- SSE 进度、审批卡片、Diff、测试和 Artifact 面板；
- managed 模式隐藏 handoff/restore，external 模式保持兼容。

完成门禁：

- 真实浏览器完成创建、启动、进度、审批、取消、结果和下载；
- Worker `SUCCEEDED` 只推进到领域 Artifact 校验，不直接写 `COMPLETED`；
- 前端不展示 raw reasoning、Secret 或未经净化的 ANSI/HTML；
- 外部 handoff 回归测试通过。

### P4：AgentScope 异步委托（源码完成，生产自动路由关闭）

交付物：

- `managed_executor.start/status/read_result` 三个固定内建工具；
- Agent 配置版本 allowlist 和 Profile/预算限制；
- 异步任务卡片与结果引用；
- 明确路由策略和 Eval 数据集。

完成门禁：

- `start` 不阻塞原 Supervisor HTTP/SSE 请求直到 Worker 完成；
- AgentScope 不能用模型参数扩大 Workspace、网络、预算或 Tool 权限；
- 生产写、发布、删除和不可逆操作仍只走受治理 Workflow；
- 自动路由默认关闭，只有评测门禁达标后可灰度。

### P5：生产沙箱与扩展场景（核心源码基线完成，部署与扩展 Profile 待验）

交付物：

- gVisor/Kata/微虚拟机生产后端；
- deny-all 网络、Model Relay、对象存储和依赖代理；
- Browser Acceptance Profile；
- 受限 MCP/Capability Gateway；
- Skill Script 独立沙箱项目；
- 运维、容量、留存、成本和事故 Runbook。

当前实现把未完成扩展 fail closed：Managed Execution create、Agent 配置发布、Worker schema 和 UI 类型只允许 `ANALYZE_READONLY | WORKSPACE_PATCH`；NetworkPolicy 不包含 MCP/Capability Gateway；Skill Script 不复用该授权。生产操作入口见 [Managed Executor 生产运行手册](../operations/managed-executor-production-runbook.md)。

完成门禁：

- 威胁模型中的攻击用例全部有部署态证据；
- 跨租户、容器逃逸、metadata、内网、Secret、磁盘/PID 炸弹均 fail closed；
- 沙箱终止和存储擦除可以审计；
- 真实项目 E2E 与业务验收分别留证。

## 4. 首个真实试点

首个试点使用 `CODE_IMPLEMENTATION + MANAGED_SANDBOX + WORKSPACE_PATCH`：

- 输入是固定 base commit 的 Git Workspace 快照；
- 工具链镜像固定 Java 17、Maven、Node 20、Git 和 Codex 版本；
- Shell 网络默认关闭；
- 只运行配置中明确给出的验收命令；
- 输出 `workspace.patch`、`test-report.json`、`execution-summary.json` 和 `evidence-manifest.json`；
- 不自动写回 Git 远端；
- 至少使用 20 个真实诊断/修复任务比较外部 Codex、Managed Codex 与现有 AgentScope。

PoC 目标限额：2 vCPU、4 GiB、10 GiB ephemeral storage、256 PID、30 分钟、50k Token、50 MiB Artifact、每用户 1 个并发、每项目 2 个并发。当前源码已覆盖 Pod 资源、PID、wall-time、Artifact 与全局并发；50k Token 需 Model Relay 硬门禁，每用户/每项目并发需 Runtime 配额扩展，二者在目标环境验收前均不得宣称已生效。

## 5. 验证分层

任何阶段都必须区分：

1. Source/Contract Ready；
2. Unit/Integration Verified；
3. Image Built；
4. Sandbox Deployed；
5. Runtime Connected；
6. Browser E2E Verified；
7. Multi-tenant Security Verified。

不能用编译、单元测试或模型自报替代后续层级。

## 6. 默认回滚策略

- `REACHAI_MANAGED_EXECUTOR_ENABLED=false` 禁止新建托管执行；
- `REACHAI_MANAGED_EXECUTOR_AUTO_ROUTE_ENABLED=false` 禁止 AgentScope 自动委托；
- 集群并发设为 `0` 停止新 Job；
- 已有 Job 可按策略完成或取消；
- 保留不可变事件和 Artifact 元数据，不在回滚时删表；
- 外部 AI Coding、AgentScope 和 GraphSpec 不依赖 Managed Executor，可独立继续运行。
