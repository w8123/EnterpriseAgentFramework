# Workflow Authoring 统一内核

## 目标

Workflow Studio 网页内 AI 与 Cursor、Codex、Claude Code、外部 CLI 等 AI Coding 客户端共享同一套 Workflow 修改能力。共享的是 GraphSpec 语义修改、候选校验、canvas 投影和并发保存规则；鉴权、自然语言理解和交互形态不强行合并。

## 分层

```text
Workflow Studio（Bearer / 登录态）
  自然语言 -> 创建或修改 operations -> 有界修复循环
                                      \
                                       GraphSpec Mutation Kernel
                                      /   -> release validation
AI Coding / CLI（X-ReachAI-AiCoding-Key） -> canvas projection
  直接提交结构化 operations               -> revision check / save
```

### 入口适配层

- Workflow Studio 只展示底部“AI 编排”单入口，不再要求用户先选择“生成”或“修改”。已有 GraphSpec、画布选择和自然语言共同决定 operations。
- Workflow Studio 接收自然语言、当前 GraphSpec、选中节点/边和资源目录。模型只负责把意图转换成结构化 operations。
- AI Coding / CLI 直接读取 context 并提交 operations，不要求再调用平台模型。
- 两个入口保留不同鉴权。外部 `aiCodingKey` 不能替代平台登录态，网页 Bearer 也不能绕过外部 AI Coding guard。

### GraphSpec 修改内核

`RuntimeWorkflowGraphMutationService` 是共享语义内核：

- 输入只有当前 GraphSpec 和原子 operations；
- 不调用模型、不鉴权、不执行 Workflow、不直接持久化；
- 在 GraphSpec 深拷贝上原子应用整批操作；
- deep merge 嵌套 patch，禁止修改 node / edge id；
- 检查 edge、entry、finish 引用；
- 删除节点时同步清理关联边和 entry / finish；
- 输出候选 GraphSpec、changedNodes、changedEdges 和摘要。

支持的操作为 `ADD_NODE`、`UPDATE_NODE`、`DELETE_NODE`、`ADD_EDGE`、`UPDATE_EDGE`、`DELETE_EDGE`、`SET_ENTRY`、`SET_FINISH`。

### Proposal 校验与修复

网页 AI 的创建和修改都必须调用发布链路同一套 `RuntimeWorkflowReleaseValidationService.validateProposed()`。发现确定性错误后，`RuntimeWorkflowDraftRepairService` 最多执行两轮：

1. 把候选 GraphSpec 和结构化校验错误交给模型；
2. 模型只返回最小 GraphSpec operations；
3. 统一修改内核生成新候选；
4. 再次执行确定性校验。

循环只操作内存候选，不保存、不发布、不运行带副作用节点。达到两轮上限仍失败时，返回未解决错误，由用户继续修改或放弃。

### Canvas 和持久化

- GraphSpec 始终是运行语义真相；`canvas_json` 只从候选 GraphSpec 投影和布局。
- `dryRun=true` 只返回 Proposal。
- `dryRun=false` 仍需 `validation.valid=true` 才能保存；无效 Proposal 返回 `saved=false`，不得落库。
- 保存前使用 `baseRevision` 做乐观并发检查；冲突后必须重新读取 context 并合并。

## AgentScope 与外部编码工具

AgentScope 适合继续承载网页内的意图理解、工具选择和有限步骤编排，但只能通过候选工具调用本页内核，不能直接写 Workflow 表。当前有界的“生成/修改 -> 校验 -> 修复”循环已经独立于具体 Agent 框架，后续替换为 AgentScope authoring adapter 不会改变 GraphSpec 修改契约。

OpenCode、Codex、Cursor 等属于外部客户端或代码上下文提供者，不应成为 Runtime 的必选依赖。它们通过 Workflow AI Coding API / MCP 复用同一内核即可。

## 兼容入口

- `/api/workflows/studio/generate-draft`：保留给 Page Assistant 等既有调用方的创建兼容入口；GraphSpec-first，执行发布级候选校验和有界修复。Workflow Studio 主界面不再单独暴露此入口。
- `/api/workflows/studio/edit-draft`：网页修改兼容入口；自然语言先转 operations，再调用统一修改内核。
- `/api/workflows/{workflowId}/ai-coding/patch`：外部结构化 patch 入口；直接调用统一修改内核。

兼容路由可以保留，但不得再复制 canvas-first 修改规则。
