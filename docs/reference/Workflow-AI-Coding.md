# Workflow AI Coding

Workflow AI Coding 是面向 Cursor、Codex、Claude Code 等 AI 编程工具的 **Workflow 层工程化接口**。

它与 Workflow Studio 的关系：

- **Workflow Studio**：人类可视化编辑器；网页内 AI 由 AgentScope Authoring Adapter 理解自然语言，通过受约束工具修改内存候选 GraphSpec，再进入统一修改内核与发布级校验。
- **Workflow AI Coding**：Cursor / Codex / CLI 等外部工具直接提交结构化 GraphSpec operations，也进入同一修改内核。
- **核心语义对象**：`Workflow.graph_spec_json`（`GraphSpec`），不是 canvas，也不是裸 Graph 层。
- **禁止直接改数据库**：所有变更必须走 `/api/workflows/{workflowId}/ai-coding/*` 或现有 Workflow API。

网页 AI 的创建/修改与外部 AI Coding 共用 `RuntimeWorkflowGraphMutationService`、发布级 Proposal 校验和 Canvas 布局投影。Workflow Studio `/proposals/edit` 由 AgentScope 根据工具错误有限重试；`/proposals/generate` 可对同一选定模型做最多两轮候选修复。二者都不是独立“修复模型”，且在用户应用前不保存、不发布、不执行 Workflow。OpenCode / Codex / Cursor 仍是外部 AI Coding 客户端，不是 Runtime 必选依赖。架构边界见 [Workflow Authoring 统一内核](../architecture/workflow-authoring-kernel.md)。

Agent 负责稳定身份和入口；版本化 Supervisor 配置通过 Workflow-as-Tool 白名单选择已发布 Workflow。Page Assistant 只是 Workflow AI Coding 的后续使用场景之一，不是本协议的核心对象。

## API 列表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/workflows/ai-coding/workflows` | 创建 Workflow Working Copy；PAGE_ASSISTANT 必须提交资源绑定 |
| GET | `/api/workflows/{workflowId}/ai-coding/context` | 获取 AI Coding 上下文 |
| PUT | `/api/workflows/{workflowId}/ai-coding/resource-bindings` | 仅在首次发布前替换 DRAFT Workflow 的一等资源绑定 |
| POST | `/api/workflows/{workflowId}/ai-coding/validate` | 校验当前或提案 GraphSpec |
| POST | `/api/workflows/{workflowId}/ai-coding/patch` | 结构化 patch GraphSpec |
| POST | `/api/workflows/{workflowId}/ai-coding/run` | 调试运行 Workflow Working Copy |
| GET | `/api/workflows/{workflowId}/ai-coding/versions` | 查看当前 Working Copy 校验、ACTIVE version 和历史版本 |
| POST | `/api/workflows/{workflowId}/ai-coding/publish` | 发布校验通过的 Working Copy，创建 ACTIVE Workflow version |
| GET | `/api/workflows/{workflowId}/ai-coding/runs` | 查看近期 AI Coding debug run |
| GET | `/api/workflows/{workflowId}/ai-coding/page-assistant/catalog` | PAGE_ASSISTANT：catalog 与 PAGE_ACTION 匹配视图 |
| POST | `/api/workflows/{workflowId}/ai-coding/page-assistant/validate` | PAGE_ASSISTANT：校验 PAGE_ACTION 节点 |
| POST | `/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test` | PAGE_ASSISTANT：安全 smoke test |

认证使用项目级 `aiCodingKey`，必须通过 `X-ReachAI-AiCoding-Key` header 传递，不使用平台登录 Cookie / Bearer token，也不要把 key 拼进 URL query。服务端按 `workflow.projectId` 复用 `AiCodingAccessGuard` 校验项目 key；Page Assistant 子资源同样走 Workflow 层校验。

外部工具如需先发现项目可用能力，可读取项目级 AI Coding Gateway manifest：`GET /api/ai-coding/projects/{projectId}/manifest`。该 manifest 暴露 SDK 快速接入、业务页面工作台任务、Workflow AI Coding、Context Candidate 的入口与认证约定；不会回显原始 `aiCodingKey`。Workflow 的可执行语义仍以本页 `/api/workflows/{workflowId}/ai-coding/*` 和 `GraphSpec` 为准。

## Context 示例

```bash
curl -s -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/context"
```

返回包含：

- `workflow`：id、name、keySlug、projectId、projectCode、workflowKind、executionEngine、definitionAuthority、creationChannel、status、defaultModelInstanceId
- `graphSpec`：当前运行语义
- `canvas`：当前画布布局（只读参考）
- `validation`：`WorkflowReleaseValidationService` 结果
- `nodeTypes`：`RuntimeWorkflowNodeCapabilityRegistry` 当前开放目录
- `runtimeHints`：运行时能力与限制说明
- `pageAssistantContext`：附带一等 `resourceBindings`、TARGET `pageKey` 和 GraphSpec 中的 `actionKeys`
- `warnings`：当前 Workflow 风险提示

## Resource Bindings

通用 Workflow 页面创建的 PAGE_ASSISTANT 草稿如果尚未绑定页面，或首次发布前需要纠正页面范围，使用：

`PUT /api/workflows/{workflowId}/ai-coding/resource-bindings`

```json
{
  "baseRevision": "2026-07-25T10:30:00",
  "reason": "bind the verified orders page",
  "resourceBindings": [
    {
      "resourceType": "PAGE",
      "resourceKey": "orders.list",
      "bindingRole": "TARGET"
    }
  ]
}
```

`baseRevision` 必填，使用最新 context 的 `workflow.updatedAt`。PAGE_ASSISTANT 必须恰好有一个 `TARGET PAGE`；其他确实需要访问的页面使用 `RELATED PAGE`。接口只接受 `DRAFT`，首次发布后资源绑定永久冻结；后续版本只修改 GraphSpec，不允许把已经发布、授权和验收的 Workflow 悄悄移到另一个页面。

## Validate 示例

校验当前 Working Copy：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/validate" \
  -d '{}'
```

校验提案 GraphSpec（不保存）：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/validate" \
  -d '{
    "mode": "PROPOSED",
    "graphSpec": {
      "schemaVersion": 2,
      "entryNodeId": "start",
      "exitNodeIds": ["answer"],
      "nodes": [
        {"id": "start", "type": "USER_INPUT", "name": "Start"},
        {"id": "answer", "type": "ANSWER", "name": "Answer"}
      ],
      "edges": [
        {"id": "e1", "from": "start", "to": "answer", "condition": "always"}
      ]
    }
  }'
```

## Patch 协议

Patch 操作对象是 **GraphSpec**，使用结构化 JSON operations，不允许整段字符串替换。

```json
{
  "baseRevision": "2026-06-16T10:00:00",
  "dryRun": true,
  "operations": [
    {
      "op": "ADD_NODE",
      "node": {
        "id": "llm",
        "type": "LLM",
        "name": "Main LLM",
        "config": {
          "prompt": "You are a helpful assistant.",
          "modelInstanceId": "llm-main"
        }
      }
    },
    {
      "op": "ADD_EDGE",
      "edge": {
        "id": "start-to-llm",
        "from": "start",
        "to": "llm",
        "condition": "always"
      }
    },
    {
      "op": "UPDATE_NODE",
      "nodeId": "answer",
      "patch": {
        "config": {
          "answerConfig": {
            "template": "Done: {{answer}}"
          }
        }
      }
    },
    {
      "op": "DELETE_NODE",
      "nodeId": "legacy-node"
    },
    {
      "op": "DELETE_EDGE",
      "edgeId": "old-edge"
    },
    {
      "op": "SET_ENTRY_NODE",
      "entryNodeId": "llm"
    },
    {
      "op": "SET_EXIT_NODES",
      "exitNodeIds": ["answer"]
    }
  ],
  "layout": {
    "autoLayout": true,
    "direction": "LR",
    "columnGap": 88,
    "rowGap": 56
  },
  "reason": "AI Coding: add LLM node between start and answer"
}
```

### Patch 行为说明

- **`dryRun` 默认值**：请求体省略 `dryRun` 或传 `null` 时，视为 `true`（只预览，不保存）。只有显式 `"dryRun": false` 才会落库。
- `dryRun=true`：返回 `proposedGraphSpec`、`proposedCanvas`、`validation`，**不保存**。
- `dryRun=false`：保存到 Workflow **Working Copy**（`graph_spec_json` / `canvas_json`），不修改已发布 version 快照；**必须** patch 操作无错误且 `validation.valid=true`。
- operations 支持 `ADD_NODE`、`UPDATE_NODE`、`DELETE_NODE`、`ADD_EDGE`、`UPDATE_EDGE`、`DELETE_EDGE`、`SET_ENTRY_NODE`、`SET_EXIT_NODES`。
- 所有 operations 会先作用于 GraphSpec 深拷贝；任一操作失败时整批失败，不会留下部分修改。
- `UPDATE_NODE` / `UPDATE_EDGE` 对嵌套对象做 deep merge，并禁止修改节点或边的 `id`。
- `DELETE_NODE` 会删除关联边，并清理对应的 `entryNodeId` / `exitNodeIds` 引用。
- 新增或修改边、设置入口或出口节点时会检查引用的节点是否存在。
- 不支持 `op` / node type 会返回明确错误。
- `canvas_json` 只保存 `schemaVersion=1 / layoutVersion=1`、viewport、节点位置/尺寸/折叠状态和边展示样式，不保存 GraphSpec 节点数据或拓扑。
- create 会从 GraphSpec 投影并整理 Canvas；patch 的 `layout.autoLayout` 默认为 `true`。当前 `layered` 布局以卡片边界计算间距，默认层间距 `88px`、同层间距 `56px`，支持分支、汇合、回环和断开组件，并写入 `canvas_json.nodes[].position`。`autoLayout=false` 时保留已有节点坐标，只为缺少坐标的新节点补位。
- Workflow Studio 的「自动整理」使用相同的 LR 分层和间距契约，并以浏览器实测卡片尺寸和 Handle 位置进行最终排布；后端 AI Coding 在没有 DOM 尺寸时使用节点类型估算值。
- AI Coding 回传或保存前请确认 `canvasJson.nodes[].position` 已合理分布；Canvas 只负责布局，运行语义始终完整写入 GraphSpec。
- `baseRevision` 可选，值为 Workflow `updatedAt.toString()`；格式非法时返回 HTTP 400 和 `WORKFLOW_BASE_REVISION_INVALID`。不匹配时返回 HTTP 409、`WORKFLOW_WORKING_COPY_CONFLICT`、`currentRevision` 和对应 `ETag`，调用方应保留本地修改并重新加载最新 revision 后再合并。
- `reason` 在 `dryRun=false` 保存成功时写入 guard audit log。

### dryRun patch 示例

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/patch" \
  -d @patch-dry-run.json
```

### 保存 patch 示例

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/patch" \
  -d '{
    "dryRun": false,
    "operations": [
      {
        "op": "ADD_NODE",
        "node": {"id": "answer", "type": "ANSWER", "name": "Answer"}
      }
    ]
  }'
```

## Run 示例

普通 Workflow 调试运行：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/run" \
  -d '{
    "message": "hello",
    "input": {"foo": "bar"},
    "runtimeContext": {
      "traceId": "trace-demo-1"
    }
  }'
```

dryRun（不执行 runtime）：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/run" \
  -d '{"dryRun": true, "message": "hello"}'
```

**Run 默认 `dryRun=false`**（会尝试执行）。安全约束：

- 含 `PAGE_ACTION` 且缺少 `embedSessionId` / 非空 `pageBridge` / 非空 `pageContext` / `bridgeGlobal` 时返回 `SKIPPED`。
- 含 `HTTP_REQUEST` / `TOOL` / `CAPABILITY` / `MCP_CALL` / `KNOWLEDGE_WRITE` 等副作用节点时，需 `runtimeContext.confirmSideEffects=true` 才会执行。
- 即使执行成功，`PAGE_ACTION` 也只是 queue 客户端动作，不保证真实页面已执行。

返回字段：

- `status`：`SUCCESS` / `FAILED` / `SKIPPED` / `DRY_RUN`
- `answer`、`traceId`、`runId`
- `nodeOutputs`：步骤级输出摘要
- `errors`、`warnings`
- `metadata`：workflow / runtime 上下文说明

执行成功后可用 `GET /api/workflows/{workflowId}/ai-coding/runs?limit=20&days=7` 查看近期 debug run，
或用 `GET /api/workflows/{workflowId}/ai-coding/runs/{traceId}` 查看单次轨迹详情。

## Publish 示例

Workflow AI Coding 可以发布 **当前 Workflow Working Copy**，但发布必须走 `WorkflowVersionService.publish()`，因此仍会执行 Workflow Studio 同一套 release validation。它不会绕过发布校验，也不会直接改数据库 version 快照。

SDK 快速接入只创建或复用项目 Agent，并发布 ACTIVE AgentScope Supervisor 配置；它不会创建占位 Workflow。AI Coding 为真实业务能力创建 Workflow、完成 GraphSpec 绘制并保存 Working Copy 后，应先读取版本状态：

```bash
curl -s -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/versions"
```

当返回的 `releaseValidation.valid=true` 后，重新读取一次 `GET .../context`，取最新的 `workflow.updatedAt` 作为 `baseRevision`，再执行首次发布：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-1/ai-coding/publish" \
  -d '{
    "version": "v1.0.0",
    "note": "initial AI Coding publish",
    "publishedBy": "Cursor",
    "baseRevision": "2026-07-14T10:30:00"
  }'
```

发布成功后会生成 ACTIVE Workflow version；只有已发布 Workflow 才能通过 `/api/workflows/{workflowId}/page-assistant/attach-tool` 等显式入口加入 Agent 配置版本的 Workflow-as-Tool 白名单并由 Supervisor 执行。若发布前 Working Copy 已被其他编辑更新，接口返回 `409` 且不会创建版本；调用方必须重新读取 context、重新校验，再用新的 `workflow.updatedAt` 发布。若版本号已存在，应先读取 `/versions`，选择下一个语义化版本号后重试。不要创建没有实际业务语义的默认 Workflow。

## PAGE_ASSISTANT 扩展 API

当 `workflowKind=PAGE_ASSISTANT` 时，Workflow AI Coding 提供 **workflow-scoped** 的 Page Assistant 子资源。它们复用项目 AI Coding Key、Workflow 应用服务、Control internal 页面操作目录与 Runtime 发布校验，不再存在另一套页面 onboarding session。

非 `PAGE_ASSISTANT` Workflow 调用下列接口会返回 `400`（`WORKFLOW_KIND_NOT_SUPPORTED`）。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/workflows/{workflowId}/ai-coding/page-assistant/catalog` | 返回 pageKey、GraphSpec 中 PAGE_ACTION 节点与 catalog 匹配视图 |
| POST | `/api/workflows/{workflowId}/ai-coding/page-assistant/validate` | 校验 PAGE_ACTION 节点（可选传入提案 `graphSpec`） |
| POST | `/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test` | 安全 smoke test（默认 `dryRun=true`） |

### PAGE_ACTION 语义约定

运行语义**不依赖固定节点 id**，而依赖：

- `node.type = PAGE_ACTION`
- `config.pageKey`
- `config.actionKey`
- `config.args`
- `outputAlias` / `config.outputAlias`

节点 id 仅用于画布与可读性；模板可推荐 `user_input` / `extract_filters` / `set_filters` / `search` / `read_table` / `answer` 等 id，但平台不能靠 id 判断语义。

### Catalog 示例

```bash
curl -s -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" \
  "http://localhost:8080/api/workflows/wf-page/ai-coding/page-assistant/catalog"
```

返回字段：

- `workflowId` 与 `context.resourceBindings[]`
- `context.pageKey`：恰好一个 `TARGET PAGE` 绑定
- `pageActionNodes[]`：GraphSpec 中每个 PAGE_ACTION 节点，含 `matchStatus`（`MATCHED` / `MISSING` / `INACTIVE` / `UNBOUND_PAGE` / `ACTION_KEY_EMPTY` / `PAGE_KEY_EMPTY`）
- `catalogActions[]`：所有 `TARGET` / `RELATED` PAGE 绑定对应的真实 Control 页面操作目录
- `warnings`：目录服务不可用时明确标记，不能把不可用伪装为空目录

### Page Assistant Validate 示例

校验当前 Workflow Working Copy：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-page/ai-coding/page-assistant/validate" \
  -d '{}'
```

校验提案 GraphSpec（不保存）：

```bash
curl -s -X POST -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/workflows/wf-page/ai-coding/page-assistant/validate" \
  -d '{
    "graphSpec": {
      "schemaVersion": 2,
      "entryNodeId": "search",
      "exitNodeIds": ["search"],
      "nodes": [
        {
          "id": "search",
          "type": "PAGE_ACTION",
          "config": {
            "pageKey": "orders.list",
            "actionKey": "openDetail",
            "args": {"managerName": "Alice"},
            "outputAlias": "search_result"
          }
        }
      ],
      "edges": []
    }
  }'
```

返回结构化 `items[]`，每项含：

- `nodeId`、`pageKey`、`actionKey`、`matchStatus`
- `errors[]` / `warnings[]`：结构化 finding（`code`、`level`、`field`、`message`）

常见发布校验 code：

- `GRAPH_PAGE_ACTION_CATALOG_MISSING`：actionKey 不在 Control catalog
- `GRAPH_PAGE_ACTION_CATALOG_INACTIVE`：catalog 存在但非 ACTIVE
- `GRAPH_PAGE_ACTION_ARGS_REQUIRED_MISSING`：`config.args` 未覆盖 `inputSchema.required`
- `GRAPH_PAGE_ACTION_PAGE_UNBOUND`：节点 pageKey 不在 Workflow 的 PAGE 绑定中
- `PAGE_RESOURCE_BINDING_INVALID`：没有且仅有一个 TARGET PAGE

`confirmRequired=true` 的动作会附加 `GRAPH_PAGE_ACTION_CONFIRM_REQUIRED` warning，真实执行仍由 Runtime Guard 与用户确认策略决定。

### Page Assistant Smoke Test

`POST /api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test` 复用真实 Workflow debug run，不维护另一套虚构 smoke 状态。请求体与通用 `/run` 相同：

```json
{
  "dryRun": true,
  "message": "检查订单详情页动作",
  "input": {},
  "runtimeContext": {}
}
```

发布契约检查应先调用 `/page-assistant/validate`。需要执行 PAGE_ACTION 时，必须提供真实 `embedSessionId` / Page Bridge 上下文并经过 Runtime Guard；debug run 的成功只证明本次运行结果，不等同于真实业务浏览器验收。浏览器验收必须由“业务页面工作台”的 `BROWSER_ACCEPTANCE` 任务回传版本化验收报告。

## PAGE_ASSISTANT 注意事项（通用 Run）

当 GraphSpec 含 `PAGE_ACTION` 节点时：

- **不能**仅凭 GraphSpec 判断页面动作已在真实页面执行成功。
- 真实执行需要 embed session / page bridge runtime context（例如 `embedSessionId`、`pageBridge`、`pageContext`）。
- 若缺少 bridge context，`/run` 返回 `SKIPPED` 和明确 `warnings`，**不会伪造成功**。
- 页面地图、单页面分析、实施报告与浏览器验收统一通过 `/api/ai-coding/handoffs/{handoffId}/activate` 激活，再以任务级 Bearer Token 调用 `/api/ai-coding/tasks/{taskId}/*`；Workflow AI Coding 只负责 Workflow Working Copy、GraphSpec、页面资源绑定、调试、发布和版本。

## 架构原则（给 AI 工具）

1. 入口是 **Workflow 层**，不是裸 Graph 层。
2. 运行语义写入 `graph_spec_json`（GraphSpec）。
3. `canvas_json` 只是布局，不能作为运行语义来源。
4. 不允许直接操作数据库。
5. Agent 通过已发布配置版本维护 Workflow-as-Tool；不要恢复 Agent 画布入口。

Workflow AI Coding 使用项目级 `aiCodingKey`，工具链统一走 `X-ReachAI-AiCoding-Key` header，不走平台登录 Cookie；`aiCodingKey` 只能给 AI 工具、本机显式脚本或服务端使用，不得进入 URL、浏览器运行时代码或业务前端配置。
