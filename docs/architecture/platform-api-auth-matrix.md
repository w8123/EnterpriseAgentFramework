# Control API 认证边界矩阵

> 实施状态：管理端路径已由 `PlatformConsoleRoutePolicy` 收口；SDK Registry 兼容路径仍保留为后续显式多凭证适配任务。

`/api/**` 不是单一认证域。平台会话、SDK 项目签名、Embed Token、AI Coding 项目 Key、Task Token 和内部服务 HMAC 的主体不同；不得用“所有 401 跳登录”或一条全局 Bearer 拦截器混用。

## 已实施的 Control 路由策略

| 路径族 | 凭证域 | 执行点 | 失败语义 / 调用方 |
| --- | --- | --- | --- |
| `/api/platform/auth/login` | PUBLIC_LOGIN | `PlatformConsoleAuthWebConfig` 排除 | LOCAL 未配置返回 503；凭据错误返回 401。 |
| `/api/platform/**`（除 login） | PLATFORM_SESSION + RBAC | 控制台会话拦截器；`/api/platform/account-management/**` 的账号生命周期、自定义角色、权限目录和授权审计，以及认证源管理均追加全局 `platform:admin`；业务用户目录读取追加 `identity:business-user:read`，修改追加 `identity:business-user:manage` | 缺失、过期、撤销 Token 为带 `PLATFORM_SESSION_INVALID` 标记的 401；有效会话缺权限为 403，不触发退出登录。 |
| `/api/ai-coding-console/**`、`/api/ai-assist/projects/*/**`、`/api/registry/projects/*/page-workbench/**` | PLATFORM_SESSION | 控制台会话拦截器 | AI Coding 控制台、项目接入与页面工作台。 |
| `/api/context/**`、`/api/tool-acl/**`、`/api/a2a-hub/**`、`/api/mcp/**`、`/api/market/**` | PLATFORM_SESSION | 控制台会话拦截器 | 管理/治理操作；A2A Hub 再按发布、信任、凭据、任务操作和正文读取细分权限。 |
| `/api/workflows/**`（独立 AI Coding 子协议除外）、`/api/agents/**`、`/api/skills/**`、`/api/automations/**`、`/api/traces/**`、`/api/runops/**`、`/api/trace-center/**` | PLATFORM_SESSION + RESOURCE_RBAC | 控制台会话拦截器；Agent、Workflow、Automation、RunOps 分别追加领域权限与 PROJECT scope；Skill Controller 追加 `skill:*` 权限与 PRIVATE owner / PROJECT scope | 建模、版本、发布、自动化与运行治理。无项目过滤的跨项目查询要求 GLOBAL grant。 |
| `/api/runtime/evals/**`、`/api/runtime/agents/route-evaluation`、`/api/runtime/debug-sessions/**`、`/api/runtime/interactions/human-approvals` | PLATFORM_SESSION + RESOURCE_RBAC | EvalOps 使用 `agent:evaluate`，Debug 使用 `agent:debug` / `workflow:debug`；可解析 Agent、Workflow、Dataset、Experiment、Debug Session 时校验其 PROJECT scope | 旧 Eval opaque ID、全量评测和无法解析项目的兼容入口采用 GLOBAL-only fail-closed。 |
| `/api/runtime/agents/sessions/**`、`/api/runtime/tools/**`、`/api/runtime/compositions/**`、其余 `/api/runtime/interactions/**` | PLATFORM_SESSION / RUNTIME_IDENTITY | 控制台拦截器或 Controller 自身的 Runtime 用户身份约束 | 兼容执行辅助协议；不能据此推断拥有 Agent/Workflow 管理权限。 |
| `/api/capabilities/**`、`/api/api-market/**`、`/api/tools/**`、`/api/api-graph/**`、`/api/tool-retrieval/**`、`/api/scan-projects/**`、`/api/scan-modules/**`、`/api/semantic-docs/**`、`/api/domains/**` | PLATFORM_SESSION | `CapabilityCompatibilityProxyController` 已把 console mapping 与 registry mapping 分开；入口拦截后才代理 | Capability 目录、API 市场发现/项目接入、扫描和项目配置管理；API 市场不接收调用凭据。 |
| `/api/internal-services/health` | PLATFORM_SESSION | 控制台会话拦截器 | Dashboard 聚合健康接口；不是 `/internal/**`。 |
| `/api/knowledge/biz-index/**` | PLATFORM_SESSION + RBAC | 控制台会话拦截器；Controller 追加 `platform:read` / `platform:write`；随后 HMAC 到 Knowledge | 业务索引控制台。浏览器 Bearer 不再直达 Knowledge。 |
| `/api/knowledge/import-jobs/**`、`/api/knowledge/import-files/{fileId}/reparse` | PLATFORM_SESSION + RESOURCE_RBAC | 控制台会话拦截器；Controller 根据 Knowledge 返回的 workspace/project scope 校验 `platform:read` / `platform:write`；随后以精确请求体 HMAC 到 Knowledge | 文档导入、任务查询/操作和重新解析。任务还由 Knowledge 按 tenant/actor owner fence 隔离。 |
| `/api/knowledge-ingress/projects/{projectCode}/biz-index/**` | PROJECT_REQUEST_V1 | Control 校验精确 body 摘要；Capability 作为项目凭证 owner 验签并消费 nonce；Knowledge 校验项目归属 | 业务系统结构化同步；平台 session 不能替代，认证失败不触发浏览器登出。 |
| `/api/embed/**`、`/embed/**` | EMBED_TOKEN / PROJECT_HMAC | Embed controller 自身校验 | 业务终端用户协议；平台 Token 不能代替。 |
| `/api/ai-coding/projects/**` | AI_CODING_KEY | `ControlAiCodingAccessInterceptor` | `X-ReachAI-AiCoding-Key`；平台登录不能代替。 |
| `/api/ai-coding/tasks/**`、`/api/ai-coding/handoffs/**` 激活协议 | TASK_TOKEN / ONE_TIME_ACTIVATION | `AiCodingTaskTokenGuard` 等协议控制器 | 外部任务协作协议；前端不得当作平台会话失效。 |
| `/api/runtime/agents/execute`、`/detailed`、`/stream`、`/api/v1/agents/**`、`/gateway/**` | PUBLIC_RUNTIME_COMPAT / GATEWAY | Controller 会剥除客户端注入的信任字段；有真实平台 Bearer 时才附加服务端可信身份 | 不是控制台管理路由；其公开调用凭证/ACL 收口另由 Runtime/Embed/Gateway 契约负责。 |
| `/api/ai-assist/skills/**`、`/api/ai-assist/artifacts/**` | PUBLIC_ARTIFACT | 仅打包产物 | 不含项目密钥、Provider 秘钥或平台 Token。 |
| `/mcp/manifest`、`/mcp/jsonrpc` | MCP_CLIENT_KEY | MCP endpoint controller | `manifest` 可匿名发现；JSON-RPC 只接受已启用 MCP Client 的 Bearer API Key，不接受平台会话替代。 |
| `/api/a2a-hub/**` | PLATFORM_SESSION + RESOURCE_RBAC | A2A Hub management controllers | 管理查看、发布、信任、凭据、任务操作与正文读取使用独立权限；正文读取额外审计且响应 `no-store`。 |
| `/.well-known/agent-card.json` | PUBLIC_CARD_BY_HOST | A2A Agent Card controller | 只返回已发布不可变 Card 快照，不返回凭据或管理字段。 |
| `/a2a/v1/**` | A2A_PRINCIPAL | A2A protocol guard + authentication service | Host 解析 Publication；API Key/显式开发匿名策略形成稳定 Principal；每次请求重新执行 owner、tenant、scope、operation、大小和共享限流校验。平台会话不能替代协议身份。 |
| `/internal/runtime/managed-executions/**` | INTERNAL_SERVICE_AUTH | Control→Runtime 精确 body HMAC / caller allowlist / nonce / tenant-user identity | Control 创建、查询和取消 Managed Execution；Worker token 不能替代 Control HMAC。 |
| `/internal/runtime/managed-worker/**` | MANAGED_EXECUTION_TOKEN | Runtime 校验单 execution 高熵 Bearer digest、到期时间、Worker ID 和 lease owner | 仅供 disposable Worker 轮询调用；token 不能跨 execution 使用，也不能访问任何其他 `/internal/**`、数据库或共享服务 Secret。 |
| `/internal/control/managed-executions/events` | INTERNAL_SERVICE_AUTH | Runtime→Control 精确 body HMAC / nonce / metadata-only inbox digest | 只接受 Runtime 可靠投递；Worker token、平台会话和路径位置均不能替代 Runtime HMAC。 |
| `/internal/**`（除上列 Worker 协议） | INTERNAL_SERVICE_AUTH | 内部服务 HMAC / caller allowlist / nonce | 路径或网络位置不能视为已认证。 |

## 仍待收口：`/api/registry/**`

`/api/registry/**` 是 Starter SDK 与历史控制台兼容路径，不能纳入普通平台会话拦截器；本次已将其与受保护的 Capability console proxy 显式分开。除已独立保护的 page-workbench 路由外，Registry 的最终策略仍是：

1. 由 owning Capability service 验证 `projectCode + timestamp + nonce` 的项目签名并防重放。
2. Control 以显式适配器按确定顺序解析“平台 session 或已验证 SDK 签名”，产生带 `callerType`、`platformUserId` 或 `projectCode` 的受内部认证保护主体。
3. 管理端逐步迁出兼容 Registry 路由；新增 console API 不得继续写入该路径。
4. 为无凭据、过期签名、重放、有效 SDK 同步和有效控制台操作分别增加集成测试后，才可将此路径标记为完全收口。

平台角色、scope 与建设/运营治理工作区的关系见 [平台授权与工作区底座](./platform-authorization-foundation.md)。菜单和路由只投影服务端权限，不能替代本矩阵中的 API 授权执行点。

## 变更检查清单

- 新增 Control API 必须先在本矩阵选择唯一凭证域，并同步更新 `PlatformConsoleRoutePolicy`（若为控制台路径）。
- 只有显式 `X-ReachAI-Auth-Failure: PLATFORM_SESSION_INVALID` 的平台会话 401 才能触发浏览器登出。
- 平台 Token、Embed Token、Task Token、AI Coding Key、SDK 签名和内部 HMAC 不可跨域替用。
- `PlatformConsoleRoutePolicyTest` 会反射扫描编译后的全部 Control Controller 映射；任一路径未命中 `PLATFORM_SESSION`、`PUBLIC_LOGIN`、`INDEPENDENT_PROTOCOL` 或显式 `COMPATIBILITY_PENDING` 凭证域时构建失败。`/api/registry/**` 的多凭证适配尚不能宣称已完成。
