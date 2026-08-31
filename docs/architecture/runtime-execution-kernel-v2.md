# Runtime 执行内核契约

状态：Accepted
日期：2026-08-24

## 1. 目标与边界

本契约把 ReachAI 的 Workflow 执行收口为稳定门面、确定性编译、显式节点处理器、类型化 I/O、类型化事件、统一 Trace 投影和版本化断点。它不改变 GraphSpec 的产品语义，不把 Canvas 变成运行输入，也不替代 AgentScope Supervisor。

调用边界保持为：

`Agent / Studio / Embed -> RuntimeGraphSpecExecutor -> GraphSpecCompiler -> Handler Registry -> Node Handler -> typed Port`

`RuntimeGraphSpecExecutor` 是调用方可依赖的稳定门面；具体执行位于 `execution/kernel`。后续更换执行引擎时，门面、GraphSpec 和业务调用方不随内部实现一起重写。

## 2. 编译与节点调度

`GraphSpecCompiler` 每次执行先完成 JSON 解析和防御性校验，并生成不可变 `ExecutableGraph`：

- `schemaVersion=2`；
- entry、exit、node、edge 引用完整；
- 节点类型使用规范值；
- `TOOL` 引用满足当前 Capability 契约；
- `nodesById`、exit 集合和执行入口在进入运行循环前固定。

`RuntimeNodeHandlerRegistry` 是不可变注册表。启动构造时要求注册集合与 `handledNodeTypes()` 完全一致，缺处理器、重复处理器或意外处理器直接失败，不允许落入静默 `switch default`。

当前实际开放的 15 类处理器为：`USER_INPUT`、`INTENT_CLASSIFIER`、`IF_ELSE`、`PARAMETER_EXTRACT`、`ANSWER`、`LLM`、`TOOL`、`PAGE_ACTION`、`INTERACTION`、`VARIABLE_ASSIGN`、`TEMPLATE`、`VARIABLE_AGGREGATOR`、`KNOWLEDGE_RETRIEVAL`、`HTTP_REQUEST`、`LOOP`。协议枚举中其余节点只有在真实 Handler、发布校验和 Studio 配置同时完成后才能开放。

节点实现按运行语义拆为 5 个处理器族，并共享唯一变量/模板解析器：

| 处理器 | 负责节点 |
| --- | --- |
| `RuntimeDeterministicNodeHandlers` | `USER_INPUT`、`IF_ELSE`、`ANSWER`、`VARIABLE_ASSIGN`、`TEMPLATE`、`VARIABLE_AGGREGATOR` |
| `RuntimeModelNodeHandlers` | `INTENT_CLASSIFIER`、`PARAMETER_EXTRACT`、`LLM` |
| `RuntimeIoNodeHandlers` | `KNOWLEDGE_RETRIEVAL`、`HTTP_REQUEST` |
| `RuntimeActionNodeHandlers` | `TOOL`、`PAGE_ACTION`、`INTERACTION` |
| `RuntimeLoopNodeHandler` | `LOOP` 配置、迭代隔离与结果聚合 |

`RuntimeNodeValueResolver` 是模板、上下文路径、`var` 业务变量和结构化 `input` 的唯一解析实现；节点处理器不得复制一套近似规则。`RuntimeTrustedExecutionContexts` 是服务端身份和 Eval 标记的唯一恢复入口，客户端字段不能提升信任。

`RuntimeGraphSpecExecutionEngine` 只负责图推进、统一重试/错误策略、节点边界取消、Trace/事件、上下文提交和循环子图驱动。`RuntimeLoopNodeHandler` 通过受限回调执行循环体，不能绕过 Engine 的节点策略。所有注册使用显式 method reference 指向处理器；禁止再把叶子节点实现写回 Engine。

## 3. Capability 类型化端口

Runtime 到 Capability 的规范路由 `POST /internal/capability/invocations` 使用 Contract V1；
`POST /internal/capability/tools/{qualifiedName}/execute` 只保留滚动升级兼容适配：

- 请求包含 `contractVersion`、`invocationId`、`qualifiedName`、`input`、`context`、可选约束、Eval 策略、deadline、幂等键和 trace context；
- 响应固定为 `SUCCEEDED / BUSINESS_FAILED / REJECTED / TECHNICAL_FAILED`；
- 失败分类固定区分目录缺失、禁用、策略拒绝、身份缺失、配置错误、业务响应、超时、连接、非法响应和内部错误；
- 仅 `TIMEOUT / CONNECTIVITY` 的技术失败可声明重试；业务失败和拒绝不得重试；
- Runtime 校验 `invocationId` 与 `qualifiedName` 相关性、status/success 一致性和重试策略；
- HMAC 签名实际发送的精确 JSON 字节和规范新路径，Capability 同时校验调用方、身份绑定、body 摘要、时间窗与 nonce；
- Capability 应用服务依次执行资产解析、限制性策略链、唯一 Invoker 选择、结果分类；目录 HTTP Tool 复用既有 SDK/OpenAPI 签名执行器，内核资产由显式 Invoker 承接，未知或多重匹配一律失败关闭。

旧 Map 调用只保留在进程内兼容适配层，执行内核不再通过字符串猜测跨服务结果语义。

## 4. 执行事件与 Trace

GraphSpec Runtime 实时发出 `RuntimeExecutionEvent` schema V1：

- Run：`RUN_STARTED / RUN_SUSPENDED / RUN_COMPLETED / RUN_FAILED / RUN_CANCELLED`；
- Node：`NODE_STARTED / NODE_DELTA / NODE_COMPLETED / NODE_SUSPENDED / NODE_FAILED`。

事件有严格递增 sequence、事件 ID、run/trace/workflow 相关键、节点身份、状态、耗时、attempt、code 和白名单属性。事件中禁止 prompt、用户原文、检索证据、Tool 参数、HTTP body、凭据或模型 delta 原文；`NODE_DELTA` 只记录字符数。

`RuntimeExecutionTraceProjector` 是 Workflow 节点 Trace 的统一结构来源。它校验事件顺序，并把事件确定性投影为 `workflowNodeTraces` 与 `runtimeTraceProjection`。Studio Debug、Supervisor Trace 和 RunOps 消费同一投影，不再各自推断节点状态和耗时。

Studio Debug SSE 以 `runtime.execution.v1` 事件名增量发送完整类型化事件，旧 `node.* / turn.* / session.*` 事件由事件桥继续保留。前端 Conversation 适配器把类型化事件收口为仅用于调试观测的内部事件，不重复追加公共消息或模型文本 delta，因此旧页面与新版 RunOps 可以并行升级。

## 5. WorkflowCheckpointV1

阻塞式 `INTERACTION` 暂停时，Runtime 写入以下 envelope：

| 字段 | 含义 |
| --- | --- |
| `checkpointSchemaVersion=1` | 断点格式版本 |
| `executionEngineVersion=RUNTIME_KERNEL_V2` | 恢复路由 |
| `graphDigest` | 暂停时 GraphSpec 快照原始 UTF-8 字节 SHA-256 |
| `suspendedNodeId` | 暂停节点 |
| `createdAtEpochMs` | 创建时间 |
| `state` | 恢复所需的内部执行状态 |

数据库同时保存 envelope SHA-256 和 UTF-8 字节数。默认大小上限为 256 KiB，可通过 `RUNTIME_WORKFLOW_CHECKPOINT_MAX_BYTES` 下调或上调，但不得超过不可配置的 1 MiB 硬上限。恢复前必须依次验证 schema、engine、配置大小上限、payload 摘要、GraphSpec 指纹和节点；任一不匹配均失败关闭并写 `CHECKPOINT_REJECTED`，不得猜测恢复。

`__workflowExecutionIdentity` 与 `__runtimeEvalExecutionContext` 在任何嵌套层级都不得进入断点。恢复身份由已验证的 `runtime_interaction_session` app/tenant/session/user 归属重新构造，客户端 context 不能提升信任。

历史行保持 `checkpoint_schema_version=0 / execution_engine_version=LEGACY`，由唯一的兼容路由读取；不会猜测性改写为 V1。未知版本不得由当前引擎执行。

等待会话行、V1 断点和 `CREATED / REQUESTED` 审计事件在同一事务提交后，调用方才可返回 `uiRequest`。连续两个交互之间，旧等待完成与新等待创建也在同一事务提交，避免出现“用户看到下一张表单但数据库没有可恢复断点”。

## 6. 数据库与部署顺序

新库使用 `sql/initV2.sql`。已有开发或测试库必须先执行：

```text
sql/upgrade-20260830-platform-consolidated.sql  # Runtime checkpoint V1: Section 09
```

然后部署 Runtime 代码。旧 schema 上直接启动新代码会因缺列失败，这是有意的同步切换门禁。

## 7. 验收门禁

至少验证：

1. Handler 注册集合与 Runtime 实际处理器一致；
2. Capability V1 成功、业务失败、拒绝、超时/连接失败和响应相关性；
3. 事件 sequence、敏感字段白名单和 Run 唯一终态；
4. Trace 对成功、失败、等待、取消、重试和循环的投影；
5. V1 round-trip、篡改、Graph 漂移、节点不匹配、超限和 Legacy 路由；
6. Agent/Embed 的 `WAITING_USER -> submit -> COMPLETED`，以及连续两个交互；
7. RunOps root/span 与 `runtime_interaction_session` 状态一致。
8. 以迁移前机械平移的直接 Engine 为基线，在包含代表性外部 I/O 的执行样本中，V2 门面、类型化事件与 Trace 投影的 P95 增量不得超过 5%。

结构门禁：

```powershell
node scripts/check-runtime-kernel-structure.test.mjs
node scripts/check-runtime-kernel-structure.mjs
```

门禁固定 15 个节点到上述处理器的直接注册，并限制 Engine 不得重新膨胀为节点实现容器。新增节点必须先进入明确处理器族；如果某一处理器族超过门禁规模，应继续按语义拆分，不能调大上限掩盖职责失控。

源码测试和构建通过只代表 CODE_READY；目标数据库升级、服务重启和真实 Agent/Embed 业务流通过后才能标记部署完成。
