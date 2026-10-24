# 员工在自己的 AI 工具里办业务（路线第 1 步）

> 依据：[产品方向：企业系统的 AI 操作层](./产品方向-企业系统的AI操作层.md)。
> 2026-10-24 用户修正：不让用户理解 MCP，不让用户去业务系统点授权。企业内部系统以方便为主：开发者拿项目秘钥对接；用户身份由 ReachAI 管理和同步（没有就新建），业务系统身份代码由 starter 改造；用户最多在页面上勾选要接入的系统。
> 目标验收：员工在芋道的 AI 助手面板里勾选「芋道 ERP」，复制一行到自己的 Claude Code / Codex，然后查库存、新建客户；芋道记录的操作人是本人，调用权限按芋道角色生效，管理端能看到"谁、用什么工具、做了什么"。

## 1. 三件事分别怎么做

| 角色 | 要做的事 | 现状 |
| --- | --- | --- |
| 开发者 | 用 ReachAI 给的项目秘钥接入 starter，改造业务系统 | 已有（项目凭据、SDK 注册） |
| 用户身份 | ReachAI 统一管理员工账号：业务系统调用时自动同步，没有就新建；starter 把 ReachAI 带来的员工身份还原成业务系统登录人 | 已有基础：业务用户目录（`business_user`）、外部账号绑定（`external_user_binding`、角色绑定）、starter `ReachAiSecurityContextBridge` |
| 员工 | 在页面上勾选要接入的系统，复制一行（或一键）到自己的 AI 工具 | **新增** |

页面上不出现"MCP""授权""令牌"这些词，只说「在我的 AI 工具中使用」。MCP 只是背后的连接方式。

## 2. 员工的操作

1. 在芋道右下角的 AI 助手面板里点「在我的 AI 工具中使用」。员工已经登录芋道，身份由现有嵌入链路证明，不用再登录。
2. 勾选要接入的系统（默认勾上当前系统），选 AI 工具（Claude Code / Codex / 其他）。
3. 页面给出一行命令，例如 `claude mcp add --transport http 芋道ERP https://reachai.example.com/mcp/u/... --header "Authorization: Bearer ..."`，点复制，粘到终端回车即可。Codex 给对应的一行配置。
4. 面板里能看到「我的连接」，可随时断开。

后续（第二阶段）：做员工个人页，用同步过来的公司账号登录，一个人统一管理多个业务系统的连接。

## 3. 背后怎么实现

- **个人连接**：员工勾选后，ReachAI 生成一个个人连接密钥，只存哈希；绑定 ReachAI 员工（`business_user`）和勾选的系统。默认 90 天有效，员工和管理员都能断开；员工在业务系统里被停用时，连接一起失效。
- **员工身份**：调用时按连接找到员工，再找到他在目标系统里的外部账号绑定（externalUserId、角色）。Control → Runtime 新增签名身份来源 `MCP_EMPLOYEE`，Runtime 新增 `WorkflowExecutionIdentity.fromMcpEmployee(...)`（`userTrusted=true`），Capability claims 带员工的 externalUserId、userName、roles，芋道里还原成本人。
- **能用哪些能力**：每个系统由运营人员在 MCP Hub 选一个「员工可用」发布（例如「芋道 ERP 助手能力」）。员工能看到的工具 = 这个发布里的工具，再按员工业务角色走调用权限（和嵌入式对话同一套规则）。
- **记录**：调用日志和运行记录写入员工、AI 工具名称、连接 ID；运行中心入口类型显示 MCP。

## 4. 本步不做

- 写操作审批（第 2 步）。本步写操作按调用权限放行并完整审计；`IRREVERSIBLE` 能力对个人连接一律拒绝。
- 员工个人页、OAuth 自动登录、MCP 入向、A2A。
- 管理者使用报表（第 4 步），本步只在调用日志里能按员工筛选。

## 5. 批次

| 批次 | 内容 | 数据库 |
| --- | --- | --- |
| P1-1 | 个人连接：表与领域模型；`/api/embed/ai-tool-connections/**`（Embed Token 鉴权：可接入系统列表、创建、我的连接、断开）；员工首次出现时自动同步到业务用户目录；管理接口 `/api/mcp/publications/{id}/employee-access` 设置「员工可用」 | 新表 `control_mcp_employee_access`、`control_ai_tool_connection`、`control_ai_tool_connection_system`（`sql/upgrade-20261024-ai-tool-connections.sql`） |
| P1-2 | 员工身份执行：`/mcp/u/{connectionKey}` 协议入口复用现有 MCP 协议实现；按员工角色走调用权限；`MCP_EMPLOYEE` 签名身份、Runtime 身份工厂、Capability claims；日志与运行记录带员工和工具 | 调用日志加 `connection_id`、`external_user_id`、`tool_client_name` |
| P1-3 | 嵌入面板「在我的 AI 工具中使用」：勾选系统、选工具、复制命令、我的连接 | 无 |
| P1-4 | 管理端：MCP 发布里设置「员工可用」；「员工连接」列表（按员工、系统查看和断开）；文档「用 Claude Code 操作芋道」 | 无 |
| P1-6 | 员工个人页：用同步过来的公司账号登录，统一管理多个业务系统的连接（2026-10-24 用户选择两个入口都做） | 待定 |
| P1-5 | 部署与验收：备份后执行升级 SQL（需你在部署线程点确认），部署 Control / Runtime / 前端；在云端用 Claude Code 真实连上，查库存、建客户，核对芋道操作人、调用日志、运行中心 | 执行 P1-1、P1-2 的升级 SQL |

每批一个 PR，自测通过后合并到 dev；P1-5 之前不部署。
