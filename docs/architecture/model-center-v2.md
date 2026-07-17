# 模型中心 V2

> Date: 2026-07-17
> Scope: 第一阶段数据库/后端基座 + 第二阶段前端原子升级与 UI 改版
> Owner service: `reachai-model-service`
> Frontend: `ai-admin-front` 模型中心页面

## 领域对象

模型中心只保留两个对象。

### `model_template`

平台提供的模型目录和创建模板。模板不含真实 API Key；本阶段由 SQL 种子维护，只读：

- `GET /model/templates`
- `GET /model/templates/{id}`

`disabled` 模板禁止创建实例。

`params_schema_json` 本阶段种子统一为 `[]`。原因：尚无统一的参数 Schema 契约与前端渲染协议；完整模板驱动参数表单留到后续阶段。当前可调参数通过 `default_options_json` 保守提供（如 `temperature` / `max_tokens`），不会把仅用于展示的元数据（如 embedding 维度）放入会真实发送给供应商的 defaultOptions。

### `model_instance`

对 Agent / Workflow / Knowledge 暴露的稳定可执行资源。业务继续绑定稳定 `modelInstanceId`；实例不保存 `template_id`。

## URL 语义

不变量：

- `baseUrl`：OpenAI SDK 风格 API Root，包含供应商版本前缀（如 `/v1`、`/v2`、`/v1beta/openai`、`/api/v3`）。
- `chatPath` / `embeddingPath` / `rerankPath`：相对 API Root 的路径，例如 `/chat/completions`、`/embeddings`、`/rerank`。
- 使用通用 URL join，**没有**仅针对 `/v1` 的特殊拼接。

示例：

| API Root | Path | 结果 |
| --- | --- | --- |
| `https://generativelanguage.googleapis.com/v1beta/openai` | `/chat/completions` | `.../v1beta/openai/chat/completions` |
| `https://qianfan.baidubce.com/v2` | `/embeddings` | `.../v2/embeddings` |
| `https://ark.cn-beijing.volces.com/api/v3` | `/chat/completions` | `.../api/v3/chat/completions` |
| `https://{resource}.openai.azure.com/openai/v1` | `/chat/completions` | `.../openai/v1/chat/completions` |
| `https://api.openai.com/v1` | `/chat/completions` | `.../v1/chat/completions` |

## 受保护参数

`defaultOptions` 与业务请求 `options` 只能覆盖可调推理参数。以下字段永远不能被覆盖：

| 类型 | 受保护字段 |
| --- | --- |
| Chat | `model`, `messages`, `stream`, `stream_options`, `tools`, `tool_choice` |
| Embedding | `model`, `input` |
| Rerank | `model`, `query`, `documents`, `top_n` |

连接地址、路径、凭证、鉴权头只能来自实例 `connectionConfig`。创建/编辑时校验；运行时再次防御；非法字段返回明确错误，不静默改路由。

## 凭证与 BaseURL 绑定

编辑或草稿测试合并连接配置时：

1. 比较旧/新 `baseUrl` 的 scheme、host、port（authority）。
2. authority 变化时，不得复用旧 `apiKey` / token / secret；必须重新输入凭证。
3. 同一 authority 下仅改路径时可保留凭证。
4. URL 仅允许 `http`/`https`，禁止 userInfo，拒绝非法 URI。

### 剩余风险（本期不做完整 SSRF 阻断）

企业私有模型地址（内网 IP / 私有 DNS）是合法场景，因此本阶段不实施完整私网 SSRF 黑名单。剩余风险：持有模型管理权限的主体可能把实例 BaseURL 指到内网探测目标。未来应结合平台统一鉴权、审计与可选的出站策略收口该边界。

## 从模板创建

1. `disabled` 模板禁止创建。
2. `provider` / `modelType` / `protocol` 由模板固定。
3. 允许 `request.modelName` 在创建时覆盖模板默认值（Azure deployment、火山 endpoint、自部署）。
4. 所有模型类型创建后均可在同类型范围内更换上游 `provider` / `modelName`；`modelType` 与 `protocol` 仍不可变。
5. `defaultOptions` = template defaults + request overrides 按 key 合并，不能整体替换。

## `project_code` 与 `project_scope_key`

- `project_code` NULL = 全局；非空预留项目范围；本期不做强制隔离。
- `project_scope_key` 为 MySQL 生成列：`IFNULL(project_code,'') STORED`；应用层禁止写入。
- 名称在同一 scope 内唯一。
- **前端本期不展示、不选择、不伪造 `projectCode`。**

## 状态与测试

- 启停：`ACTIVE` / `DISABLED` / `ARCHIVED`（删除=归档，无物理删除）。
- 测试：`UNKNOWN` / `SUCCESS` / `FAILED`。
- `ACTIVE` 只表示启用，不能解释为“测试通过”或“可用”。
- 草稿测试的校验失败或连通失败返回 `success=false`；资源不存在/已归档继续 4xx。
- Embedding 测试要求至少一个非空向量且 `dimension > 0`；Rerank 要求至少一个合法排序结果。

## 第二阶段前端事实

管理端模型中心已原子升级到 V2 契约，不再保留 V1 字段或双轨页面。

### 路由与 API

- 主页：`/model/instances`
- 详情：`/model/instances/:id`
- 前端 API：`/model/templates`、`/model/instances`、`from-template`、`test-draft`、`test`、`archive`
- 模型流唯一入口：`POST /model/chat/stream/events`（结构化 `ModelStreamEvent`）。旧 `POST /model/chat/stream` 文本流已删除，不提供兼容。
- Vite 代理覆盖：`templates|instances|chat`

### 模型调试台

- 流式模式只调用 `/model/chat/stream/events`。
- 分别展示 content、可折叠思考过程（reasoning）、tool call 聚合、usage、completed/error。
- 支持停止生成（AbortController）；用户主动停止不显示 `MODEL_STREAM_INTERRUPTED`。
- 非流式仍走 `POST /model/chat`，消息结构与流式对齐。

### Dashboard 健康

- Dashboard 只请求 Control：`GET /api/internal-services/health`。
- Control 聚合 `runtime` / `capability` / `model` / `knowledge`；单服务失败降级为 DOWN，不拖垮整个聚合接口。
- 禁止前端直连 `/model/providers` 或 `/ai/actuator/health` 做假绿探针。

### 主页

- 第一屏**只展示已接入实例卡片**，不展示 31 个模板目录。
- 无表格模式、无左侧供应商栏、无物理删除。
- MetricStrip：已接入 / 启用中 / 已停用 / 测试异常。
- 顶部筛选 + 模型类型 tabs；默认隐藏 `ARCHIVED`。
- 运行状态与测试状态分开展示。

### 接入模型

- 单个 `AppDialog` 内两步：选择模型 → 配置并测试。
- 模板目录与“自定义 OpenAI 兼容模型”只出现在接入弹窗 / 更换上游流程。
- 创建前走 `test-draft`；失败可“仍然保存”，且不会重复测试。
- 从模板创建调用 `POST /model/instances/from-template/{templateId}`；自定义调用 `POST /model/instances`。
- 实例不持久化 `templateId`。

### 详情页

- 默认只读；“编辑配置”后进入编辑态，复用 `ModelInstanceForm`。
- 支持测试、启停、更换上游模型、归档。
- 归档入口只在详情页危险操作区；归档后不可编辑、测试、启用或恢复。
- 未保存修改有路由离开与 `beforeunload` 提示。

### 同类型更换上游模型

- 通过模板选择器（锁定当前 `modelType`）或自定义同类型上游更新编辑草稿。
- 不改变实例 ID；不保存 `templateId`。
- Embedding 更换展示不可忽略风险提示，但不阻断。
- 后端已移除 EMBEDDING `provider`/`modelName` 特殊不可变限制；仍保留 `modelType` / `protocol` 不可变。

### 本地供应商图标

- `ModelProviderIcon` + `src/assets/model-providers/*.svg`
- 优先 `iconKey`，否则按 `provider` 映射；未知供应商字母 fallback
- 禁止 Google favicon / 外网图标

### 明确不属于本任务

- Model Playground 结构化流 / 旧流逻辑
- Dashboard 健康检查
- `project/workspace` 真正隔离、引用索引、主备熔断预算限流

## 明确不做

- 完整私网 SSRF 阻断、真正项目隔离、不可变版本、引用索引
- paramsSchema 完整运行时校验
- 归档恢复 / 物理删除

## 表所有权

| 表 | Owner |
| --- | --- |
| `model_template` | `reachai-model-service` |
| `model_instance` | `reachai-model-service` |

## 升级脚本行为

脚本：`sql/upgrade-20260717-agent-supervisor-runops-model-center-v2.sql`（破坏性；先备份）。该文件是 Agent Supervisor、RunOps 与 Model Center V2 的统一已有库升级入口。

识别并处理五类 `model_instance` 状态：

1. 无表 → 创建最终 V2 结构
2. 旧 V1（有 `endpoint_type`）→ 冲突预检后原子迁移；过程成功后删除临时旧表，不保留表级备份
3. 不完整 V2（有 `connection_config_json`、无 `endpoint_type`，但 19 列终态 / 生成列 / 唯一索引任一不完整）→ 冲突预检后补齐可填充列（含 `remark`）、按安全顺序修复生成列与唯一索引；修复后仍非最终 V2 则 `SIGNAL`
4. 最终 V2 → 跳过实例迁移/修复，仅模板 upsert
5. 未知结构（非 V1、无 `connection_config_json`、非最终 V2）→ `SIGNAL`，不静默修复

统一脚本成功结束时还会清理旧版脚本可能遗留的 `model_instance_v1_bak_20260716`；如需回滚，使用执行前要求创建的整库备份。

最终 `model_instance` 共 **19** 个业务列：

- 核心列 7（不可猜测补齐）：`id` / `name` / `provider` / `model_type` / `model_name` / `created_at` / `updated_at`
- 非核心必需列 12（可补齐，含 `remark`）：`protocol` / `project_code` / `project_scope_key` / `connection_config_json` / `default_options_json` / `params_schema_json` / `status` / `last_test_status` / `last_test_at` / `last_test_latency_ms` / `last_test_error` / `remark`

最终 V2 判定同时要求：核心列 = 7、非核心必需列 ≥ 12、正确 STORED generated `project_scope_key`、正确唯一索引。核心列缺失时不进入 final；进入 incomplete 后在任何 DDL 前 `SIGNAL`。

生成列识别：使用 `EXTRA LIKE '%STORED GENERATED%'` 与规范化 `GENERATION_EXPRESSION`（去反引号/空格/`_utf8mb4`/引号转义），语义确认为 `IFNULL(project_code,'')`。不用 `IS_GENERATED`。

唯一索引判定：`uk_model_instance_name_scope` 必须 `NON_UNIQUE=0`，且 `SEQ_IN_INDEX=1` 为 `name`、`SEQ_IN_INDEX=2` 为 `project_scope_key`，恰好两列。

上一版常见路径（普通 `project_scope_key` + 形状正确的唯一索引）：先 `DROP INDEX`（即便当前索引 valid），再 `DROP COLUMN` + `ADD` 正确生成列，最后重建唯一索引。禁止在同名索引仍引用该列时 `DROP COLUMN`。

平台模板：对固定 31 个 `tpl-*` id 使用单条 `INSERT ... ON DUPLICATE KEY UPDATE`（`VALUES(column)` 兼容 MySQL 5.7）；刷新目录字段；保留已有 `enabled` / `created_at`。用户自建模板不受影响。

## 实例测试状态失效

更新实例时，若有效运行配置变化（`provider` / `modelName` / `connectionConfig` 规范字段 / `defaultOptions`），必须把 `lastTestStatus` 重置为 `UNKNOWN`，并清空 `lastTestAt` / `lastTestLatencyMs` / `lastTestError`。仅改 `name` / `remark` / `projectCode` / `ACTIVE|DISABLED` / `paramsSchema`，或前端回传相同掩码凭证与相同 URL/参数时，不重置。
