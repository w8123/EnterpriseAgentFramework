# 服务内模块边界

五个物理服务保持稳定部署边界；架构优化优先在服务内部建立单向模块依赖，而不是继续拆服务或拆库。当前第一批治理聚焦体量和依赖最复杂的 `reachai-control-service` 与 `reachai-runtime-service`。

## 规则

- 顶层 Java package 是服务内模块声明。新增顶层 package 必须先登记到 `scripts/internal-module-boundaries.json`，说明其职责和依赖方向。
- 模块可以依赖另一个模块公开的 application port、只读 contract 或 client facade，不得直接引用另一模块的 `persistence` package、Mapper 或 Entity。
- 新的双向 package 依赖一律失败。当前已有环只作为显式、可递减的债务基线；移除后检查器会要求同步删除 allowance，禁止它再次出现。
- 产品模块需要接收基础设施事件时，由基础设施 owning module 定义 port 和不可变事件 contract，产品模块实现该 port；基础设施模块不得反向导入产品 application service。
- 服务间仍遵守 [Internal API Contracts](./internal-api-contracts.md) 与 [Service Table Ownership](./service-table-ownership.md)，服务内模块化不改变表 owning service 和公共路由。

## 当前债务基线

`scripts/internal-module-boundaries.json` 是唯一机读基线。第一批已移除 Control 的 `managed -> aicoding` 反向依赖：Managed Execution Inbox 只负责可靠接收、幂等、租约和重试，通过 `ControlManagedExecutionInboxProjector` 交给 AI Coding 投影；AI Coding 继续单向依赖 Managed Execution contract。

Control 的 Page Workbench 通过自有 `PageWorkbenchObservationPort` 读取 Platform 会话、页面操作、真实对话和 Trace 的不可变观测快照；Platform 负责 Mapper/Entity 查询和适配。Project、Model、Runtime 的远程访问同样经 Page Workbench 自有 port 进入，由 `client` 侧统一适配 Feign client。Page Workbench 不再穿透 Platform 持久化实现，也不再直接持有 client，`pageworkbench -> platform/client` 已列入禁止回流边。

AI Assist 的 AI Coding access 请求契约已归属 Capability client，不再由 client 反向引用 Controller DTO。`RuntimeTrustedAgentExecutionGateway` 则从 `client` 迁回 `runtime`：它负责可信信封、流式转发和个人记忆观测编排，底层内部 HTTP client 仍留在 `client`。因此 `client -> aiassist/context/runtime` 均已禁止回流。

Runtime 已将 GraphSpec 执行、会话清理、业务记忆 hydration、RunOps 生命周期、Supervisor 调用、审批恢复和交互过期 Trace 收口为 execution-owned port。`SupervisorRuntimeAdapter` 现位于 `execution`，Supervisor 只实现端口；A2A 持久化 binding 在进入 Supervisor 前转换为不可变快照。共享 `USER_INPUT` 语义位于 `graph`，Workflow 发布校验和执行处理器不再互相引用。AgentScope 模型桥接、流式诊断和 answer phase 归属独立的 `agentscope` 基础模块，Workflow Authoring 不再反向依赖 Supervisor。以下已删除的反向边均写入 `forbiddenEdges`，不能回流：`execution -> memory/runops/supervisor/workflow`、`a2a -> supervisor`、`workflow -> supervisor`。

前端 Workflow Studio 保留画布编排 shell，评测、源码编辑、项目 API 查询模板、发布门禁和会话式调试台归属 `views/workflow/studio-overlays/`。状态与业务调用继续由既有 composable 管理，覆盖层只接收显式状态并上抛业务事件；调试台仍保留会话恢复、人工交互、节点轨迹、Trace 回放和 ACTIVE 发布版本对照。`scripts/check-workflow-studio-structure.mjs` 限制 shell 体量并禁止这些实现重新内联回 `WorkflowStudio.vue`。

本轮同时退役了三类已确认无调用价值的实现：RunOps 详情页中被常量永久隐藏的旧诊断视图、可信 Agent 流式网关中无调用方的签名辅助方法，以及 Control/Runtime 中只用于把旧英文 Page Copilot 默认值改写为中文的历史数据迁移分支。当前创建、编辑、发布、Workflow-as-Tool 附加、RunOps 调查工作台和公开 API 路由均保留；公共入口中的 Compatibility Controller 是五服务拆分后的正式 Control facade，不属于可删除旧路由。

剩余基线：

- Control：0 组双向顶层 package 依赖，4 个 `runops -> aicoding.persistence` 临时例外；
- Runtime：9 组双向顶层 package 依赖（本轮从 15 组降至 9 组）；
- 这些数字只能下降，不能以替换环或新增例外的方式保持总数不变。

## 验证

```powershell
node scripts/check-internal-module-boundaries.test.mjs
node scripts/check-internal-module-boundaries.mjs
node scripts/check-runtime-kernel-structure.test.mjs
node scripts/check-runtime-kernel-structure.mjs
node scripts/check-workflow-studio-structure.mjs
node scripts/verify-architecture.mjs
```

完整 Maven、前端、SQL、运行服务和浏览器验证仍按对应变更范围分别执行；架构脚本通过不能替代业务验收。
