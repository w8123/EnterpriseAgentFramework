# ReachAI 文档

从接入第一个业务方法，到发布可治理的 AI 助手。这里提供产品说明、操作指南、系统接入、部署运维与开发参考，供使用者和 AI 智能体共同阅读。

## 从你的任务开始

| 我想做什么 | 从这里开始 | 完成后继续 |
| --- | --- | --- |
| 第一次了解和使用 ReachAI | [快速入门](quickstart.md) | [接入第一个模型](guides/model-setup.md) |
| 把现有企业系统接入 AI | [系统接入](integration.md) | [在业务页面嵌入对话](guides/embed-chat.md) |
| 编排并发布业务助手 | [创建第一个 Workflow](guides/first-workflow.md) | [Agent 与标准 Skill](agent.md) |
| 用自己的 AI 工具办理业务 | [员工 AI 工具接入](guides/employee-ai-tools.md) | [身份与安全](security.md) |
| 安装并维护一套服务 | [部署与运维手册](部署与运维手册.md) | [运维与验收](operations.md) |
| 让 AI 工具理解或开发本项目 | [AI 阅读入口](for-agents.md) | [架构](architecture.md)与[开发](development.md) |

## 学习路径

- **Java 开发者**：[产品概念](product.md) → [系统接入](integration.md) → [页面对话](guides/embed-chat.md) → [业务能力与 Workflow](workflow.md)。
- **Agent / Workflow 实施人员**：[接入模型](guides/model-setup.md) → [第一个 Workflow](guides/first-workflow.md) → [发布 Agent](agent.md) → [知识问答](guides/knowledge-qa.md)。
- **管理员与运维人员**：[安装](部署与运维手册.md) → [身份与安全](security.md) → [员工 AI 工具](guides/employee-ai-tools.md) → [运维与验收](operations.md)。

## 常用指南

- [常见问题与排查](faq.md)：模型、来源同步、调用、审批和检索问题。
- [芋道 ERP 接入实战](guides/芋道ERP接入ReachAI实施步骤.md)：后端身份桥、页面补丁与验收。
- [用 Claude Code 操作芋道](guides/用Claude%20Code操作芋道.md)：员工连接与审批实例。
- [Docker 体验](../deploy/quickstart/README.md)：在本机体验已接入的业务系统。

## 主题文档

| 任务 | 文档 |
| --- | --- |
| 了解 ReachAI、用户和核心对象 | [产品](product.md) |
| 理解服务、数据归属和代码边界 | [架构](architecture.md) |
| 构建、开发、测试与排错 | [开发](development.md) |
| 接入 Java 系统和业务页面 | [系统接入](integration.md) |
| 使用业务方法/API、设计和调试流程 | [业务能力与 Workflow](workflow.md) |
| 配置 Agent、记忆和标准 Skill | [Agent](agent.md) |
| 使用知识、业务索引和模型 | [数据与模型](data.md) |
| 理解登录、业务身份、权限和安全 | [身份与安全](security.md) |
| 使用 MCP、员工 AI 工具与 A2A | [开放协议](protocols.md) |
| 用外部编码工具改造系统或编排流程 | [AI Coding](ai-coding.md) |
| 修改管理端页面与公共组件 | [前端设计](../ai-admin-front/DESIGN.md) |
| 安装、升级、备份（一条命令） | [部署与运维手册](部署与运维手册.md) |
| 部署、验收和恢复 | [运维](operations.md) |
| 确认支持范围和未完成事项 | [当前边界](status.md) |

## 精确清单

以下清单供开发核对与自动检查；业务说明不再重复完整表和接口列表。

- [表所有权](contracts/tables.md)：每张表的唯一 owning service。
- [内部 API](contracts/internal-apis.md)：服务间接口、owner 与消费者。
- [公共路由](contracts/public-routes.md)：浏览器和外部客户端的入口与凭证域。

## 文档与 AI 阅读

官网文档中心使用本目录及相关模块的 Markdown 构建，网页、搜索和 AI 文本使用同一份内容；官网源码仅在开发仓库维护，开源发布按[同步规则](development.md#开源同步)排除官网目录。阅读方法见 [AI 阅读入口](for-agents.md)。在仓库中工作的 AI 先读根目录 [AGENTS.md](../AGENTS.md)，再读相关主题及源码。模块 README 只解释模块本身，源码和配置决定当前实现。

源码存在、测试通过、运行环境可用和真实业务验收分别判断。文档不保存执行流水、旧决策、历史授权或归档副本；需要的待验条件集中在[当前边界](status.md)。
