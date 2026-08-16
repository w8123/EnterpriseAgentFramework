# Control API 认证边界矩阵

> 实施状态：管理端路径已由 `PlatformConsoleRoutePolicy` 收口；SDK Registry 兼容路径仍保留为后续显式多凭证适配任务。

`/api/**` 不是单一认证域。平台会话、SDK 项目签名、Embed Token、AI Coding 项目 Key、Task Token 和内部服务 HMAC 的主体不同；不得用“所有 401 跳登录”或一条全局 Bearer 拦截器混用。

## 已实施的 Control 路由策略

| 路径族 | 凭证域 | 执行点 | 失败语义 / 调用方 |
| --- | --- | --- | --- |
| `/api/platform/auth/login` | PUBLIC_LOGIN | `PlatformConsoleAuthWebConfig` 排除 | LOCAL 未配置返回 503；凭据错误返回 401。 |
| `/api/platform/**`（除 login） | PLATFORM_SESSION | 控制台会话拦截器；高危身份管理追加 `platform:admin` | 缺失、过期、撤销 Token 为带 `PLATFORM_SESSION_INVALID` 标记的 401。 |
| `/api/ai-coding-console/**`、`/api/ai-assist/projects/*/**`、`/api/registry/projects/*/page-workbench/**` | PLATFORM_SESSION | 控制台会话拦截器 | AI Coding 控制台、项目接入与页面工作台。 |
| `/api/context/**`、`/api/slot-*`、`/api/tool-acl/**`、`/api/admin/a2a/**`、`/api/mcp/**`、`/api/market/**` | PLATFORM_SESSION | 控制台会话拦截器 | 管理/治理操作。 |
| `/api/workflows/**`、`/api/agents/**`、`/api/traces/**`、`/api/runops/**`、`/api/trace-center/**` | PLATFORM_SESSION | 控制台会话拦截器 | Workflow Studio、Agent 管理、Trace 与 RunOps。 |
| `/api/runtime/evals/**`、`/api/runtime/agents/sessions/**`、`/api/runtime/agents/route-evaluation`、`/api/runtime/tools/**`、`/api/runtime/compositions/**`、`/api/runtime/interactions/**`、`/api/runtime/debug-sessions/**` | PLATFORM_SESSION | 控制台会话拦截器 | 管理端调试、评测、人审和执行辅助操作。 |
| `/api/capabilities/**`、`/api/tools/**`、`/api/compositions/**`、`/api/api-graph/**`、`/api/tool-retrieval/**`、`/api/skill-mining/**`、`/api/capability-mining/**`、`/api/scan-projects/**`、`/api/scan-modules/**`、`/api/semantic-docs/**`、`/api/domains/**` | PLATFORM_SESSION | `CapabilityCompatibilityProxyController` 已把 console mapping 与 registry mapping 分开；入口拦截后才代理 | Capability 目录、扫描和项目配置管理。 |
| `/api/internal-services/health` | PLATFORM_SESSION | 控制台会话拦截器 | Dashboard 聚合健康接口；不是 `/internal/**`。 |
| `/api/knowledge/biz-index/**` | PLATFORM_SESSION + RBAC | 控制台会话拦截器；Controller 追加 `platform:read` / `platform:write`；随后 HMAC 到 Knowledge | 业务索引控制台。浏览器 Bearer 不再直达 Knowledge。 |
| `/api/knowledge-ingress/projects/{projectCode}/biz-index/**` | PROJECT_REQUEST_V1 | Control 校验精确 body 摘要；Capability 作为项目凭证 owner 验签并消费 nonce；Knowledge 校验项目归属 | 业务系统结构化同步；平台 session 不能替代，认证失败不触发浏览器登出。 |
| `/api/embed/**`、`/embed/**` | EMBED_TOKEN / PROJECT_HMAC | Embed controller 自身校验 | 业务终端用户协议；平台 Token 不能代替。 |
| `/api/ai-coding/projects/**` | AI_CODING_KEY | `ControlAiCodingAccessInterceptor` | `X-ReachAI-AiCoding-Key`；平台登录不能代替。 |
| `/api/ai-coding/tasks/**`、`/api/ai-coding/handoffs/**` 激活协议 | TASK_TOKEN / ONE_TIME_ACTIVATION | `AiCodingTaskTokenGuard` 等协议控制器 | 外部任务协作协议；前端不得当作平台会话失效。 |
| `/api/runtime/agents/execute`、`/detailed`、`/stream`、`/api/v1/agents/**`、`/gateway/**` | PUBLIC_RUNTIME_COMPAT / GATEWAY | Controller 会剥除客户端注入的信任字段；有真实平台 Bearer 时才附加服务端可信身份 | 不是控制台管理路由；其公开调用凭证/ACL 收口另由 Runtime/Embed/Gateway 契约负责。 |
| `/api/ai-assist/skills/**`、`/api/ai-assist/artifacts/**` | PUBLIC_ARTIFACT | 仅打包产物 | 不含项目密钥、Provider 秘钥或平台 Token。 |
| `/mcp/manifest`、`/mcp/jsonrpc` | MCP_CLIENT_KEY | MCP endpoint controller | `manifest` 可匿名发现；JSON-RPC 只接受已启用 MCP Client 的 Bearer API Key，不接受平台会话替代。 |
| `/a2a/{agentKey}/**` | A2A_ENDPOINT_PROTOCOL | A2A endpoint controller | 当前只按已启用 endpoint 路由，不属于管理端会话。调用方认证/签名尚未实现，必须作为对外暴露前的独立安全收口项，不能误标为已认证。 |
| `/internal/**` | INTERNAL_SERVICE_AUTH | 内部服务 HMAC / caller allowlist / nonce | 路径或网络位置不能视为已认证。 |

## 仍待收口：`/api/registry/**`

`/api/registry/**` 是 Starter SDK 与历史控制台兼容路径，不能纳入普通平台会话拦截器；本次已将其与受保护的 Capability console proxy 显式分开。除已独立保护的 page-workbench 路由外，Registry 的最终策略仍是：

1. 由 owning Capability service 验证 `projectCode + timestamp + nonce` 的项目签名并防重放。
2. Control 以显式适配器按确定顺序解析“平台 session 或已验证 SDK 签名”，产生带 `callerType`、`platformUserId` 或 `projectCode` 的受内部认证保护主体。
3. 管理端逐步迁出兼容 Registry 路由；新增 console API 不得继续写入该路径。
4. 为无凭据、过期签名、重放、有效 SDK 同步和有效控制台操作分别增加集成测试后，才可将此路径标记为完全收口。

## 变更检查清单

- 新增 Control API 必须先在本矩阵选择唯一凭证域，并同步更新 `PlatformConsoleRoutePolicy`（若为控制台路径）。
- 只有显式 `X-ReachAI-Auth-Failure: PLATFORM_SESSION_INVALID` 的平台会话 401 才能触发浏览器登出。
- 平台 Token、Embed Token、Task Token、AI Coding Key、SDK 签名和内部 HMAC 不可跨域替用。
- `PlatformConsoleRoutePolicyTest` 会反射扫描编译后的全部 Control Controller 映射；任一路径未命中 `PLATFORM_SESSION`、`PUBLIC_LOGIN`、`INDEPENDENT_PROTOCOL` 或显式 `COMPATIBILITY_PENDING` 凭证域时构建失败。`/api/registry/**` 的多凭证适配尚不能宣称已完成。
