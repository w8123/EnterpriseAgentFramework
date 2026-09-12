# 架构契约

本目录只保存会影响当前产品边界、代码归属、协议、安全或运行语义的长期契约。实施步骤和未完成验收位于 [plans/](../plans/)；目标环境操作位于 [operations/](../operations/)。

## 服务与接口边界

| 文档 | 用途 |
| --- | --- |
| [service-boundaries.md](./service-boundaries.md) | 五服务职责、调用方向、公共入口、本地启动和验证分层 |
| [public-route-contracts.md](./public-route-contracts.md) | 外部/前端路由的 canonical、compatibility 和 retired 生命周期 |
| [physical-split-route-ownership.md](./physical-split-route-ownership.md) | 公共路由逐项 owner、委托方式和实现状态 |
| [internal-api-contracts.md](./internal-api-contracts.md) | 服务间 internal API 的 owner、consumer、用途和前端禁用边界 |
| [service-table-ownership.md](./service-table-ownership.md) | 同库阶段每张表的唯一 owning service |
| [internal-module-boundaries.md](./internal-module-boundaries.md) | Control/Runtime 服务内模块、依赖方向、循环债务基线和可执行守卫 |
| [platform-api-auth-matrix.md](./platform-api-auth-matrix.md) | Control API 的凭证域、会话与 RBAC 边界 |
| [platform-authorization-foundation.md](./platform-authorization-foundation.md) | 平台身份域、角色模板、权限作用域与建设/运营治理工作区边界 |
| [platform-console-auth-state-machine.md](./platform-console-auth-state-machine.md) | 管理端登录、会话恢复、不可用和退出状态机 |

## Agent、Workflow 与 Runtime

| 文档 | 用途 |
| --- | --- |
| [agent-supervisor-runtime.md](./agent-supervisor-runtime.md) | AgentScope Supervisor、配置版本、Workflow-as-Tool 与 Page Bridge |
| [workflow-semantic-contract.md](./workflow-semantic-contract.md) | GraphSpec、Canvas、节点、生命周期和发布语义 |
| [runtime-execution-kernel-v2.md](./runtime-execution-kernel-v2.md) | GraphSpec 编译、Handler/Port、Capability V1、类型化事件、Trace Projector 与 WorkflowCheckpointV1 |
| [runtime-automation.md](./runtime-automation.md) | Automation 边界、持久集群时钟、版本钉住、租约执行、RBAC 与 RunOps 契约 |
| [workflow-authoring-kernel.md](./workflow-authoring-kernel.md) | Studio AI 与外部 AI Coding 共用的 mutation/validation 边界 |
| [workflow-interaction-runtime.md](./workflow-interaction-runtime.md) | GraphSpec 原生暂停、恢复、交互和运行状态 |
| [runtime-context-engineering.md](./runtime-context-engineering.md) | 会话压缩、Tool 结果卸载、超限恢复和灰度边界 |
| [runops-trace-workflow-candidate.md](./runops-trace-workflow-candidate.md) | 从成功 Trace 生成受约束 Workflow 候选的闭环 |
| [runtime-audit-attribution.md](./runtime-audit-attribution.md) | 根运行与 Trace 的项目、租户、用户归属及恢复和完成规则 |
| [agent-skill-market.md](./agent-skill-market.md) | 外部 Agent Skill 发现、GitHub 不可变引入、来源信任与供应链边界 |
| [agent-skill-center.md](./agent-skill-center.md) | 标准 Agent Skill 包、评审、精确绑定、Runtime 载入和 Host 边界 |
| [managed-executor.md](./managed-executor.md) | 服务端 Codex harness、Runtime 控制面、AgentScope 异步委托、沙箱与证据边界 |
| [managed-executor-threat-model.md](./managed-executor-threat-model.md) | 不可信 Workspace/Codex 的跨租户、凭据、网络、逃逸、资源与审批威胁门禁 |

## 项目接入与平台能力

| 文档 | 用途 |
| --- | --- |
| [ai-coding-task-protocol-v1.md](./ai-coding-task-protocol-v1.md) | 外部 AI Coding 任务交接、状态、凭证、Artifact 和验收协议 |
| [business-page-workbench.md](./business-page-workbench.md) | 页面地图、改造分析、任务交付、资源绑定和发布查询 |
| [capability-change-governance.md](./capability-change-governance.md) | 能力来源观察、自动接纳、契约漂移保护与调用投影归属 |
| [api-market.md](./api-market.md) | 外部 API 目录、版本、Operation、项目接入和来源治理 |
| [model-center-v2.md](./model-center-v2.md) | 模型模板/实例、稳定 ID、测试、归档和凭据边界 |
| [docling-document-ingestion.md](./docling-document-ingestion.md) | 文档解析路由、导入任务、原件工件和部署安全 |
| [knowledge-base-lifecycle.md](./knowledge-base-lifecycle.md) | 知识库创建、删除、目录编辑和配置更新的职责与并发边界 |
| [personal-agent-memory.md](./personal-agent-memory.md) | 会话、个人、业务记忆及检索投影、遗忘与生产治理 |

## 开放协议

| 文档 | 用途 |
| --- | --- |
| [a2a-hub-product-design.md](./a2a-hub-product-design.md) | A2A Hub 产品边界、角色、旅程、状态和企业验收门禁 |
| [a2a-hub-architecture.md](./a2a-hub-architecture.md) | A2A 1.0、身份/信任、Task/Artifact、出站委派和数据边界 |
| [mcp-hub-product-design.md](./mcp-hub-product-design.md) | MCP Hub 发布修订、Client、协议调用、权限和审计边界 |
