# 架构契约

这里放会影响代码边界、路由归属、服务间调用和部署方式的事实源。根目录文档只讲产品主线；具体约束以本目录为准。

| 文档 | 用途 |
| --- | --- |
| [public-route-contracts.md](./public-route-contracts.md) | 前端和外部调用应使用的公共路由、冻结兼容 alias 和 retired route |
| [physical-split-route-ownership.md](./physical-split-route-ownership.md) | public route owning service 归属和迁移状态 |
| [internal-api-contracts.md](./internal-api-contracts.md) | 服务间 internal API 契约、owner/consumer 和前端禁用边界 |
| [service-table-ownership.md](./service-table-ownership.md) | 同库阶段的服务表所有权 |
| [model-center-v2.md](./model-center-v2.md) | 模型中心 V2：template / instance 职责、稳定 modelInstanceId、归档与测试语义 |
| [agent-supervisor-runtime.md](./agent-supervisor-runtime.md) | AgentScope Supervisor、Agent 配置版本、Workflow-as-Tool 与跨路由 Page Bridge 主路径 |
| [workflow-authoring-kernel.md](./workflow-authoring-kernel.md) | Workflow Studio AI 与外部 AI Coding / CLI 共用的 GraphSpec 修改、校验、修复和保存边界 |
| [ai-coding-task-protocol-v1.md](./ai-coding-task-protocol-v1.md) | Codex / Cursor / Trae / Claude Code 统一任务交接、DPAPI 会话恢复、UTF-8 回传、任务级 Token、状态和 Provider 边界 |
| [workflow-semantic-contract.md](./workflow-semantic-contract.md) | Workflow 分类、执行引擎、GraphSpec/Canvas、节点调用和生命周期的规范语义及迁移规则 |
| [business-page-workbench.md](./business-page-workbench.md) | 业务页面工作台的页面地图、分析结果、AI Coding 任务、资源绑定、发布查询与服务边界 |
| [unified-conversation-surfaces-implementation-plan.md](./unified-conversation-surfaces-implementation-plan.md) | Agent 调试台、Workflow Studio 调试对话框和业务 Embed Chat 的共享对话内核、组件、事件流与实施计划 |
| [backend-boundaries-and-naming.md](./backend-boundaries-and-naming.md) | 五服务边界、同库策略、命名规则和公共入口 |
| [physical-services-and-startup.md](./physical-services-and-startup.md) | 五服务启动、IDEA 配置、环境变量和验证入口 |
| [legacy-retirement.md](./legacy-retirement.md) | 旧 agent 主入口退场、兼容面生命周期和启动清单 |
