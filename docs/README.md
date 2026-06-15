# 睿池 ReachAI 文档入口

本目录只保留当前系统的权威知识库。旧的升级计划、阶段验收清单和临时讨论稿已经被合并到下面几份主文档中；阅读时优先看当前能力、代码位置和未完成边界，不再按历史阶段编号追溯。

根目录 `README.md` 已按当前定位重写为对外入口；本目录继续作为更细的系统知识库和实现索引。

## 当前定位

睿池 ReachAI 是面向 Java 企业系统的 Agent 开发与治理中台。它把业务系统中的接口、领域能力、知识、模型、流程、权限和运行审计，沉淀成 Agent 可以理解、编排、调用、治理和开放的资产。

当前系统不是单纯的“扫描历史项目生成 Tool”，而是由以下主线组成：

- 项目与能力注册中心：业务系统可通过 `reachai-spring-boot2-starter` 与 `reachai-capability-sdk` 主动注册项目、实例、能力和 SDK 图。
- 能力资产治理：`tool_definition` 统一承载 Tool 与粗粒度 Capability，扫描接入和 SDK 注册都进入同一资产目录。
- Agent 入口与 Workflow Studio：Agent（`ai_agent`）负责身份/策略/绑定，Workflow（`ai_workflow`）负责 GraphSpec 与画布；Workflow Studio 是唯一画布编辑器，Runtime 通过 AgentEntry + binding 执行已发布 Workflow。
- 运行治理与开放协议：RunOps、Trace、Tool ACL、Guard 决策、MCP、A2A 和 Gateway 共同承担生产运行边界。
- 企业资产底座：模型实例、知识库、业务索引、领域归属和市场资产为 Agent 提供可复用上下文。

## 阅读顺序

| 文档 | 适合回答的问题 |
| --- | --- |
| [01-平台定位与架构总览.md](./01-平台定位与架构总览.md) | 系统现在是什么、有哪些服务、管理端有哪些页面、统一 SQL 基线覆盖什么 |
| [02-项目注册与能力资产.md](./02-项目注册与能力资产.md) | 业务系统如何接入，扫描、SDK 注册、能力同步、Tool/Capability 资产如何落库 |
| [03-Agent-Studio与Runtime.md](./03-Agent-Studio与Runtime.md) | Agent/Workflow 解耦、`/api/agents` 与 `/api/workflows`、Workflow Studio、GraphSpec、binding 解析、调试、发布与 Runtime 如何工作 |
| [04-运行治理与开放协议.md](./04-运行治理与开放协议.md) | Trace、RunOps、ACL、Guard、MCP、A2A、Gateway 如何保护和开放能力 |
| [05-知识模型与企业资产.md](./05-知识模型与企业资产.md) | 模型实例、知识库、业务索引、领域、市场资产如何被 Agent 使用 |
| [09-AI长任务代码审计提示词.md](./09-AI长任务代码审计提示词.md) | 交给长上下文 AI 执行只读全仓代码审计、死代码过滤、文档/SQL/前后端漂移检查的完整提示词 |
| [11-ReachAI-JDK8接入SDK与JDK17-Runtime分层方案.md](./11-ReachAI-JDK8接入SDK与JDK17-Runtime分层方案.md) | JDK8 业务接入 SDK 与 JDK17 中台 Runtime 分层的当前落地模块、协议边界和验证方式 |
| [14-班组系统接入ReachAI SDK实施步骤.md](./14-班组系统接入ReachAI%20SDK实施步骤.md) | 班组微服务接入 ReachAI SDK、网关 token broker、前端嵌入对话框和 Page Action 的标准步骤 |
| [15-创建页面助手轻闭环.md](./15-创建页面助手轻闭环.md) | 项目详情页创建页面助手向导、手工声明页面动作、生成 Agent Studio 草稿的 v1 边界 |
| [ai-memory/README.md](./ai-memory/README.md) | 给 Cursor、Codex 等 AI 编程工具看的项目规则、记忆和历史坑位 |

## 当前已落地

- `docs` 根目录已经收敛为 6 份主文档，覆盖平台总览、注册与能力、Studio 与 Runtime、运行治理、企业资产。
- 旧的里程碑交付、临时规划说明和试点说明内容已经被吸收到主文档。
- `docs/系统截图/` 保持原路径，避免影响暂未重写的根目录 `README.md`。
- `docs/superpowers/plans/` 保持为执行计划产物，不纳入主知识库。
- `docs/ai-memory/` 是给 AI 编程工具看的项目记忆，不替代主知识库和对外 README。

## 仍待补齐

- 根目录 `README.md` 已完成对外定位重写，后续应随这套知识结构持续同步。
- 主文档目前是架构和产品级知识，不替代逐页操作手册。
- 如果代码继续演进，应优先更新这 6 份主文档，而不是新增阶段型文档。

## 文档维护规则

- 新文档按产品能力组织，不再新增阶段型验收清单。
- 默认使用 `Capability / 能力`。当文档必须解释旧代码、表名或枚举时，才使用 `Skill`，并明确它是 legacy naming。
- 所有实现说明应指向真实代码模块、前端页面、接口路径或 SQL 表。
- 每份主文档都必须区分“当前已落地”和“仍待补齐”，避免规划与实现混在一起。
- `docs/superpowers/plans/` 是执行计划产物，不属于主知识库。
- `docs/ai-memory/` 只记录会影响未来 AI 编程判断的规则、决策和坑位；如果与当前代码冲突，以当前代码为准并同步更新记忆。
