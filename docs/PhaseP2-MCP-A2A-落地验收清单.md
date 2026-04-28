# Phase P2 对外开放（MCP + A2A）— 落地验收清单

> 与 `docs/AI中台生态扩展规划.md` 的 P2 阶段对齐。
> 目标：把已沉淀的 Tool / Skill / Agent 通过 MCP（外部 IDE 助手 / Claude Desktop / Cursor / OpenClaw）和 A2A（远程 Agent 节点）协议对外暴露，所有暴露/凭证/审计动作都在管理端可视化。

---

## 0. 目标回顾

- 一行配置接入：Cursor / Claude Desktop / Dify 通过 `mcp.json` 或 A2A AgentCard URL 即可发现并调用本仓能力。
- 双闸口治理：系统级 `mcp_visibility` + Client 自身 `tool_whitelist` 取交集，再叠加 `tool_acl` 的角色策略，三层防护。
- 全链路可观测：所有外部调用进 `mcp_call_log` / `a2a_call_log`，复用既有 `tool_call_log` traceId，可在管理端逐条查看请求/响应/耗时/错误。
- 不破坏既有路径：MCP / A2A 调用最终都跑 `AiToolAgentAdapter` / `AgentRouter.executeByDefinition`，沿用 sideEffect 闸口、ACL 决策、tool_call_log。

---

## 1. 数据层

- [x] `mcp_client`（`name / api_key_prefix / api_key_hash / roles_json / tool_whitelist_json / enabled / expires_at / last_used_at`）。
- [x] `mcp_call_log`（`client_id / method / tool_name / success / latency_ms / request_body / response_body / error_message / trace_id / remote_ip`）。
- [x] `mcp_visibility`（`target_kind ∈ {TOOL,SKILL} / target_name / exposed / note`）。
- [x] `a2a_endpoint`（`agent_id / agent_key / card_json / enabled`，`UNIQUE(agent_id)`）。
- [x] `a2a_call_log`（`endpoint_id / agent_key / task_id / method / success / latency_ms / request_body / response_body / error_message / trace_id / remote_ip`）。
- [x] `ai-agent-service/sql/mcp_phase_p2.sql` + `ai-agent-service/sql/a2a_phase_p2.sql` 幂等。
- [x] `sql/init.sql` 七.d / 七.e 节同步新增。

## 2. 后端 — MCP

- [x] `McpServerEndpoint` 暴露 `/mcp/jsonrpc`，实现 JSON-RPC 2.0：`initialize` / `tools/list` / `tools/call`。
- [x] `McpClientService.authenticate` 对 `Authorization: Bearer <prefix>.<plain>` 双段校验：先 `api_key_prefix` 索引定位 → BCrypt 比对 `api_key_hash`。
- [x] `tools/list` 三层过滤：`McpVisibilityService.isExposed` ∩ `tool_whitelist_json` ∩ `ToolAclService.decide(roles)`。
- [x] `tools/call` 复用 `AiToolAgentAdapter` 的执行链路（保持 sideEffect 闸口 + tool_call_log 一致性）。
- [x] `McpAdminController` 提供 Client CRUD（生成时只返回一次明文）、可见性维护、调用日志查询。

## 3. 后端 — A2A

- [x] `A2aServerEndpoint` 暴露：
  - `GET  /a2a/{agentKey}/.well-known/agent.json` —— A2A 0.2 规范的 AgentCard，自动补 `url` 字段。
  - `POST /a2a/{agentKey}/jsonrpc` —— `message/send` / `tasks/get` / `tasks/cancel`。
- [x] `message/send` 解析 `message.parts[*].text → userText` 后路由到 `AgentRouter.executeByDefinition(snapshot, contextId, userId, userText, null)`，与 `/api/v1/agents/{key}/chat` 共享 trace。
- [x] 工具调用 trace 以 `kind=data` 的 artifact 形态回写到 Task，便于 Dify / LangGraph 等编排器观测。
- [x] `A2aEndpointService.upsertForAgent`：从 `AgentDefinition` 元数据自动派生默认 AgentCard，运营可在前端 JSON 上覆盖个别字段。
- [x] `A2aAdminController` 提供 endpoint CRUD、enabled 切换、调用日志查询。

## 4. 前端可视化

### 4.1 MCP（4 页）
- [x] `views/mcp/McpVisibilityBoard.vue` —— 系统级勾选"哪些 Tool/Skill 可对外暴露"。
- [x] `views/mcp/McpClientList.vue` —— Client 凭证 CRUD（API Key 仅生成时显示一次 + 复制）。
- [x] `views/mcp/McpCallMonitor.vue` —— 调用流水 + 多维度过滤 + traceId 复制。
- [x] `views/mcp/McpOnboarding.vue` —— Cursor / Claude Desktop / OpenClaw / Dify 接入向导（一键复制 `mcp.json` 模板）。

### 4.2 A2A（2 页）
- [x] `views/a2a/A2aEndpointList.vue` —— 选 Agent → 编辑 AgentCard → 启用/禁用 → 删除。
- [x] `views/a2a/A2aSessionMonitor.vue` —— 会话流水 + 详情抽屉（请求/响应 JSON 美化）+ traceId 复制。

### 4.3 路由 & 菜单
- [x] `/mcp/visibility` `/mcp/clients` `/mcp/monitor` `/mcp/onboarding` 注册到 `views/layout/MainLayout.vue` "对外开放 / MCP" 子菜单。
- [x] `/a2a/endpoints` `/a2a/monitor` 注册到 "对外开放 / A2A" 子菜单。

## 5. 验收用例

1. **Cursor 一行配置接入**
   - 在 `~/.cursor/mcp.json` 写入：
     ```json
     {
       "mcpServers": {
         "enterprise-agent": {
           "url": "http://<host>:8080/mcp/jsonrpc",
           "headers": { "Authorization": "Bearer <prefix>.<plain>" }
         }
       }
     }
     ```
   - 在 Cursor 内 `tools/list` 应返回 `mcp_visibility.exposed=1` ∩ `tool_whitelist` ∩ `ToolAclService.decide(roles)=ALLOW` 的能力集合。
2. **可视化勾选立即生效**
   - 管理端 `McpVisibilityBoard` 取消某 IRREVERSIBLE Tool → Cursor `tools/list` 立即不可见（无应用级缓存延迟）。
3. **Dify 远程 Agent 接入**
   - 在 Dify 远程 Agent 配置：`Agent URL = http(s)://<host>:8080/a2a/<keySlug>/.well-known/agent.json`。
   - Dify 调用 `message/send` → 在管理端 `A2aSessionMonitor` 看到 OK 流水 + traceId；复制 traceId 到 Agent 调试页可看到完整 TraceTimeline。
4. **三层闸口**
   - 关闭 `mcp_visibility.exposed` → `tools/list` 不显示。
   - 该 Tool 不在 `mcp_client.tool_whitelist` 内 → 同样不可见。
   - `ToolAclService.decide(client.roles, toolName)` = DENY → `tools/call` 立即返回 `-32004` 错误并写入 `mcp_call_log.success=0`。

## 6. 风险与未尽事项

- 当前 A2A `tasks/get` 占位返回 `state=unknown`，本仓 ReAct 一次性执行模型不持久化中间 Task；如需 Dify 拉取历史可在下个 Phase 接入 Task Persistence。
- MCP 当前仅支持 HTTP+JSON-RPC，stdio 网关 `eaf` 二进制暂未提供，CI / 无 HTTP 出网场景留作下一阶段。
- API Key 启用后未与企业 SSO 打通，仍按 Phase 设计：API Key 即为身份。
