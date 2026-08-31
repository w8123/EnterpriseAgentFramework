# 模型中心 V2

> Date: 2026-08-25
> Scope: 模型实例稳定契约 + 官方来源每日目录同步 + 前端目录新鲜度
> Owner service: `reachai-model-service`
> Frontend: `ai-admin-front` 模型中心页面

## 领域对象

模型中心对业务仍只暴露两个核心对象：`model_template` 与 `model_instance`。官方来源、同步设置、每日运行、
快照和候选变更是 Model Service 自有的目录治理事实，不成为 Agent / Workflow 的新业务资产。

### `model_template`

平台提供的模型发布目录和创建模板。模板不含真实 API Key；SQL 种子仅作为 last-known-good
启动目录，运行期由官方来源同步安全更新。对外仍只读：

- `GET /model/templates`
- `GET /model/templates/{id}`

`disabled` 模板禁止创建实例。

目录同步增加的生命周期、来源、最后核验时间与推荐治理字段会随模板返回。`sync_managed=1`
表示该模板由官方目录同步首次创建；手工/种子模板不会因此丢失其连接配置和运维备注。

`params_schema_json` 本阶段种子统一为 `[]`。原因：尚无统一的参数 Schema 契约与前端渲染协议；完整模板驱动参数表单留到后续阶段。当前可调参数通过 `default_options_json` 保守提供（如 `temperature` / `max_tokens`），不会把仅用于展示的元数据（如 embedding 维度）放入会真实发送给供应商的 defaultOptions。

### `model_instance`

对 Agent / Workflow / Knowledge 暴露的稳定可执行资源。业务继续绑定稳定 `modelInstanceId`；实例不保存 `template_id`。

## 官方来源每日同步

### 调度与幂等

- `reachai-model-service` 使用本服务 `@Scheduled` 轮询内部维护任务，不占用 Runtime Automation。
- 自动同步开关持久化在单例表 `model_catalog_setting`，初始值为关闭；环境变量
  `MODEL_CATALOG_SYNC_ENABLED` 只在单例记录缺失时作为兜底，不能覆盖已经保存的设置。
- 服务启动 10 分钟后首次检查，之后每 10 分钟轮询；业务日期默认使用 `Asia/Shanghai`。开关开启时
  才创建当天自动槽位，关闭时仍会处理已经持久化的手动槽位和失败重试。
- `model_catalog_sync_run` 对 `source_id + business_date` 建唯一键。只有 `SUCCESS` / `NO_CHANGE`
  表示该来源今天已完成，之后不会重复抓取；失败按同一槽位重试。
- `lease_owner + lease_token + leased_until` 保证多副本中只有一个实例处理同一槽位；过期租约可恢复。
- 开关开启后，每天首次轮询自动创建当日槽位，因此跨午夜无需重启。启动时若今天尚未完成，首次自动轮询会补偿。
- 当天已失败耗尽的槽位会在新进程首次轮询时重置重试预算；已经 `SUCCESS` / `NO_CHANGE` 的槽位不动。
- 手动同步为今天补齐来源槽位，并只重排 `PENDING` / `RETRY` / `DEAD`；今天已经
  `SUCCESS` / `NO_CHANGE` 的来源不会重复执行。请求返回后由后台 worker 执行，不阻塞 HTTP 请求。

### 设置与手动入口

- 前端模型中心顶部“目录设置”打开设置弹窗，展示持久化开关、今日进度和分析器配置状态。
- `GET /model/catalog/status`：读取当前设置、今日完成数、目录新鲜度与各来源状态。
- `PUT /model/catalog/settings`：保存 `autoSyncEnabled`。
- `POST /model/catalog/sync`：提交当天尚未完成来源的手动同步；自动同步关闭时同样可用。
- 未配置 `MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID` 时，手动入口失败关闭，不会暗选业务模型。

### 来源与抓取边界

- 默认来源只指向 OpenAI、阿里云百炼、Anthropic、Google Gemini、DeepSeek 官方页面。
- 来源 URL 必须同时通过数据库 `allowed_host` 与代码内固定域名白名单；仅允许 HTTPS 443、无
  userInfo、无 fragment、无重定向，避免数据库配置扩大 SSRF 边界。
- 抓取使用 ETag / Last-Modified 与规范化内容 SHA-256；内容未变化时不调用 AI。
- 单响应有严格字节上限。HTML 仅解析为文本，`script/style/noscript/svg/template` 不执行也不入库。
- 结构化 Models API 可通过固定 `auth_type` 映射读取部署环境变量；数据库只保存枚举，不保存密钥。

### AI 分析与发布守门

目录分析模型必须通过 `MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID` 显式绑定一个 ACTIVE LLM 实例。
未配置时保存快照但明确失败，不会从业务模型中暗选一个实例。

官方页面正文始终按不可信数据处理：系统提示要求忽略正文内指令，禁止工具和链接访问，只接受
严格 JSON schema。候选必须再次通过确定性校验：精确模型 ID 必须原样出现在快照中、AI 给出的
证据摘录必须可在快照原文中定位且包含该 ID、类型和生命周期枚举合法、日期为 ISO 格式、替代
模型也必须有来源证据、来源必须为官方且置信度达到阈值。
新模板还必须有官方文本明确证明当前 Runtime 所需的 Chat Completions / Embeddings / Rerank
端点；只有“模型存在”但协议兼容性不清楚时，只落候选并进入评审，不生成貌似可用的模板。

AI 不直接写模板。完整链路为：

1. `model_catalog_snapshot` 保存不可变规范化快照、哈希和严格分析结果；
2. `model_catalog_change` 保存逐模型候选、证据、校验与发布决定；
3. 只有安全事实可更新 `model_template`；新 ACTIVE 模型使用代码审定的 OpenAI-compatible 连接默认值；
4. `DEPRECATED` 只告警，`RETIRED` 可禁用模板，但绝不修改/迁移已有 `model_instance`；
5. 生命周期只允许单调前进，不能因另一个“当前可用列表”自动把退役模型重新激活；
6. 来源缺失不能推断下线，失败时继续使用 last-known-good 发布目录。

模型“新”不等于“推荐”。同步任务只把 `recommendation_status` 设为 `UNASSESSED`；推荐等级和理由
必须来自 EvalOps 代表性数据集证据或人工评审。Embedding 模型更换仍需单独重建向量索引。

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
| `model_catalog_source` | `reachai-model-service` |
| `model_catalog_setting` | `reachai-model-service` |
| `model_catalog_sync_run` | `reachai-model-service` |
| `model_catalog_snapshot` | `reachai-model-service` |
| `model_catalog_change` | `reachai-model-service` |

## 升级脚本行为

历史脚本 `upgrade-20260717-agent-supervisor-runops-model-center-v2.sql` 已随最终结构进入 GitHub 基线并从当前工作树清理。早于 `ae9e1ce6` 的数据库应从对应 Git tag 获取当时迁移链或按当前 `initV2.sql` 重建；当前合并升级不重复承载这段历史破坏性迁移。

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

平台模板：原固定 `tpl-*` 仍作为 last-known-good 启动种子；当前 `upgrade-20260830-platform-consolidated.sql` Section 10
增加官方来源日同步表、默认关闭的持久化设置和模板治理字段。同步只做有证据的增量 upsert，保留稳定实例与用户自建模板。

## 实例测试状态失效

更新实例时，若有效运行配置变化（`provider` / `modelName` / `connectionConfig` 规范字段 / `defaultOptions`），必须把 `lastTestStatus` 重置为 `UNKNOWN`，并清空 `lastTestAt` / `lastTestLatencyMs` / `lastTestError`。仅改 `name` / `remark` / `projectCode` / `ACTIVE|DISABLED` / `paramsSchema`，或前端回传相同掩码凭证与相同 URL/参数时，不重置。
