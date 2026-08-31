# ReachAI Managed Executor 架构契约

状态：P0-P4 已达到源码与离线测试就绪；P5 已实现 Kubernetes fail-closed 基线、凭据 Broker、显式 egress 和 Artifact 留存边界。镜像、集群、真实模型、浏览器与多租户攻击验收仍为 `PENDING / NOT RUN`，不表示当前部署已经具备托管执行。

日期：2026-08-24

## 1. 定位

Managed Executor 是 ReachAI Runtime 拥有的开放式执行控制面。它把需要 Workspace、Shell、构建、测试和未知环境探索的任务委托给按任务创建并销毁的 Worker。

它不替代：

- AgentScope Supervisor 的业务意图理解、Workflow/A2A 选择和有限重规划；
- Workflow GraphSpec 的确定性运行语义；
- Capability、Tool ACL、Guard、Interaction 和人工审批；
- Control 的平台身份、AI Coding Task Kernel 和领域验收。

## 2. 当前事实与目标增量

当前已有：

- Control AI Coding Task Kernel：外部客户端 handoff、短期 Task Token、事件、问题、Artifact 和服务端状态机；
- Runtime AgentScope Supervisor：受限 Workflow/A2A Tool、策略确认、Trace 和 RunOps；
- Runtime Interaction：等待用户、确认、恢复、幂等和审计；
- Agent Skill：标准包可导入和渐进加载，但脚本执行保持关闭。

本轮已落入源码但尚未部署验收：

- 独立 Codex app-server Worker 与 Runtime Worker 协议；
- Managed Execution、event、Artifact、outbox、Control inbox、Interaction 和 RunOps 投影；
- Control 管理端显式启动、SSE、审批、取消、Artifact 下载和领域投影；
- AgentScope 三个异步固定工具、配置版本策略、自动路由双开关和 Eval shadow；
- Kubernetes Job/Secret/NetworkPolicy 创建与终态清理适配器；
- namespace 静态默认拒绝、每 execution 显式 egress、gVisor/Kata allowlist、最小 RBAC 和资源上限清单；
- execution 凭据 Broker sidecar：原始 token 不进入 Worker/Codex 容器；
- Artifact 留存截止时间与过期读取拒绝；

尚未获得：

- 经部署态攻击测试证明的生产多租户沙箱；
- 真实部署、浏览器或攻击测试证据。

## 3. 执行层分工

| 执行层 | 输入和边界 | 主要输出 | 允许的副作用 |
| --- | --- | --- | --- |
| GraphSpec | 发布版本、确定性节点和受治理端口 | 业务结果、节点事件、Trace | 只按已发布 Workflow 与 Tool Policy |
| AgentScope | Agent 配置版本、Workflow/A2A allowlist | 计划、委托结果、最终答复 | 只调用已绑定执行工具 |
| Managed Executor | 不可信任务目标、Workspace snapshot、Sandbox Policy | Patch、测试、Evidence、诊断报告 | 仅临时 Workspace；业务动作仍走受治理 Gateway |

## 4. 部署与信任区

```text
Browser / External Caller
        |
        v
reachai-control-service
        | signed internal request
        v
reachai-runtime-service ---- MySQL / Runtime Artifact metadata
        |
        +--------------------> S3/MinIO Artifact body
        |
        | provision job + execution-scoped identity
        v
Sandbox Job / microVM
  +----------------------------------+
  | token-only credential broker     |----> Runtime worker API
  |              ^ Unix socket only  |
  | Managed Executor Worker          |
  |  -> Codex app-server stdio       |
  |  -> temporary /workspace         |
  |  -> deterministic evidence       |
  +----------------------------------+
        |
        +--> task-scoped Model Relay
        +--> Runtime worker API (through credential Broker)
        +--> approved Git/dependency proxies
```

Worker 是不可信执行区：

- 无数据库账号；
- 无服务间共享 HMAC Secret；
- 无 Kubernetes ServiceAccount Token；
- 无宿主机目录、Docker socket、host network/PID/IPC；
- Worker/Codex 不持有原始 execution token；只有同 Pod、不同 UID 的 Broker 挂载 token，并只代理单一 execution 的固定 Runtime 路由；
- Broker 固定限制 4 个在途请求、600 次/分钟、8 个 socket 连接和 120 秒请求/上游超时；Runtime 与各 egress gateway 仍须执行 execution/tenant 级限流；
- 网络只允许受限 DNS、Runtime、Model Relay、Git proxy 和依赖代理；Worker 不直连对象存储。

## 5. 产品任务与技术执行分离

AI Coding 任务新增：

```text
executionMode = EXTERNAL_CLIENT | MANAGED_SANDBOX
executorProvider = CODEX | CURSOR | TRAE | CLAUDE_CODE
```

`executorProvider` 回答“使用哪类执行器”，`executionMode` 回答“由外部会话还是 ReachAI 托管”。二者不得合并。

Control 任务只保存一个当前 `managedExecutionId`。任务需要从干净基线重试时创建新的任务；Runtime 不在执行可能已产生 Workspace 修改后盲目自动重跑。

## 6. Runtime 状态机

```text
REQUESTED -> QUEUED -> PROVISIONING -> RUNNING
RUNNING <-> WAITING_APPROVAL
RUNNING <-> WAITING_USER
RUNNING -> FINALIZING -> SUCCEEDED

REQUESTED/QUEUED/PROVISIONING/RUNNING/WAITING_* -> CANCELLING -> CANCELLED
QUEUED/PROVISIONING/RUNNING/FINALIZING -> FAILED | TIMED_OUT
```

以下字段与 execution status 分离：

- `cleanupStatus=PENDING|RUNNING|COMPLETED|FAILED`；
- Worker lease owner、heartbeat 和 lease expiry；
- Artifact validation/scan status；
- Control domain task status。

## 7. Control 状态映射

| Runtime | Control AI Coding Task |
| --- | --- |
| QUEUED / PROVISIONING / RUNNING | RUNNING |
| WAITING_APPROVAL / WAITING_USER | WAITING_USER |
| ARTIFACT_READY | RESULT_SUBMITTED |
| FAILED / TIMED_OUT | FAILED |
| CANCELLED | CANCELLED |

`SUCCEEDED` 不直接映射 `COMPLETED`。Runtime 先可靠投递 Artifact；Control Task Kind Provider 校验、应用并检查 readiness 后，才能进入 `RESULT_APPLIED`、`ACCEPTANCE_READY` 或 `COMPLETED`。

## 8. 持久化归属

Runtime 拥有：

- `runtime_managed_execution`；
- `runtime_managed_execution_event`；
- `runtime_managed_artifact`；
- `runtime_managed_execution_outbox`。

Control 仅在 `control_ai_coding_task` 保存执行模式、Profile 和 Runtime execution 引用，通过 internal API 读取 Runtime 事实，不跨服务读 Runtime 表。

大 Artifact 进入 S3/MinIO。MySQL 只保存对象 key、digest、大小、媒体类型、扫描和留存元数据。

## 9. 标准事件契约

Worker 到 Runtime 的事件 envelope：

```json
{
  "schema": "reachai.managed-execution.event.v1",
  "executionId": "mex_...",
  "sequence": 1,
  "eventId": "mex_...:1",
  "occurredAt": "2026-08-24T00:00:00.000Z",
  "type": "TURN_STARTED",
  "phase": "RUNNING",
  "visibility": "OPERATOR",
  "persistence": "DURABLE",
  "message": "Codex turn started",
  "data": {}
}
```

约束：

- `sequence` 在单 execution 内严格递增；
- Runtime 以 `(execution_id, sequence)` 和 `(execution_id, event_id)` 双重幂等；
- payload 深度、数组、字符串和总字节数有上限；
- raw reasoning、stdout/stderr delta、Secret、Cookie、Authorization 和外部绝对路径不得持久化；
- 未识别 app-server 消息默认丢弃，只记录方法名级指标，不透传 payload；
- `FINAL_MESSAGE` 仍是非可信模型输出，不能充当测试或验收证据。

## 10. Worker 协议

Worker 只通过 execution-scoped Unix socket 调用凭据 Broker；Broker 注入短期身份并代理：

```text
claim
heartbeat
events:batch
artifacts/{fixed-type} upload
commands?afterSequence=...
complete
```

Runtime 通过 command polling 返回：

- `CANCEL`；
- `APPROVAL_DECISION`；

运行中方向修正和有界用户回答当前不在首期协议中；未来若引入，必须增加独立 Schema、身份绑定、幂等与提示注入测试。

Worker 不接受 Runtime 主动连接，避免为每个不可信 Job 暴露入站服务。

每次 Artifact 上传使用 Runtime 生成的唯一对象键。并发唯一键冲突只清理本次上传对象，再按已落库的 artifact ID、digest、size 和 media type 返回幂等结果，不能删除胜出方证据。Runtime 在完成前会从对象存储重新读取五类证据并复核 bundle。

## 11. Codex app-server 适配

生产 Adapter 使用发布版 Codex CLI/app-server，不 fork 源码：

1. 固定 Worker image digest 与 Codex 版本；
2. 对相同版本运行 `codex app-server generate-json-schema`；
3. app-server 仅监听 stdio；
4. `initialize` 后发送 `initialized`；
5. `thread/start` 使用 ephemeral thread、`approvalPolicy=on-request`、`approvalsReviewer=user`；
6. `turn/start` 传入显式 `sandboxPolicy`；
7. 禁止 experimental capabilities 和 `process/*`；
8. command/file approval 只接受一次性 `accept|decline|cancel`；
9. `acceptForSession`、exec policy amendment 和网络永久 amendment 首期拒绝；
10. `turn/interrupt` 是取消当前 turn 的标准路径。

官方协议依据：

- [Codex App Server](https://learn.chatgpt.com/docs/app-server)
- [Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)
- [Codex Sandboxing](https://learn.chatgpt.com/docs/sandboxing)

## 12. Sandbox Profile

### `ANALYZE_READONLY`

- 源码不可修改；
- scratch 和 Artifact 目录可写；
- 命令网络关闭；
- 完成时必须证明源码 diff 为空。

### `WORKSPACE_PATCH`

- 仅临时 Workspace 可写；
- 命令网络关闭；
- 可执行固定工具链和配置的验收命令；
- 最终只返回 patch 与 Evidence，不写 Git 远端。

### `BROWSER_ACCEPTANCE`

- 当前 Managed Executor API 明确拒绝，不能从任务类型或 Agent 配置推断为已开放；
- 只允许访问固定预览 Origin；
- 浏览器上下文和业务测试身份独立注入并在任务后销毁；
- 截图、Network 和操作记录进入验收 Artifact。

### `SKILL_SCRIPT`

- 当前 Managed Executor API 明确拒绝；它是独立安全项目；
- 不能复用 `WORKSPACE_PATCH` 的授权结论；
- 需要脚本级 allowlist、包 digest、评审版本和攻击测试证据。

## 13. AgentScope 委托边界

当前只注册三个固定 Runtime Tool：

- `managed_executor.start`；
- `managed_executor.status`；
- `managed_executor.read_result`。

`start` 只创建异步 execution 并返回任务卡片。AgentScope 不等待 Worker 长时间完成，也不能从模型参数指定任意镜像、挂载、网络或预算。

自动路由默认关闭。已知确定性业务任务优先 GraphSpec；受限多工具任务由 AgentScope；需要文件、Shell、构建、测试和环境探索的任务才进入 Managed Executor。

生产发布、部署、push、删除、数据库破坏性操作等意图不会暴露 `start`，即使模型直接构造调用也会被服务端再次拒绝。Eval shadow 可以评估路由，但只生成 `mex_eval_*` 模拟卡片，不创建 execution、Job 或 Artifact。

## 14. Kubernetes 凭据与网络边界

- Secret 中 token 与验收配置通过不同 projected volume 暴露；Worker 只挂载验收配置，Broker 只挂载 token。
- Broker、Worker、Git init 使用不同 UID，共享组只用于 Unix socket；Worker 对 socket volume 使用只读挂载。
- Broker 在路由解析前执行本地并发/速率门禁，未知或畸形请求同样计入配额，防止不可信 Workspace 借 execution token 代理面放大流量。
- Git credential volume 只进入 workspace init；Broker/Worker 均不挂载。
- namespace 必须预装 `default-deny-all`。Runtime 每次 provision 前回查该策略、RuntimeClass、Worker ServiceAccount、Codex ConfigMap 和 Git Secret。
- per-execution NetworkPolicy 空 ingress，egress 只有受限 DNS proxy，以及 Runtime、Model Relay、Git proxy、依赖代理的 namespace/app/port 组合；不生成 `0.0.0.0/0`。
- Codex ConfigMap 必须 credential-free；Model Relay 身份由目标环境工作负载身份建立，不能把长期 API key 下发给 Worker。
- Worker 只接受首期固定 TOML 字段集合，且 Model Relay `base_url` 的 host/port 必须与 Runtime operator policy 精确一致；quoted key、inline table、MCP/hook 和未知字段 fail closed。
- Git init 使用共享组与 `umask 0002` 创建 Workspace，保证不同 UID 的 Worker 可写，但 Git credential volume 仍只对 init container 可见。

生产操作与证据门禁见 [Managed Executor 生产运行手册](../operations/managed-executor-production-runbook.md)。

## 15. 功能开关

默认值必须 fail closed：

```text
REACHAI_MANAGED_EXECUTOR_ENABLED=false
REACHAI_MANAGED_EXECUTOR_AUTO_ROUTE_ENABLED=false
REACHAI_MANAGED_EXECUTOR_ALLOWED_PROJECTS=
REACHAI_MANAGED_EXECUTOR_ALLOWED_PROFILES=ANALYZE_READONLY
REACHAI_MANAGED_EXECUTOR_MAX_CLUSTER_CONCURRENCY=0
```

关闭开关不得影响外部 AI Coding、AgentScope、Workflow 或 GraphSpec。
