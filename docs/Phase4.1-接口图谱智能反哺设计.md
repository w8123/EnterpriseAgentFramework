# Phase 4.1 接口图谱智能反哺设计

> 承接 `接口图谱-设计与落地.md` 的二期规划。目标不是再做一张更复杂的图，而是让接口图谱进入 Agent 调用链路，帮助 Agent 知道“先调哪个接口拿哪个字段，再把字段传给哪个接口”。

## 一、阶段目标

Phase 4.0 已经完成 API / FIELD / DTO / MODULE 节点投影、`MODEL_REF` 自动边、运营手动连线和布局持久化。Phase 4.1 需要补齐四件事：

1. 自动推断 `REQUEST_REF` / `RESPONSE_REF` 候选边。
2. 提供候选边确认工作台，让运营确认、忽略或转为手工边。
3. 将已确认的参数来源关系反哺给 Tool 描述和 Tool Retrieval。
4. 支持按模块、接口、邻居层数加载子图，避免大项目一次拉全图。

最终闭环：

```mermaid
flowchart LR
  scan[扫描项目] --> semantic[AI语义理解]
  semantic --> graph[接口图谱投影]
  graph --> infer[候选边推断]
  infer --> review[运营确认]
  review --> hints[参数来源提示]
  hints --> agent[Agent选Tool和填参]
  agent --> trace[运行时Trace]
  trace --> infer
```

## 二、边类型与语义

现有边保留：

- `MODEL_REF`：字段共享同一个 DTO / 复合类型。
- `BELONGS_TO`：字段归属 DTO。
- `REQUEST_REF`：某个请求入参依赖另一个接口的出参。
- `RESPONSE_REF`：某个响应字段可供另一个接口消费。

Phase 4.1 只新增候选态，不新增新边种类。建议在 `api_graph_edge` 上扩展：

- `status`：`CANDIDATE / CONFIRMED / REJECTED`，默认 `CONFIRMED` 兼容历史手工边。
- `infer_strategy`：`schema_match / dto_match / trace_value_match / llm_assisted`。
- `confirmed_by`、`confirmed_at`：运营确认审计。
- `reject_reason`：误报反馈，后续调权重。

若不希望改历史表，也可以新增 `api_graph_edge_candidate`，但推荐直接扩展边表：查询、布局和删除逻辑复用成本更低。

## 三、自动推断策略

### 3.1 Schema 匹配

基于字段路径、字段名、类型、所在 API 的 HTTP 方法和语义描述进行打分。

评分建议：

- 字段名完全相同：`+0.35`
- 字段名归一化后相同，如 `user_id/userId/id`：`+0.25`
- 类型相同或兼容，如 `Long` 与 `Integer`、`String` 与枚举：`+0.2`
- 出参接口语义包含“查询/登录/创建/获取”，入参接口语义包含“更新/删除/绑定/提交”：`+0.1`
- 同模块：`+0.05`
- 来自同一个 DTO：`+0.1`
- 同一 API 内部字段：直接跳过，避免噪音。

阈值：

- `confidence >= 0.85`：自动生成候选边并高亮推荐。
- `0.65 <= confidence < 0.85`：进入候选池，默认折叠。
- `< 0.65`：不入库。

### 3.2 DTO 匹配

沿用 Phase 4.0 的 `MODEL_REF` 基础，但只在“响应字段 → 请求字段”方向生成候选：

- `FIELD_OUT.type_name` 与 `FIELD_IN.type_name` 同 simple type。
- 或 `FIELD_OUT.parent DTO` 与 `FIELD_IN.parent DTO` 同名。
- 对 `Page<T>`、`List<T>`、`Result<T>`、`Response<T>` 做泛型剥离。

DTO 匹配适合给“整对象流转”提供候选，例如 `UserDTO` 查询结果直接作为更新接口 request body 的一部分。

### 3.3 Trace 值匹配

从 `tool_call_log` 中读取同一 `traceId` 内的工具调用顺序：

1. 解析前序 Tool 的 `result_summary` 或未来的结构化 `result_json`。
2. 解析后续 Tool 的 `args_json`。
3. 对具体值做匹配，例如前序返回 `userId=10086`，后续入参也出现 `10086`。
4. 若同一字段对在多个 trace 中重复出现，提升置信度。

为降低误报：

- 过滤布尔值、状态码、短数字、空字符串。
- 对手机号、身份证、订单号等高信息量值加权。
- 同一 trace 内必须满足“响应在前、请求在后”。

### 3.4 LLM 辅助解释

LLM 不直接创建最终边，只用于候选边解释：

- 为什么认为 `queryUser.response.data.id` 可供 `updateUser.request.userId` 使用。
- 给出业务语言说明。
- 不允许虚构不存在的 Tool 名、字段路径和 DTO。

## 四、后端设计

新增服务建议：

- `ApiGraphInferenceService`：统一调度 schema、DTO、trace 三类推断。
- `ApiGraphCandidateService`：候选边查询、确认、忽略、批量确认。
- `ApiGraphHintService`：根据已确认边生成 Tool 参数来源提示。

新增 API：

```text
POST /api/api-graph/projects/{projectId}/infer/request-response
GET  /api/api-graph/projects/{projectId}/candidates?status=CANDIDATE&minConfidence=0.65
POST /api/api-graph/projects/{projectId}/candidates/{edgeId}/confirm
POST /api/api-graph/projects/{projectId}/candidates/{edgeId}/reject
POST /api/api-graph/projects/{projectId}/candidates/batch-confirm
GET  /api/api-graph/projects/{projectId}/subgraph?moduleId=&apiId=&depth=1&nodeKinds=
GET  /api/api-graph/tools/{toolName}/param-hints
```

`subgraph` 查询返回：

- 当前模块或接口周边节点。
- 已确认边。
- 候选边数量摘要。
- 当前视口布局，缺失时前端局部布局。

## 五、Agent 反哺方式

### 5.1 Tool 描述增强

`DynamicHttpAiTool.description()` 当前优先使用 `ai_description`。Phase 4.1 建议追加一段短提示：

```text
参数来源提示：
- request.userId 通常来自 queryUser 的 response.data.id。
- request.orderNo 通常来自 createOrder 的 response.orderNo。
```

控制原则：

- 每个 Tool 最多追加 5 条。
- 只使用 `CONFIRMED` 且 `confidence >= 0.8` 的边。
- 如果 Agent 本轮白名单里不包含来源 Tool，则不追加，避免诱导不可见能力。
- Tool Retrieval 建索引时可以纳入这些提示，但要标注来源，便于调试。

### 5.2 Agent Studio 提示

在 Studio 画布选中 Tool 节点时展示：

- 必填参数缺少来源。
- 已知来源 Tool。
- 可一键添加前置 Tool 节点并连线。

这会把接口图谱进一步变成 Workflow 草稿生成器。

## 六、前端设计

`ApiGraphCanvas.vue` 增加三个交互区：

1. 候选边面板：按置信度、策略、字段名过滤；支持确认、忽略、批量确认。
2. 子图模式：按模块、API、邻居层数加载，默认不再强制全图。
3. 参数来源抽屉：选中 Tool/API 节点时展示“该接口入参通常来自哪里”。

建议在扫描项目详情页顶部增加图谱状态：

- 节点数、确认边数、候选边数。
- 最近推断时间。
- “待确认候选边”入口。

## 七、数据保留与重扫策略

- 手工边和已确认边重扫后保留。
- 候选边每次推断可按 `infer_strategy` 批量刷新。
- 若源字段或目标字段消失，边标记为 `STALE` 或在查询时进入“失效关系”列表。
- 运营拒绝过的候选边保留 `REJECTED`，避免下一次重复推荐。

## 八、验收用例

1. 扫描一个包含“查询用户”和“更新用户”的项目，系统生成 `response.id -> request.userId` 候选边。
2. 运营确认候选边后，刷新图谱仍存在，并且状态为 `CONFIRMED`。
3. 重跑推断不会覆盖手工边和已确认边。
4. Agent 调用目标 Tool 前，Tool 描述中出现参数来源提示。
5. 当来源 Tool 不在当前 Agent 白名单内时，参数来源提示不出现。
6. 大项目可以按模块打开子图，不需要一次加载全部节点。
7. `tool_call_log` 中多次出现相同值流转后，候选边置信度提升。

## 九、推荐拆分

1. `4.1.1`：表结构扩展 + 候选边 API + schema/DTO 推断。
2. `4.1.2`：前端候选边确认工作台 + 子图加载。
3. `4.1.3`：Tool 描述反哺 + Agent Studio 参数来源提示。
4. `4.1.4`：Trace 值匹配 + LLM 候选解释。
