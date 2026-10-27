# 文档

只维护当前产品、实现契约和必要操作。同一规则只在所属主题维护，其他文档通过链接引用。

## 阅读入口

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
| 部署、验收和恢复 | [运维](operations.md) |
| 确认支持范围和未完成事项 | [当前边界](status.md) |

## 精确清单

以下清单供开发核对与自动检查；业务说明不再重复完整表和接口列表。

- [表所有权](contracts/tables.md)：每张表的唯一 owning service。
- [内部 API](contracts/internal-apis.md)：服务间接口、owner 与消费者。
- [公共路由](contracts/public-routes.md)：浏览器和外部客户端的入口与凭证域。

## 使用方式

人从任务表进入；AI 先读根目录 [AGENTS.md](../AGENTS.md)，再读相关主题及源码。模块 README 只解释模块本身，源码和配置决定当前实现。

源码存在、测试通过、运行环境可用和真实业务验收分别判断。文档不保存执行流水、旧决策、历史授权或归档副本；需要的待验条件集中在[当前边界](status.md)。
