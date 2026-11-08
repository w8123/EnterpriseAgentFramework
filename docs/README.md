# ReachAI 操作手册

ReachAI 让员工用一句话操作企业里的业务系统：在业务系统右下角的助手里问，或者在自己的 Codex、Claude Code 里说。AI 以员工本人的身份办事，改数据要审批，每一步都有记录。

手册按角色组织，找到你的角色，从第一篇开始读。所有例子都用同一套开源 ERP（芋道 ERP），截图来自真实运行的系统。

## 第一次了解 ReachAI

- [ReachAI 是什么](start/what-is-reachai.md)：解决什么问题，适合谁。
- [一句话怎么变成一次 ERP 操作](start/how-it-works.md)：用一张图讲清楚原理。
- [在自己电脑上体验 ReachAI](start/try-in-10-minutes.md)：一条命令跑起 ReachAI 和芋道 ERP。
- [名词解释](start/glossary.md)：Agent、Workflow、MCP、Skill 都是什么。

## 我是业务人员

用 AI 查数据、办业务，不需要懂技术。

1. [在业务系统里用 AI 助手](business/use-assistant.md)
2. [在自己的 Codex / Claude Code 里办业务](business/own-ai-tool.md)
3. [让 AI 帮你改数据：为什么要审批](business/write-and-approval.md)
4. [我的个人页](business/my-portal.md)
5. [让 AI 记住你的习惯](business/memory.md)
6. [用同事沉淀的 Skill](business/skills.md)
7. [业务人员常见问题](business/faq.md)

## 我是管理人员

决定员工能让 AI 做什么，处理审批，看使用情况，沉淀好做法。

1. [管理人员的工作台](manager/workspace.md)
2. [开通员工使用](manager/open-to-employees.md)
3. [调用权限：谁能让 AI 做什么](manager/permissions.md)
4. [审批 AI 提交的操作](manager/approvals.md)
5. [看员工的 AI 使用情况](manager/usage.md)
6. [运行中心：查每一次运行](manager/run-center.md)
7. [把好的做法沉淀成 Skill](manager/skills.md)
8. [让 AI 按计划自动办事](manager/automations.md)

## 我是平台管理员

安装和维护 ReachAI，管理账号。

1. [安装 ReachAI](admin/install.md)
2. [第一次登录和「开始使用」](admin/first-login.md)
3. [账号和角色](admin/accounts-and-roles.md)
4. [日常运维：升级、备份、恢复、卸载](admin/operations.md)
5. [平台常见问题和排查](admin/troubleshooting.md)

## 我是开发者

把业务系统接入 ReachAI，开发助手和流程，开放给员工的 AI 工具。

1. [开发者从这里开始](developer/overview.md)
2. [接入第一个业务系统](developer/connect-project.md)
3. 声明业务方法：[Java / Spring Boot](developer/java-starter.md)、[C#、Python、Node.js、Go](developer/other-sdks.md)、[没有 SDK：导入 OpenAPI](developer/openapi-import.md)
4. [业务身份桥：让 AI 以员工本人身份办事](developer/business-identity.md)
5. [核对业务能力](developer/capabilities.md)
6. [接入模型](developer/model-setup.md)
7. [创建第一个 Workflow](developer/first-workflow.md)
8. [配置和调试 Agent](developer/agent.md)
9. [在业务页面嵌入助手](developer/embed-chat.md)
10. [发布 MCP 服务给员工](developer/publish-mcp.md)
11. [交给我的 AI：让 Codex、Claude Code 帮你干活](developer/ai-tool-handoff.md)
12. [用 AI 改造业务能力](developer/retrofit.md)
13. [创建知识库](developer/knowledge-base.md)
14. [芋道 ERP 接入实战](developer/yudao-example.md)
15. [开发者常见问题](developer/troubleshooting.md)

## 深入原理

- [一句话怎么变成一次 ERP 操作](start/how-it-works.md)
- [身份怎么一路传到业务系统](principles/identity.md)
- [AI 能做什么、不能做什么](principles/safety.md)
- [ReachAI 由哪些部分组成](principles/architecture.md)

## 参考资料

给开发 ReachAI 本身、或需要精确规则的读者。手册里的文章讲「怎么做」，这里讲「规则是什么」。

| 主题 | 文档 |
| --- | --- |
| 产品概念 | [产品](product.md) |
| 服务、数据归属和代码边界 | [架构](architecture.md) |
| 构建、开发、测试与排错 | [开发](development.md) |
| 业务能力与 Workflow | [业务能力与 Workflow](workflow.md) |
| Agent、记忆和标准 Skill | [Agent](agent.md) |
| 知识、业务索引和模型 | [数据与模型](data.md) |
| 登录、业务身份、权限和安全 | [身份与安全](security.md) |
| MCP、员工 AI 工具与 A2A | [开放协议](protocols.md) |
| 用外部编码工具改造系统 | [AI Coding](ai-coding.md) |
| 管理端页面与公共组件 | [前端设计](../ai-admin-front/DESIGN.md) |
| 部署、验收和恢复 | [运维](operations.md) |
| 支持范围和未完成事项 | [当前边界](status.md) |

精确清单（供开发核对与自动检查）：

- [表所有权](contracts/tables.md)：每张表的唯一 owning service。
- [内部 API](contracts/internal-apis.md)：服务间接口、owner 与消费者。
- [公共路由](contracts/public-routes.md)：浏览器和外部客户端的入口与凭证域。
- [SDK 接入协议](contracts/sdk-protocol.md)：跨语言注册、同步、调用与身份协议。

## 给 AI 阅读

官网文档中心用本目录的 Markdown 构建，网页、搜索和 AI 文本是同一份内容。阅读方法见 [AI 阅读入口](for-agents.md)。在仓库中工作的 AI 先读根目录 [AGENTS.md](../AGENTS.md)。

## 截图怎么来的

手册截图由 `scripts/capture-manual-screenshots.mjs` 从本机一键体验环境自动拍摄。界面改了以后重跑一次脚本，截图就会更新。
