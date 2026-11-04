# 内部 API

浏览器只调用 Control 公开入口，不直接访问下表中的服务地址。每个 owner 负责身份、项目、资源和当前修订校验；内部调用绑定精确请求体、签名、时间窗和 nonce。HTTP 路由本身不授予信任。

本清单保存接口、owner 与 consumer，具体执行语义见对应主题和实现。新增端点同步此表与内部接口检查器；已实现的兼容 transport 仍按服务间凭证保护。

| Contract | Owner service | Consumers | 浏览器直连 |
| --- | --- | --- | --- |
| `POST /internal/runtime/workflow-releases/context`, `POST /internal/runtime/workflow-releases/publish` | `reachai-runtime-service` | `reachai-control-service` | No；签名的平台身份、匹配项目及完整发布快照摘要；Control 负责个人审批的一次消费 |
| `POST /internal/runtime/workflows/studio/read-only-trials` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/agent-config-drafts/context`, `POST /internal/runtime/agent-config-drafts/versions`, `POST /internal/runtime/agent-config-drafts/save`, `POST /internal/runtime/agent-config-drafts/copy`, `POST /internal/runtime/agent-config-drafts/publish`, `POST /internal/runtime/agent-config-drafts/publication-context`, `POST /internal/runtime/agent-config-drafts/publish-approved` | `reachai-runtime-service` | `reachai-control-service` | No；要求签名的平台会话身份及匹配项目；审核发布固定 Workflow 版本 |
| `POST /internal/runtime/project-agent-config-drafts/context`, `POST /internal/runtime/project-agent-config-drafts/versions`, `POST /internal/runtime/project-agent-config-drafts/save`, `POST /internal/runtime/project-agent-config-drafts/publish` | `reachai-runtime-service` | `reachai-control-service` | No；仅已验证 AI Coding 项目 Key 派生的签名 `PROJECT_CREDENTIAL`，项目归属与修订原子校验；与平台会话入口隔离 |
| Runtime compatibility paths: `POST /api/runtime/debug-sessions`, `/stream`, `GET /{sessionId}`, `POST /{sessionId}/submit`, `/submit/stream`, `/cancel` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/control/page-actions` | `reachai-control-service` | `reachai-runtime-service` | No |
| `GET /internal/control/page-actions/lookup` | `reachai-control-service` | `reachai-runtime-service` | No |
| `GET /internal/control/page-actions/{projectCode}/{pageKey}/{actionKey}` | `reachai-control-service` | `reachai-runtime-service` | No |
| `POST /internal/control/page-bridge/execute` | `reachai-control-service` | `reachai-runtime-service` | No |
| `POST /internal/control/page-bridge/resolve-context` | `reachai-control-service` | `reachai-runtime-service` | No |
| `POST /internal/runtime/workflows/ai-coding/page-assistant/smoke-test` | `reachai-runtime-service` | `reachai-control-service` | No；Control 验证页面令牌及 Workflow 项目权限后签名用户与请求体；Runtime 复核项目和会话，真实页面动作逐项确认 |
| `POST /internal/control/agent-skills/resolve-execution` | `reachai-control-service` | `reachai-runtime-service` | No |
| `GET /internal/control/agent-skills/{skillId}/versions/{versionId}` | `reachai-control-service` | `reachai-runtime-service` | No |
| `GET /internal/control/agent-skills/{skillId}/versions/{versionId}/package` | `reachai-control-service` | `reachai-runtime-service` | No |
| `POST /internal/control/managed-executions/events` | `reachai-control-service` | `reachai-runtime-service` | No |
| `GET /internal/runtime/page-workbench/workflow-node-types` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/page-workbench/projects/{projectCode}/workflow-drafts` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/page-workbench/projects/{projectCode}/workflows/{workflowId}/deliver` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/page-workbench/projects/{projectCode}/published` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/page-workbench/projects/{projectCode}/release-readiness` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/page-workbench/projects/{projectCode}/execution-readiness` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/runops/workflow-candidates/drafts` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/runops/traces/{traceId}/replay` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/automations/readiness` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/automations` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/automations` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/automations/{automationKey}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `PUT /internal/runtime/automations/{automationKey}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `DELETE /internal/runtime/automations/{automationKey}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/automations/{automationKey}:pause` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/automations/{automationKey}:resume` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/automations/{automationKey}:run` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/automations/{automationKey}/occurrences` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/automations/{automationKey}/occurrences/{occurrenceId}:retry` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/automations/{automationKey}/occurrences/{occurrenceId}:cancel` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/health` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/capability-references` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/console-capability-invocations`, `GET /internal/runtime/console-capability-invocations/{invocationId}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/http-api-connections`, `PUT /internal/runtime/http-api-connections`, `PUT /internal/runtime/http-api-connections/project-default`, `POST /internal/runtime/http-api-connections/override-removals` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/capability/api-market/entries`, `/entries/{entryKey}`, `/stats`, `/categories`, `/sources`, `/integrations`, `/integrations/{id}` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/capability/api-market/entries/{entryKey}/integrations`, `PUT /internal/capability/api-market/integrations/{id}/status` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/runtime/http-api-invocations`, `GET /internal/runtime/http-api-invocations/{invocationId}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/agent-tool-references` | `reachai-runtime-service` | `reachai-capability-service` | No |
| `GET /internal/capability/health` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/capability/http-apis/{apiId}/execution-context` | `reachai-capability-service` | `reachai-runtime-service` | No |
| `POST /internal/capability/business-methods/{qualifiedName}/execution-context` | `reachai-capability-service` | `reachai-runtime-service` | No |
| `GET /internal/capability/business-methods`, `GET /internal/capability/business-methods/{name}` | `reachai-capability-service` | `reachai-control-service` | No |
| `GET /internal/capability/business-methods/summary` | `reachai-capability-service` | `reachai-control-service` | No |
| `GET /internal/capability/tools/{qualifiedName}` | `reachai-capability-service` | `reachai-control-service`, `reachai-runtime-service` | No |
| `POST /internal/capability/invocations` | `reachai-capability-service` | `reachai-runtime-service` | No |
| `POST /internal/capability/tools/{qualifiedName}/execute` | `reachai-capability-service` | `reachai-runtime-service` | No |
| `GET /internal/capability/projects/{projectCode}` | `reachai-capability-service` | `reachai-runtime-service` | No |
| `GET /internal/capability/projects/by-id/{projectId}` | `reachai-capability-service` | `reachai-runtime-service`, `reachai-control-service` | No |
| `GET /internal/capability/projects/by-id/{projectId}/onboarding` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/capability/projects/by-id/{projectId}/sdk-sync` | `reachai-capability-service` | `reachai-control-service` | No |
| `PUT /internal/capability/projects/by-id/{projectId}/ai-coding-access` | `reachai-capability-service` | `reachai-control-service` | No |
| `GET /internal/capability/embed/credentials` | `reachai-capability-service` | `reachai-control-service` | No |
| `PUT /internal/capability/embed/credentials/{id}/policy` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/capability/embed/token/exchange/verify` | `reachai-capability-service` | `reachai-control-service` | No |
| `GET /internal/capability/projects/by-id/{projectId}/tools` | `reachai-capability-service` | `reachai-runtime-service`, `reachai-control-service` | No |
| `GET /internal/capability/projects/by-id/{projectId}/readiness-facts` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/runtime/managed-executions` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/managed-executions/{executionId}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/managed-executions/{executionId}:cancel` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/managed-executions/{executionId}/approvals/{interactionId}:resolve` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/managed-worker/executions/{executionId}:claim` | `reachai-runtime-service` | `managed-executor-worker` | No |
| `POST /internal/runtime/managed-worker/executions/{executionId}:heartbeat` | `reachai-runtime-service` | `managed-executor-worker` | No |
| `POST /internal/runtime/managed-worker/executions/{executionId}/events:batch` | `reachai-runtime-service` | `managed-executor-worker` | No |
| `GET /internal/runtime/managed-worker/executions/{executionId}/commands` | `reachai-runtime-service` | `managed-executor-worker` | No |
| `PUT /internal/runtime/managed-worker/executions/{executionId}/artifacts/{artifactType}` | `reachai-runtime-service` | `managed-executor-worker` | No |
| `POST /internal/runtime/managed-worker/executions/{executionId}:complete` | `reachai-runtime-service` | `managed-executor-worker` | No |
| `POST /internal/runtime/projects/{projectId}/agent-supervisor/workflow-tools/attach` | `reachai-runtime-service` | `reachai-control-service` | No；精确 body HMAC 与项目 Key 派生身份，路径项目和签名租户参与校验；发布审计身份由 Runtime 派生 |
| `POST /internal/runtime/workflows/{id}/page-assistant/context`, `POST /internal/runtime/workflows/{id}/page-assistant/attach` | `reachai-runtime-service` | `reachai-control-service` | No；精确 body HMAC 与签名平台会话身份，签名租户须等于 Control 从 Workflow 推导的项目；同步须携带页面副驾驶修订或 `createTarget`（仅目标不存在时），发布者由签名用户派生 |
| `POST /internal/runtime/mcp/tool-executions` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/agents/execute` | `reachai-runtime-service` | `reachai-control-service` | No；嵌入身份的签名 body 必须携带 Control 从可信业务会话派生的 `identity.projectCode`，缺失或与目标 Agent 项目不一致时拒绝 |
| `POST /internal/runtime/agents/execute/stream` | `reachai-runtime-service` | `reachai-control-service` | No；嵌入身份的签名 body 必须携带 Control 从可信业务会话派生的 `identity.projectCode`，缺失或与目标 Agent 项目不一致时拒绝 |
| `DELETE /internal/runtime/agents/sessions/{sessionId}` | `reachai-runtime-service` | `reachai-control-service` | No；嵌入会话必须携带含 `projectCode` 的签名 JSON body，Runtime 按业务系统、用户和会话校验；平台身份仍使用空 body |
| `GET /internal/runtime/session-retention/tenants/{tenantId}/policy` | `reachai-runtime-service` | `reachai-control-service` | No |
| `PUT /internal/runtime/session-retention/tenants/{tenantId}/policy` | `reachai-runtime-service` | `reachai-control-service` | No |
| `DELETE /internal/runtime/session-retention/tenants/{tenantId}/policy` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/runtime/session-retention/tenants/{tenantId}/sessions/{sessionId}` | `reachai-runtime-service` | `reachai-control-service` | No |
| `PUT /internal/runtime/session-retention/tenants/{tenantId}/sessions/{sessionId}/legal-hold` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/session-retention/tenants/{tenantId}/sessions/{sessionId}/erase` | `reachai-runtime-service` | `reachai-control-service` | No |
| `POST /internal/runtime/session-retention/tenants/{tenantId}/owner-erasure` | `reachai-runtime-service` | `reachai-control-service` | No |
| `GET /internal/model/instances`, `GET /internal/model/instances/{id}` | `reachai-model-service` | `reachai-runtime-service`, `reachai-control-service` | No；Model 验证精确 body、原始路径和查询参数的服务 HMAC，持久化 nonce 防重放；Runtime 仅读列表，管理限 Control |
| `POST /internal/knowledge/retrieval/query` | `reachai-knowledge-service` | `reachai-runtime-service` | No |
| `POST /internal/knowledge/personal-memories/events` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `POST /internal/knowledge/personal-memories/query` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `POST /internal/knowledge/personal-memories/owner-status` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `GET/POST/PUT/DELETE /internal/knowledge/console/biz-index/**` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `GET/POST /internal/knowledge/console/document-import/**` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `POST /internal/knowledge/console/catalog/read` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `POST /internal/knowledge/console/catalog/settings/**` | `reachai-knowledge-service` | `reachai-control-service` | No |
| `POST /internal/capability/registry/enrollments` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/capability/registry/project-requests/verify` | `reachai-capability-service` | `reachai-control-service` | No |
| `POST /internal/knowledge/project-ingress/projects/{projectCode}/biz-index/{indexCode}/{upsert,batch,delete}` | `reachai-knowledge-service` | `reachai-control-service` | No |
