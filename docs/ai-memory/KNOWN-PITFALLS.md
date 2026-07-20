# ReachAI Known Pitfalls

## SQL Drift

症状：后端报 `Unknown column`、`Table doesn't exist` 或 MyBatis SQLSyntaxErrorException。

处理顺序：

1. 找到报错接口和 Mapper。
2. 找实体字段和表名。
3. 查 `sql/initV2.sql` 是否有表、列、索引。
4. 查是否需要新增 `sql/upgrade-*.sql`。
5. 不要只改 Java 或前端响应处理。

历史例子：

- `agent_workflow_credential` 缺表会影响 Workflow Studio 工作流凭证。
- `agent_release_event` 缺表会影响版本发布事件接口。
- `api_graph_edge.status` 缺列会影响 API 图谱点击和状态查询。

## SQL Cleanup Risk

历史 service SQL 曾经分散在多个模块。清理或迁移 SQL 前，不能只看文件名，要比对：

- 源 SQL 覆盖范围。
- `sql/initV2.sql` 覆盖范围。
- Entity `@TableName` 和字段。
- 索引和唯一约束。

## EmbedTokenService Startup Failure

如果后端启动失败并指向 `EmbedTokenService` / `EmbedChatController`，不要凭旧记忆假设已经修好。必须检查当前源文件和实际启动 stack trace。

过去出现过 `No default constructor found` 签名，也出现过修复中误用整文件写入导致文件被截断的风险。修复这类问题时优先用 patch 风格小改，并在改后重新启动或跑目标测试。

## Mojibake

Windows PowerShell 默认编码可能导致中文 Markdown、Vue 模板和日志读出来乱码。读取中文文件时使用 UTF-8。

常见处理：

```powershell
Get-Content -Encoding UTF8 path\to\file.md
```

不要把 mojibake 文本当作业务命名继续复制到代码里。

## Agent Definition And Legacy Agent Canvas

症状：新代码仍读写 `agent_definition`、调用 `/api/agent/definitions`，或把用户导向 `agent/:id/studio`（历史 Agent 画布兼容路由）。

处理顺序：

1. Agent 身份/策略/入口 → `runtime_agent` + `/api/agents` + `AgentController`。
2. GraphSpec / 画布 / 发布 → `runtime_workflow` + `/api/workflows` + `WorkflowStudio.vue`。
3. Agent 执行配置 → `runtime_agent_config_version` + `/api/agents/{agentId}/config-versions`。
4. Agent 可选 Workflow → `runtime_agent_workflow_tool`，随配置版本保存和发布。
5. Agent 执行工具集必须来自当前 ACTIVE `runtime_agent_config_version` 对应的 `runtime_agent_workflow_tool`；不要从页面、路由或其他旁路推导 Workflow。
6. 不要在新功能中恢复 `AgentManageController`、`AgentStudio.vue` 或 `agent_definition` 字段语义。

历史例子：

- 在 `agent_definition` 上保存 `graph_spec_json` 会导致 Runtime 与 Studio 数据源分裂。
- 遗留 `/api/agents/{agentId}/versions` 与 Workflow 版本并存时，新发布应只走 `/api/workflows/{workflowId}/versions`。
- 页面动作未等待目标 Page Bridge 就绪就执行，会把跨路由动作投递给旧页面实例；必须检查同 session 的 `NAVIGATE -> TARGET_READY -> PAGE_ACTION` 阶段。

## Legacy Studio Panel Build Failures

Workflow Studio 页面复杂，中文模板、条件面板、节点配置和类型定义容易互相影响。处理方式：

1. 先看 `ai-admin-front/src/types/workflow.ts`（Workflow 模型）与 `agent.ts`（共享 Graph 节点类型）。
2. 再看 `WorkflowStudio.vue` 和 `studio-panels/` 组件。
3. 跑 `npx vue-tsc --noEmit` 或 `npm run build`。
4. 按报错行小批量修，不要整页重写。

## GraphSpec Versus Canvas Confusion

如果功能在画布上显示正常但 Runtime 不执行，优先检查是否只改了 `canvas_json` 或前端 snapshot，没写入 `runtime_workflow.graph_spec_json`。

如果 Runtime 执行正常但画布显示不对，优先检查 `workflowStudioToCanvas`、`graphSpecToCanvas`、layout 信息和前端渲染转换。

## Node And NPM Environment

前端构建可能受本机 Node 版本影响。不要因为一次环境失败就判断代码错误。先记录 Node/npm 版本，再看是否是依赖或环境问题。

## Project Scope UI

项目选择器和项目管理页之间有特殊行为：管理项目、扫描项目等页面可能需要回到“全部项目”。修改这类行为时先检查 `MainLayout.vue`、`ProjectSelector.vue` 和 project store。

## Theme Hard-Coding

暗色/亮色 bug 往往来自硬编码颜色或 Element Plus 组件内部样式。修复时优先用共享变量和集中 override，不要在局部页面继续叠加硬编码。

## Response Envelope

前端接口失败不一定是前端 bug。先确认后端响应 envelope、`data` 层级、错误码和 API client 封装，再决定改前端还是后端。

## Workflow 安全身份与 HTTP DNS

- 凭据 / Knowledge ACL 绝不能从 GraphSpec、`params`、模型 tool args 或业务 Map 的 `projectId`/`projectCode`/`userId` 取值；只认 `SupervisorRequest.identity` / `WorkflowExecutionIdentity`。
- Control 公开 execute/stream 若直接把 body 转发给公开 Runtime 而不走 HMAC 签名的 internal 身份契约，攻击者可用 body `userId` 伪造 ACL；Embed sync/stream 同理，必须经 `RuntimeTrustedAgentExecutionGateway`（签名）传 `EMBED_SESSION`。
- `/internal` 路径名、localhost、Docker/K8s 网络隔离都不是认证；缺 `REACHAI_INTERNAL_SERVICE_SECRET`、缺 header、错签、过期、nonce 重放、body digest 不匹配必须 fail-closed。多实例必须用共享 nonce 表，不能只靠单机内存。
- nonce 满容时绝不能删除仍有效的旧 nonce 来腾位置——否则同一 nonce 可被立即重放。正确策略：只删过期；满容拒绝新 nonce。`nonceTtlSeconds` 至少 `2 * skewSeconds`（覆盖客户端未来 skew）。
- HMAC 必须绑定实际 HTTP body 字节的 SHA-256；只签 header 身份会导致业务 body 与签名解绑。Control 应对最终发送字节签名，Runtime 用 cached-body 验同一组字节后再交给 Controller。
- RunOps/Trace 审计 `userId` 只能来自 `WorkflowExecutionIdentity`；Debug/finishAgent/finishWorkflow/ToolCallLog 出口必须在持久化边界再次 sanitize，不能信任调用方已清洗。
- Control/Runtime 必须注入同一个 Secret；readiness 缺密钥应显示 `INTERNAL_AUTH_NOT_CONFIGURED`，不要等第一次请求才失败。
- 直接调用 Controller 方法不能称为“服务端 attested”；须用 MockMvc/HTTP 层证明 Filter 生效。
- PROJECT credential 授权禁止 `idMatch || codeMatch`：两侧都可比对时必须同时匹配，冲突一律拒绝。
- HTTP egress 校验后再 `InetAddress.getAllByName` 会固定到第二次解析结果，属于 TOCTOU；必须注入 `WorkflowDnsResolver`，每 hop 解析一次并 pin 同一组地址。
- Trace 生产源码不得硬编码测试 marker；`inputSummary` 不得存 message 原文；随机敏感值不得出现在 Span/ToolLog/Run/Guard Entity。Replay 禁止从脱敏摘要伪造用户问题。
- Knowledge internal `userId` 缺失不得回退 `system`；MockMvc 验收必须挂 Validator + Jackson MessageConverter。
- Runtime Knowledge 路径若仍出现 `RetrievalTestRequest` / `retrievalTest`，视为未完成正式 Core 抽取。
- 编辑 `RuntimeWorkflowNodeCapabilityRegistry.java` 后务必检查并去掉 UTF-8 BOM（`\ufeff`），否则 javac 直接失败。
