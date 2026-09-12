# Agent AI Coding

## 定位

Agent AI Coding 是 ReachAI 面向 Codex、Cursor 等外部 AI 编程工具的项目级 Agent 编排协议。它使用项目 `aiCodingKey`，在不授予平台用户会话和控制台全局权限的前提下，开放以下能力：

- 列出、读取和新建当前项目 Agent；
- 修改 Agent 的安全身份字段；
- 修改并发布 AgentScope Supervisor 配置；
- 发现当前项目凭据可绑定的已发布 Skill 精确版本；
- 给 Agent 添加或移除 Skill 绑定；
- 复用既有 Workflow AI Coding 和 Workflow-as-Tool attach，把已发布 Workflow 加入 Agent 白名单。

这里的“添加 Skill”是把治理目录中已经发布的标准 Agent Skill 绑定到 Agent，不是让 AI 跳过治理直接上传、评审或发布 Skill 包。

## 服务与认证边界

所有接口由 `reachai-control-service` 暴露在 `/api/ai-coding/projects/{projectId}/**`，由 `ControlAiCodingAccessInterceptor` 校验请求头：

```http
X-ReachAI-AiCoding-Key: {project-ai-coding-key}
```

平台登录 Token、Embed Token、Task Token 和 AI Coding Key 不能互相替代。Key 不得出现在 query string、生成代码、日志、浏览器运行时代码或仓库文件中。

Control 先从 Capability owner 解析真实项目，再调用 Runtime-owned Agent API；Control 不直接读写 `runtime_agent`、`runtime_agent_config_version` 或绑定表。Agent 配置发布仍由 Runtime 完成版本切换和发布校验。

## API 一览

| 操作 | 方法与路径 | 核心约束 |
| --- | --- | --- |
| 列出 Agent | `GET /api/ai-coding/projects/{projectId}/agents` | 只返回 `projectId`、`projectCode` 同时匹配的 Agent |
| 新建 Agent | `POST /api/ai-coding/projects/{projectId}/agents` | 固定 `visibility=PROJECT`，同时创建 AgentScope 配置，默认发布 ACTIVE |
| 读取上下文 | `GET /api/ai-coding/projects/{projectId}/agents/{agentId}` | 返回 `configVersions` 与 `mutableBase` |
| 更新身份 | `PUT /api/ai-coding/projects/{projectId}/agents/{agentId}` | 只允许 `name`、`description`、`enabled`、`allowedRoles` |
| 保存配置草稿 | `PUT /api/ai-coding/projects/{projectId}/agents/{agentId}/config/draft` | 受 `baseConfigVersionId` 乐观并发保护 |
| 发布配置 | `POST /api/ai-coding/projects/{projectId}/agents/{agentId}/config/publish` | 只发布 DRAFT；发布前重新证明 Skill 绑定 |
| 可绑定 Skill | `GET /api/ai-coding/projects/{projectId}/agent-skills/bindable` | 只列出可见的 PUBLISHED 精确版本 |
| 添加 Skill | `POST /api/ai-coding/projects/{projectId}/agents/{agentId}/skills/attach` | 固定 `scriptPolicy=DENY`；默认保存并发布 |
| 移除 Skill | `POST /api/ai-coding/projects/{projectId}/agents/{agentId}/skills/detach` | 只移除绑定，不删除 Skill 包；默认保存并发布 |
| 添加 Workflow | `POST /api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach` | Workflow 必须已发布；添加默认是增量操作 |

机器可读入口为 `GET /api/ai-coding/projects/{projectId}/manifest`。`capabilities` 中包含 `AGENT_AI_CODING`，`endpoints` 和 onboarding manifest 的 `agentSupervisor.endpoints` 提供完整 URL。配套 Skill 包由 `GET /api/ai-assist/skills/agent-ai-coding/latest.zip` 提供。

## 新建 Agent

请求示例：

```json
{
  "keySlug": "orders-assistant",
  "name": "订单助手",
  "description": "处理订单查询与协作任务",
  "enabled": true,
  "allowedRoles": ["ORDER_OPERATOR"],
  "supervisorConfig": {
    "modelInstanceId": "active-llm-id",
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

`keySlug` 在当前项目内必须唯一。模型必须是 ACTIVE LLM；省略 `modelInstanceId` 时，Control 使用 provisioning 的既有选择策略。创建前先完成字段、Supervisor 配置和模型校验，减少创建出无可用配置 Agent 的概率。

创建接口只接受白名单字段。项目归属、可见性、内部 ID、ACTIVE 配置引用、Skill/Workflow/A2A 集合都不能由新建请求直接注入。

## 草稿与发布并发

读取 Agent 后，`mutableBase` 指向当前唯一可变基线：

- 没有 DRAFT 时，它是当前 ACTIVE 配置；
- 已有 DRAFT 时，它是该 DRAFT，并返回 `requiresExplicitBase=true`。

配置修改、Skill 添加和 Skill 移除都应发送最新 `baseConfigVersionId`。若已有未读 DRAFT 而请求省略基线，返回 `AGENT_DRAFT_CONFLICT`；基线已变化时返回 `AGENT_CONFIG_BASE_CONFLICT`。调用方必须重新读取上下文并重新判断差异，不能自动覆盖或盲目重试。

发布前，Control 会再次从 Skill Catalog 生成权威绑定快照，Runtime 再执行 Agent 配置发布校验。发布成功后旧 ACTIVE 归档，新版本成为 ACTIVE；Runtime 只解析 ACTIVE 配置。

## Skill 可见性与脚本策略

项目凭据没有平台用户 owner 身份，因此绑定规则比控制台更窄：

| Skill 可见性 | AI Coding 项目 Key |
| --- | --- |
| `PRIVATE` | 不可发现、不可绑定 |
| `PROJECT` | 仅 `skill.projectCode` 与当前项目一致时可绑定 |
| `SHARED` | 可绑定 PUBLISHED 精确版本 |
| `PUBLIC` | 可绑定 PUBLISHED 精确版本 |

调用方必须先从 `agent-skills/bindable` 读取 `skillId + skillVersionId`，不能按名称猜 ID。绑定快照中的发布者、名称、版本、摘要、风险报告和包 manifest 均由 Catalog 权威数据重建，浏览器或 AI 提交的同名字段不会被信任。

AI Coding 的 Skill 绑定一律为 `scriptPolicy=DENY`。即使 Skill 包包含脚本，项目 Key 也不能授予 `SANDBOX_REVIEWED`；脚本批准必须由拥有相应权限的人在控制台完成。

## 明确不开放的能力

- 导入、评审、批准、发布、撤回或删除 Skill 包；
- 读取或绑定任何人的 PRIVATE Skill；
- 修改 Agent 的项目归属、`keySlug`、可见性或内部 ID；
- 删除 Agent；需要停止入口时使用 `enabled=false`；
- 通过项目 Key 创建或批准远程 A2A Agent 信任绑定；
- 绕过 Workflow AI Coding 直接写 GraphSpec 表或绑定未发布 Workflow；
- 直接调用控制台 `/api/agents/**`、`/api/skills/**` 冒充平台用户。

这些限制不是缺失的 CRUD，而是项目凭据与平台人工治理之间的权限边界。后续若开放 Skill 包生成，也应先产出候选包并进入人工评审，不应复用 Agent 绑定权限直接发布。

## 错误与验收

Agent authoring 的领域错误采用 `agent-ai-coding-error.v1`：

```json
{
  "schema": "agent-ai-coding-error.v1",
  "code": "AGENT_CONFIG_BASE_CONFLICT",
  "message": "baseConfigVersionId does not match the current mutable config",
  "details": {},
  "requestId": "..."
}
```

验收至少包括：

1. 缺少/错误项目 Key 分别得到 401/403；
2. 跨项目 Agent 不可读取或修改；
3. PRIVATE 与其他项目的 PROJECT Skill 不出现在可绑定列表；
4. 添加 Skill 后回读 ACTIVE config，确认精确 `skillId`、`skillVersionId` 和 `scriptPolicy=DENY`；
5. 已存在 DRAFT 时，未携带正确基线的写入被拒绝；
6. 添加 Workflow 后回读 ACTIVE Workflow-as-Tool 白名单；
7. 如需声明业务闭环，再运行真实 Agent 场景并保留 trace/run 证据。

源码测试通过只能证明契约与安全分支，不等于数据库迁移、运行服务、真实 HTTP、浏览器或业务 E2E 已验收。
