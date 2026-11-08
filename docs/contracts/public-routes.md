# 公共路由

## 路由规则

Control 是浏览器、业务系统和开放协议的公共边界。浏览器不直连 Runtime / Capability，也不直接访问 `/internal/**`。受保护的 Knowledge / Model 管理请求先通过 Control，再进入对应 owner。

公共入口按凭证域分别认证，不能靠路径、请求 metadata 或转发字段冒充调用方。运行中的兼容入口由路由检查器约束；新消费者只使用当前规范路径，不增加另一套维护 API。

Model 服务除健康探针外均验证服务间 HMAC，管理入口仅接受 Control，执行入口接受 Control / Runtime / Knowledge 的对应请求；浏览器只能经 Control 的会话与权限检查进入。签名包含原始路径、查询参数和精确 body，nonce 在 Model 自有表中持久化；已有环境升级条件见 [SQL 清单](../../sql/README.md)。

| 业务入口 | 公共路径 | Owner | 凭证 |
| --- | --- | --- | --- |
| 平台登录与账号 | `/api/platform/**` | Control | 平台会话 / 管理权限 |
| 项目、SDK 与来源 | `/api/registry/**`、`/api/scan-projects/**`、`/api/capability-review/**` | Capability，Control 编排 | 项目签名或平台会话，按精确路由区分 |
| 业务方法与 API | `/api/business-methods/**`、`/api/apis/**`、`/api/business-capabilities/**` | Capability / Runtime | 平台权限、项目范围与调用 ACL |
| Agent 配置 | `/api/agents/**` | Runtime | 平台会话 / 项目授权 |
| Agent 执行 | `/api/runtime/agents/execute/**` | Runtime | 当前入口的可信执行身份 |
| Workflow 与 Studio | `/api/workflows/**` | Runtime | 平台会话；AI Coding 子资源使用项目 Key |
| 运行与交互 | `/api/runops/**`、`/api/traces/**`、`/api/runtime/interactions/**` | Runtime；对话由 Control 聚合 | 平台会话与资源授权 |
| 评测与自动化 | `/api/runtime/evals/**`、`/api/automations/**` | Runtime | 目标版本、平台权限与项目范围 |
| 知识与模型 | 受保护 `/ai/**`、`/model/**`，`/api/knowledge/**` | Knowledge / Model | Control 平台会话或受签名内部调用 |
| 业务索引写入 | `/api/knowledge-ingress/**` | Knowledge | 项目精确 body 签名 |
| 嵌入对话与页面 | `/api/embed/**`、`/embed/**` | Control，执行经 Runtime | 项目换令牌；业务接口使用 Embed 身份 |
| 员工个人页 | `/api/employee-portal/**` | Control | 由已验证业务身份换取的短期员工会话；共享记忆按当前有效关联校验 |
| 个人记忆与合规 | `/api/me/memories/**`、`/api/compliance/**`、`/api/runtime-user-mappings/**` | Control | 当前所有者或明确平台权限 |
| 平台用户 AI 连接 | `/api/me/ai-connections/**`、`/api/me/ai-approvals/**`、`/api/me/ai-access/**`、`/mcp/platform` | Control，资源查询经 owner 服务 | 管理端会话管理连接并审核本人请求；独立平台用户 AI 密钥调用 MCP |
| 项目 AI Coding | `/api/ai-coding/projects/**` | Control，委托 owning service | `X-ReachAI-AiCoding-Key` |
| 任务与交接 | `/api/ai-coding-console/**`、`/api/ai-coding/handoffs/**`、`/api/ai-coding/tasks/**` | Control | 平台会话、一次性交接或 Task Token |
| Skill 与市场 | `/api/skills/**`、`/api/ai-assist/skills/**`、`/api/api-market/**` | Control / Capability | 按目录、发布下载与项目操作区分 |
| SDK 制品 | `GET /api/ai-assist/artifacts/manifest`、`GET /api/ai-assist/artifacts/**` | Control | 公开分发元数据与制品，可在首次注册前读取，不含项目凭据 |
| MCP | `/api/mcp/**`、`/mcp/**` | Control，执行经 Runtime | 管理端会话、客户端凭证或员工连接 |
| A2A | `/api/a2a-hub/**`、`/.well-known/agent-card.json`、`/a2a/v1/**` | Control，执行经 Runtime | 平台权限或 A2A Principal |
| 内部接口 | `/internal/control/**`、`/internal/runtime/**`、`/internal/capability/**` | 各服务 | 服务间签名与可信执行信封，浏览器禁用 |

项目接入任务通过平台 MCP 绑定连接；控制台的任务交接包签发入口对 `PROJECT_ONBOARDING` 返回 `410 PROJECT_ONBOARDING_MCP_REQUIRED`。其他任务类型继续使用其现有入口，已签发的任务凭据按原有效期处理。

`POST /api/embed/chat/sessions/{sessionId}/context/clear` 使用当前 Embed Token 清除已验证会话的 Runtime 上下文；它保留 Embed 会话及审计记录，并透传 Runtime 的冲突等失败状态。

员工个人记忆关联由 `/api/employee-portal/memory-identities` 下的一次性码签发、确认及解除接口处理；确认需要两个独立的有效员工会话。管理员可通过 `/api/platform/personal-memory-identity-links` 代办关联或解除，须拥有全局业务用户管理权限并记录核验依据。关联只共享个人层记忆。

精确路径、方法、代理与授权由 `check-physical-service-route-contracts.mjs`、`check-frontend-public-api-routes.mjs`、`check-internal-api-contracts.mjs` 以及 Control 路由策略验证。详细服务间清单见[内部 API](internal-apis.md)，凭证语义见[安全](../security.md)。
