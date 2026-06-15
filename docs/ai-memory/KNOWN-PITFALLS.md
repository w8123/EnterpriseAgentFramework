# ReachAI Known Pitfalls

## SQL Drift

症状：后端报 `Unknown column`、`Table doesn't exist` 或 MyBatis SQLSyntaxErrorException。

处理顺序：

1. 找到报错接口和 Mapper。
2. 找实体字段和表名。
3. 查 `sql/init.sql` 是否有表、列、索引。
4. 查是否需要新增 `sql/upgrade-*.sql`。
5. 不要只改 Java 或前端响应处理。

历史例子：

- `agent_workflow_credential` 缺表会影响 Workflow Studio 工作流凭证。
- `agent_release_event` 缺表会影响版本发布事件接口。
- `api_graph_edge.status` 缺列会影响 API 图谱点击和状态查询。

## SQL Cleanup Risk

历史 service SQL 曾经分散在多个模块。清理或迁移 SQL 前，不能只看文件名，要比对：

- 源 SQL 覆盖范围。
- `sql/init.sql` 覆盖范围。
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

## Agent Definition And Agent Studio Legacy

症状：新代码仍读写 `agent_definition`、调用 `/api/agent/definitions`，或把用户导向 `agent/:id/studio`（Agent Studio 兼容路由）。

处理顺序：

1. Agent 身份/策略/入口 → `ai_agent` + `/api/agents` + `AgentEntryController`。
2. GraphSpec / 画布 / 发布 → `ai_workflow` + `/api/workflows` + `WorkflowStudio.vue`。
3. Agent 到 Workflow 路由 → `ai_agent_workflow_binding` + `/api/agents/{agentId}/workflow-bindings`。
4. 不要在新功能中恢复 `AgentManageController`、`AgentStudio.vue` 或 `agent_definition` 字段语义。

历史例子：

- 在 `agent_definition` 上保存 `graph_spec_json` 会导致 Runtime 与 Studio 数据源分裂。
- 遗留 `/api/agents/{agentId}/versions` 与 Workflow 版本并存时，新发布应只走 `/api/workflows/{workflowId}/versions`。

## Agent Studio Build Failures

Workflow Studio 页面复杂，中文模板、条件面板、节点配置和类型定义容易互相影响。处理方式：

1. 先看 `ai-admin-front/src/types/workflow.ts`（Workflow 模型）与 `agent.ts`（共享 Graph 节点类型）。
2. 再看 `WorkflowStudio.vue` 和 `studio-panels/` 组件。
3. 跑 `npx vue-tsc --noEmit` 或 `npm run build`。
4. 按报错行小批量修，不要整页重写。

## GraphSpec Versus Canvas Confusion

如果功能在画布上显示正常但 Runtime 不执行，优先检查是否只改了 `canvas_json` 或前端 snapshot，没写入 `ai_workflow.graph_spec_json`。

如果 Runtime 执行正常但画布显示不对，优先检查 `workflowStudioToCanvas`、`graphSpecToCanvas`、layout 信息和前端渲染转换。

## Node And NPM Environment

前端构建可能受本机 Node 版本影响。不要因为一次环境失败就判断代码错误。先记录 Node/npm 版本，再看是否是依赖或环境问题。

## Project Scope UI

项目选择器和项目管理页之间有特殊行为：管理项目、扫描项目等页面可能需要回到“全部项目”。修改这类行为时先检查 `MainLayout.vue`、`ProjectSelector.vue` 和 project store。

## Theme Hard-Coding

暗色/亮色 bug 往往来自硬编码颜色或 Element Plus 组件内部样式。修复时优先用共享变量和集中 override，不要在局部页面继续叠加硬编码。

## Response Envelope

前端接口失败不一定是前端 bug。先确认后端响应 envelope、`data` 层级、错误码和 API client 封装，再决定改前端还是后端。
