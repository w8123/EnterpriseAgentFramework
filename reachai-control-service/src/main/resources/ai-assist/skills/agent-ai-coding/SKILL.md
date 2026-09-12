---
name: agent-ai-coding
description: Create, inspect, and safely update project-scoped ReachAI Agents; edit and publish Supervisor config drafts; discover published bindable Skills; and attach or detach exact Skill versions through the Agent AI Coding REST API. Use when an AI coding tool is asked to add an Agent, configure its model or prompt, bind a Skill, remove a Skill binding, or attach a published Workflow as a Tool.
---

# Agent AI Coding

## 适用范围

通过项目级 AI Coding 凭据完成受约束的 Agent 编排，不直接调用控制台 `/api/agents/**` 或 `/api/skills/**`，不直接写 `runtime_agent`、`runtime_agent_config_version` 或 Agent Skill 绑定表。

认证方式：每次请求都发送 `X-ReachAI-AiCoding-Key`。该 Key 只放在请求头，不得写入 URL、生成代码、日志、浏览器运行时代码或仓库文件。API Base URL 和 `projectId` 必须来自当前项目 handoff 或 `GET /api/ai-coding/projects/{projectId}/manifest`，不要猜测。

凡是 Agent 名称、描述、System Prompt、审计说明和返回给业务用户的内容，默认使用清晰的简体中文；`keySlug`、ID、枚举、路径和协议字段保持原样。

## 强制安全边界

- 只能读取或修改当前 `projectId` 与 `projectCode` 同时匹配的 Agent。
- 创建的 Agent 固定为 `visibility=PROJECT`；不能通过 AI Coding 改写项目归属、`keySlug`、可见性或内部 ID。
- 只能绑定精确的 `PUBLISHED` Skill 版本。可绑定范围为 `PUBLIC`、`SHARED`，或当前同项目的 `PROJECT` Skill；`PRIVATE` Skill 永远不可发现、不可绑定。
- AI Coding 绑定的 Skill 始终使用 `scriptPolicy=DENY`。不能申请或伪造 `SANDBOX_REVIEWED`，也不能批准脚本执行。
- AI Coding 不提供 Skill 包导入、评审、发布、撤回或删除能力。这些治理动作必须由控制台中的人完成。
- AI Coding 不发布包含远程 A2A Agent 绑定的配置；出现 `AGENT_A2A_REQUIRES_CONSOLE` 时转交控制台人工处理。
- 不提供 Agent 删除接口。停用 Agent 只能通过受约束的 identity update 设置 `enabled=false`。
- Workflow 必须先通过 Workflow AI Coding 校验并发布，再通过项目级 Workflow-as-Tool attach 接口添加。

## 标准执行循环

1. 下载本 Skill：`GET /api/ai-assist/skills/agent-ai-coding/latest.zip`。
2. 读取项目 manifest：`GET /api/ai-coding/projects/{projectId}/manifest`。
3. 列出 Agent：`GET /api/ai-coding/projects/{projectId}/agents`。
4. 新建 Agent，或读取目标 Agent 的完整上下文。
5. 修改前读取 `mutableBase.configVersionId`。若已有 DRAFT，后续写请求必须显式携带该 DRAFT id；不要覆盖未读草稿。
6. 修改 Supervisor 字段、绑定 Skill、解绑 Skill或附加 Workflow。
7. 默认让 Skill attach/detach 直接发布；若 `publish=false`，完成全部修改后再显式发布 DRAFT。
8. 重新读取 Agent，确认 ACTIVE 配置、Skill 精确版本和 Workflow Tool 白名单。

## API

### 列出与读取 Agent

```http
GET /api/ai-coding/projects/{projectId}/agents
GET /api/ai-coding/projects/{projectId}/agents/{agentId}
```

第二个接口返回 `agent`、`configVersions` 和 `mutableBase`。必须用真实返回的内部 `agent.id` 调用后续 Agent authoring 接口。

### 创建 Agent

```http
POST /api/ai-coding/projects/{projectId}/agents
Content-Type: application/json
```

```json
{
  "keySlug": "orders-assistant",
  "name": "订单助手",
  "description": "处理订单查询与协作任务",
  "enabled": true,
  "allowedRoles": ["ORDER_OPERATOR"],
  "supervisorConfig": {
    "modelInstanceId": "从平台返回值中选择的 ACTIVE LLM id",
    "systemPrompt": "你是订单助手，只使用已允许的 Skill 和 Workflow。",
    "maxPlanSteps": 6,
    "maxWorkflowCalls": 4,
    "maxReplans": 2,
    "policyProfile": "DEV_ALLOW_ALL"
  },
  "activate": true,
  "requestedBy": "Codex"
}
```

`modelInstanceId` 必须是 ACTIVE LLM；省略时平台选择第一个可用 ACTIVE LLM。默认 `activate=true`，创建后立即得到 ACTIVE AgentScope Supervisor 配置。不要为占位而创建空 Workflow。

### 更新 Agent 身份字段

```http
PUT /api/ai-coding/projects/{projectId}/agents/{agentId}
```

只允许 `name`、`description`、`enabled`、`allowedRoles`。不允许发送 `projectId`、`projectCode`、`keySlug`、`visibility`、`activeConfigVersionId` 或其他受保护字段。

### 保存 Supervisor 配置草稿

```http
PUT /api/ai-coding/projects/{projectId}/agents/{agentId}/config/draft
```

```json
{
  "baseConfigVersionId": 101,
  "systemPrompt": "更新后的系统提示词",
  "modelInstanceId": "active-llm-id",
  "maxPlanSteps": 8,
  "maxWorkflowCalls": 5,
  "maxReplans": 2,
  "totalTimeoutMs": 120000,
  "workflowTimeoutMs": 60000,
  "pageBridgeTimeoutMs": 30000,
  "parallelReadOnly": true,
  "policyProfile": "DEV_ALLOW_ALL",
  "config": {"purpose": "orders"}
}
```

只发送需要修改的字段。`baseConfigVersionId` 来自最新 Agent context；当 mutable base 是未发布 DRAFT 时必须提供。`AGENT_DRAFT_CONFLICT` 或 `AGENT_CONFIG_BASE_CONFLICT` 表示先重新读取上下文，不能盲目重试。

### 发布 Supervisor 草稿

```http
POST /api/ai-coding/projects/{projectId}/agents/{agentId}/config/publish
```

```json
{
  "configVersionId": 102,
  "requestedBy": "Codex"
}
```

只允许发布当前 Agent 的 DRAFT。发布前平台会重新证明所有 Skill 仍为可绑定的 PUBLISHED 精确版本，并再次拒绝脚本批准和 A2A 远程绑定。

### 发现可绑定 Skill

```http
GET /api/ai-coding/projects/{projectId}/agent-skills/bindable?search={optional}
```

从 `bindings[].skill.id` 和 `bindings[].version.id` 选择精确组合。不要从名称推断 ID，不要绑定未出现在该响应中的版本。响应 `scriptPolicy` 固定为 `DENY`。

### 添加 Skill

```http
POST /api/ai-coding/projects/{projectId}/agents/{agentId}/skills/attach
```

```json
{
  "skillId": 11,
  "skillVersionId": 21,
  "baseConfigVersionId": 101,
  "activationMode": "MODEL_SELECTED",
  "required": false,
  "enabled": true,
  "publish": true,
  "requestedBy": "Codex"
}
```

默认 `activationMode=MODEL_SELECTED`、`required=false`、`enabled=true`、`publish=true`。同一 `skillId` 已存在时会替换成请求的精确版本；完全相同的 ACTIVE 绑定会幂等复用。请求中不要发送 `scriptPolicy`，平台固定使用 `DENY`。

### 移除 Skill 绑定

```http
POST /api/ai-coding/projects/{projectId}/agents/{agentId}/skills/detach
```

```json
{
  "skillId": 11,
  "baseConfigVersionId": 102,
  "publish": true,
  "requestedBy": "Codex"
}
```

该操作只移除当前 Agent 配置中的绑定，不删除 Skill 包。目标不存在时幂等返回 `removed=false`。

### 添加已发布 Workflow

```http
POST /api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach
```

发送已发布 `workflowId`，可选使用目标 Agent 的 `agentKeySlug` 或 `agentId`。添加默认是增量操作；只有明确替换一个已附加的前序 Workflow 时才发送精确 `replaceWorkflowId`。Workflow GraphSpec 的创建、修改、验证和发布遵循 `workflow-ai-coding` Skill。

## 错误处理与完成证据

Agent authoring 错误使用 `agent-ai-coding-error.v1`，至少包含 `code`、`message`、`requestId` 和可选 `details`。`401` 表示缺少 Key；`403` 表示 Key 无效、已停用或越过项目/Skill 可见性边界；`409` 通常表示草稿基线冲突、状态冲突或必须转人工控制台。

完成前至少回读一次 Agent context，并报告：Agent id/keySlug、ACTIVE configVersionId、绑定的 Skill id/version、Workflow Tool 列表、是否发布，以及未执行的运行时或业务验收。只有 HTTP 写入成功但未回读时，不要宣称闭环完成。
