# AI 中台生态扩展规划

本文承接当前生产闭环收口后的下一层外延：AI Gateway、MCP / A2A / CLI、Agent / Skill 市场。目标不是替代现有 `ai-agent-service`，而是在其稳定后提供统一入口、协议适配和跨项目复用能力。

## 一、AI Gateway

定位：面向业务系统的统一 AI 能力入口，和传统业务网关解耦。

最小职责：

1. 统一鉴权：把业务身份转换为 `userId`、`roles`、租户信息。
2. 统一限流：在网关层做租户 / 应用级粗粒度限流，服务内继续保留 Tool 级限流。
3. 统一熔断：按 Agent keySlug、Tool project、下游系统域名做熔断。
4. Trace 透传：生成或接收 `traceId`，透传到 `/api/v1/agents/{key}/chat`。
5. 能力目录：聚合 Agent / Skill / `/api/ai/*` 能力元数据，供业务系统发现。

建议接口：

```text
POST /gateway/agents/{key}/chat
POST /gateway/ai/extract
POST /gateway/ai/summarize
POST /gateway/ai/retrieve
GET  /gateway/catalog
```

## 二、MCP / A2A / CLI

### MCP Server

把 `ToolRegistry` 与 `tool_definition` 中可见的 Tool / Skill 暴露为 MCP tools：

1. `tools/list`：按 roles、project、kind 过滤。
2. `tools/call`：复用现有 `AiToolAgentAdapter` 执行路径，继续写 `tool_call_log`。
3. schema：优先使用 `LlmJsonSchemaProvider`，否则回退 `ToolParameter`。
4. 审计：MCP client id 写入 `userId` 或 `extra`，保持 trace 可查。

### A2A 适配

把已发布 Agent 暴露为远程 Agent：

1. Agent Card 来自 `AgentDefinition` 与 `AgentVersion`。
2. Task / Run 映射到 `/api/v1/agents/{key}/chat`。
3. Stream 后续接入现有 SSE 能力。
4. 结果携带 `traceId`，可跳转 Trace 时间线。

### CLI

命令名建议 `eaf`：

```text
eaf agent chat <key>
eaf tool call <toolName> --json args.json
eaf skill test <skillName>
eaf scan trigger <projectId>
eaf trace show <traceId>
```

CLI 只调用 REST，不绕过网关和 ACL。

## 三、Agent / Skill 市场

市场适合在多项目复用需求明确后启动，避免过早产品化。

最小模型：

1. 可见性：PRIVATE / PROJECT / PUBLIC。
2. 导入导出：AgentDefinition、AgentVersion snapshot、Skill spec、依赖 Tool 清单。
3. 依赖检查：导入前验证 Tool / Skill 是否存在，缺失时给出扫描或映射建议。
4. 评分指标：复用 `skill_eval_snapshot`、`tool_call_log` 成功率、延迟和 token 成本。
5. 安全策略：导入后默认禁用，需要 Tool ACL 和 sideEffect 检查通过后才能发布。

## 四、推荐顺序

1. 先做 CLI，只封装现有 REST，成本最低，能服务 CI 和运维。
2. 再做 MCP Server，对外部智能体平台最有价值。
3. AI Gateway 在试点业务接入超过 2 个后启动，避免过早引入独立工程。
4. Agent / Skill 市场最后做，等复用和治理需求真实出现。
