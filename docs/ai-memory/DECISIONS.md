# ReachAI Decisions

本文只记录会影响后续代码判断的长期决策。当前代码、SQL、接口和启动配置永远优先于本文。

## 五服务物理拓扑

后端主路径已经进入物理服务拆分后的旧结构退场阶段：

- `reachai-control-service`: Platform Control / public API BFF，保持 `/api/**`、`/embed/**` 和 SDK 注册兼容入口。
- `reachai-runtime-service`: Runtime Host，承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service`: Capability Catalog，承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service`: Knowledge / Retrieval，承接知识库、文件、chunk、RAG、业务索引、向量检索和历史扫描器实现。
- `reachai-model-service`: Model Gateway，承接模型中心 V2（`model_template` + `model_instance`）、Chat、Embedding、Rerank；不再保留未使用的 `/model/openai-proxy` 入口。

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已从仓库主路径删除，不再作为 Maven、IDEA、本地启动或部署单元存在。

## Public API 边界

`reachai-control-service` 是第一阶段唯一公共 API/BFF 入口：

- 前端 `/api/**` 指向 Control。
- 前端 `/ai/**` 指向 Knowledge。
- 前端 `/model/**` 指向 Model。
- Runtime 和 Capability 内部端口不直接暴露给前端。
- 旧 agent generic fallback、legacy catch-all 和占位响应壳都不再作为当前策略。

剩余 public route 必须由 owning service 本地实现，或正式删除并同步前端/文档契约。

## AI Coding Task Kernel

- 项目接入工作台与业务页面工作台共用 Control 内唯一的 AI Coding Task Kernel；领域 Provider 只拥有 Context、Artifact `content` Schema、校验和应用规则。
- 正式客户端枚举为 `CODEX`、`CURSOR`、`TRAE`、`CLAUDE_CODE`，任务创建、激活和 Artifact reporter 必须一致；领域页面不得自行维护客户端别名。
- 聊天框只承载不超过 5000 字符的紧凑激活 bootstrap；完整 PowerShell 恢复能力由一次性激活响应的 `clientSetup` 以 Base64 UTF-8 + SHA-256 下发，任务规则仍以 `/context` 为唯一事实源。
- Windows 交接使用当前用户 DPAPI 加密任务缓存和不含秘密的任务级恢复脚本跨进程恢复，不安装 Runner 或常驻程序。所有 JSON helper 固定发送 UTF-8 字节。
- Event API 不接受 `COMPLETED`。客户端自然语言和普通事件没有完成权限；Artifact 成功校验并应用后先进入 `RESULT_APPLIED`。Task Kind 如声明验收 readiness 门禁，只有服务端计算的目标项全部 `PASS` 才能进入 `ACCEPTANCE_READY`；前端将其表述为“已回传，待验证”。
- 项目接入的验收门禁固定为 `CODE_READY`、`RUNTIME_READY`、`SDK_CALLBACK_READY`、`E2E_READY`。`CODE_READY` 必须包含 Capability owning service 已观测到的业务实例注册事实，仅有代码、配置和客户端报告时保持 `PENDING`；Runtime 依据 owning service 的实例心跳；SDK Callback 依据当前项目配置之后真实收到的签名能力快照；E2E 依据当前任务启动后 Control 记录的 Embed SDK Session、用户消息和助手回复，不信任客户端报告中的 `performed/passed` 布尔值。领域步骤在 UI 中只能表述为“AI 已报告”，不得表述成平台完成。
- Capability owning service 的 `/api/scan-projects/{projectId}/sdk-access-check` 只负责平台 SDK 自检，返回 `CODE_READY / RUNTIME_READY / SDK_CALLBACK_READY`；`SDK_CALLBACK_READY` 表示签名回调和能力快照闭环。任务级 `E2E_READY` 仍由 Control 独立计算真实浏览器 Embed 会话，两个状态不得共用名称、相互代替或自动折算。
- 项目接入、代码实施和浏览器验收共用 `delivery-evidence-v1` 的测试与浏览器材料结构。浏览器验证可选的领域在未运行时必须报告 `null`；代码实施和浏览器验收的最终交付必须包含真实业务 URL、场景、截图路径和观察结果。材料只供复核，不替代平台 readiness；公共任务详情必须真正展示材料，不能只显示 Artifact 状态。
- 业务页面工作台对最终交付执行确定性一致性门槛：代码实施缺少改动文件、全部通过的测试或通过的浏览器材料时 Artifact `REJECTED` 且任务回到 `RUNNING`；浏览器验收或发布前检查报告失败时保留 `APPLIED` 材料并由 Task Kernel 进入 `FAILED`；只有完整且报告通过的材料可进入 `ACCEPTANCE_READY`，仍需用户确认。
- 业务页面工作台的发布前检查以最新 `APPLIED` Artifact 的 `applicationResult.preRelease` 作为精确目标，不从 Event 或任意历史 Workflow 猜测对象。Runtime owning service 独立验证项目、`PAGE_ASSISTANT` 类型、唯一 `TARGET PAGE`、当前发布校验和目标版本状态；`PRE_RELEASE_READY` 只有服务端确定返回 `PASS` 才放行，Runtime 不可用或响应不确定时保持 `PENDING`。该门禁不自动发布 Workflow。
- 业务页面工作台的浏览器验收在客户端报告通过后仍先停留于 `RESULT_APPLIED`。`PAGE_BROWSER_E2E_READY` 只接受任务启动后、精确 `projectCode + pageKey` 的 Control 服务端观测事实：带 SDK 版本的真实 Embed Session、至少一条用户消息和一条助手回复。其他页面、旧会话、截图路径和客户端 `passed=true` 都不能替代该 readiness；缺失时保持 `PENDING` 并允许重新验证。
- 从“已发布”发起浏览器验收时，任务使用 `RELATED WORKFLOW` 保存精确 `workflowVersionId + workflowVersion`，并在创建期通过 Runtime 已发布查询复核，拒绝前端快照漂移。`PAGE_WORKFLOW_TRACE_READY` 只在当前任务启动后的当前页面 Embed Run 已完成、且成功 `WORKFLOW_TOOL` Span 精确命中指定 Workflow 版本时通过。Artifact 指定 `traceId` 时只能验证该 Trace；未指定时仅唯一符合条件的 Trace 可自动关联，多条候选保持 `PENDING`。该门禁不从 GraphSpec 推断 `PAGE_ACTION`，也不替代入口可见性、业务结果和人工体验验收。
- Embed SDK 由 Onboarding Skill 同包携带 tgz、Manifest 和 Node 安装脚本。脚本校验 SHA-256 后将制品复制到业务前端 `vendor/reachai/`，再以相对路径执行 npm；不得让临时 Skill 解压目录或本机绝对路径进入业务依赖声明。

## Workflow 与 Runtime

`GraphSpec` 是运行语义，归属 Workflow；`canvas_json` 只是画布布局。

- DB 字段：`runtime_workflow.graph_spec_json` 和 `runtime_workflow.canvas_json`。
- Workflow Studio 负责 AI 生成、局部编辑、调试、发布校验和运行预览。
- Agent 是稳定聚合根；可执行设置进入版本化 `runtime_agent_config_version`，允许选择的 Workflow 进入同版本的 `runtime_agent_workflow_tool`。
- AgentScope Java `2.0.0` 正式版 Supervisor 负责理解、规划、选择一个或多个 Workflow 和有限重规划；单个 Workflow 仍按其已发布 `GraphSpec` 快照执行。
- “项目接入工作台”的 Agent provisioning 只创建/复用项目 Agent 并发布 ACTIVE Supervisor 配置，不创建占位 Workflow；“业务页面工作台”以 AI Coding 任务完成分析和建设。PAGE_ASSISTANT Workflow 必须先绑定恰好一个 TARGET PAGE、发布 ACTIVE 版本，再加入 Supervisor Workflow-as-Tool 白名单并发布新版 Agent 配置。
- Agent 与 Workflow 的唯一执行关系是已发布 Agent 配置版本中的 `runtime_agent_workflow_tool` 目录。
- Agent 身份写入和 Supervisor 配置写入必须使用独立 API；`POST/PUT /api/agents` 不再隐式创建或修改配置草稿。
- 产品统一称为 Agent；Agent 是通用 Supervisor 聚合根，不再保留 `agent_kind` 接入形态字段。页面副驾驶按稳定 keySlug 创建或复用，Embed / Gateway / A2A 属于开放渠道而非 Agent 类型。
- 执行请求只接受 `agentId`，删除 `agentDefinitionId` 兼容别名。
- ACTIVE/ARCHIVED 配置不可变；历史版本只能复制为新 DRAFT 后再编辑和发布。
- 页面动作只响应用户明确的页面意图；跨路由动作保持同一 embed session，并严格执行 `NAVIGATE -> TARGET_READY -> PAGE_ACTION`。
- Supervisor 统一策略链固定为工具白名单/启停 -> project -> tenant -> Agent roles -> permissionKey roles -> risk；READ 自动、PAGE_ACTION 要求原始问题显式页面意图、WRITE 一次性精确确认、IRREVERSIBLE 默认拒绝。
- Agent Eval 必须调用 `RuntimeAgentExecutionService` 执行当前已发布配置，并断言工具、PLAN/REPLAN、策略、UI 请求、Trace 与回答；不允许再生成“runtime 未接入”的占位结果。
- Runtime 执行主线在 `reachai-runtime-service`，Supervisor 通过 `SupervisorRuntimeAdapter` 解耦，上层规划和底层 Workflow runtime 不混为一个概念。

新增 Workflow Studio 节点、AI 编辑能力或 Runtime 行为时，必须把可执行语义写入 Workflow `GraphSpec`。

### Workflow 第一阶段节点与变量契约

- 节点“可执行/可开放”必须以 `RuntimeGraphSpecExecutor.handledNodeTypes()` 为 Runtime 真相，再由 `RuntimeWorkflowNodeCapabilityRegistry` 决定 Studio/发布/AI 编排开关；禁止只靠 UI 或枚举凑数。AI Draft `systemPrompt` / `nodeTypes` 也必须同源，禁止硬编码第二份 forbidden 列表。
- 业务输出别名统一写入 `var.<alias>`；保留 `input/message/params/sys/nodeOutput/lastOutput/previousOutput`；裸别名仅作只读兼容解析，不双写两套真相。`VARIABLE_ASSIGN` GraphSpec 保存原生 JSON 类型。
- `RetryPolicy` 必须同时受 `retryable()`、失败分类与 HTTP 幂等约束；`ErrorPolicy` 只在重试耗尽后消费一次。
- Knowledge 检索只走 Knowledge owning service 的 `POST /internal/knowledge/retrieval/query`（hits only），并由 `KnowledgeRetrievalCore` 承载正式生产检索；Runtime Internal API 不得构造 `RetrievalTestRequest` 或调用 `retrievalTest` 命名入口。`searchMode`/`rerankEnabled` 为 request override，不改 KB 持久化配置；Workflow GraphSpec/AI Draft 不得写入 `directReturnEnabled`/`directReturnThreshold`。
- HTTP 凭据 canonical 类型：`BEARER` / `BASIC` / `API_KEY_HEADER` / `API_KEY_QUERY` / `CUSTOM_HEADERS`；scope fail-closed；传输层仅 2xx 成功；若 JSON 顶层存在业务 `code`，则仅字符串/数值 `200` 成功，`code != 200` 或 `success=false` 必须作为不可重试的节点失败并明确提示“查询失败”（Capability/Tool 同规则）；credentialed 跨 origin redirect 拒绝；HTTPS→HTTP 拒绝。Egress 校验与连接 pin 必须共用同一组 DNS 解析结果（每 hop 只解析一次）；普通节点不得覆盖 Host/Content-Length/Transfer-Encoding/Connection/Proxy-Authorization。
- 可信执行身份：`WorkflowExecutionIdentity` 仅由受控 factory 构造（无公共 `@JsonCreator` / 不可由外部 JSON 设置 trust 布尔）。`SupervisorRequest.identity` 显式携带身份；Supervisor 不得从 `request.input`/metadata/模型 args 构造可信用户。Control→Runtime 使用 HMAC-SHA256 内部服务认证（canonical 含 method/path/caller/source/userId/timestamp/nonce/**BODY_SHA256**；恒定时间验签；JDBC nonce 仅清理过期条目，满容 fail-closed 拒绝新 nonce，绝不为腾容量删除有效 nonce；`nonceTtlSeconds >= 2 * skewSeconds`；缺密钥 fail-closed；actuator `INTERNAL_AUTH_NOT_CONFIGURED`）。RunOps/`runtime_run.userId`/ToolCallLog 审计主体必须来自可信身份，禁止从业务 body 提升。公开 `/api/runtime/agents/execute(+stream)`：有平台 Bearer 则经签名 internal 传 `AGENT` 身份，否则 `userTrusted=false`。Embed sync/stream 必须从已验签 claims 经签名 internal 传 `EMBED_SESSION`。Debug/Composition 保持 untrusted。`projectId`/`projectCode` 冲突 fail-closed。
- Trace 持久化边界：按数据来源构造 `TraceSafeSummary` allowlist（statusCode/hitCount/attempt/latency 等）；`inputSummary` 只存 inputType/messageLength/hasMessage 与非敏感 id，永不存用户原文；禁止内容猜测与固定 marker 特判；plan/replan 只存工具名与步骤数；HTTP 非 2xx raw body 可留内存供 ErrorPolicy，不得落库。Replay 若安全策略禁止原文，必须显式 `messageOverride`，不得从脱敏摘要伪造输入。
- Retry 默认 fail-closed：仅显式 transient（HTTP 408/429/5xx、timeout/connect，或 metadata `retryableFailure=true`）可重试；`*_REQUIRED` / `*_DENIED` / `*_UNAVAILABLE` / 配置与权限错误不可重试。
- Agent/Embed 对外不推送 Workflow node delta，但对内必须持久化已脱敏的 `workflowNodeTraces`。
- `INTERACTION` 保持 BETA 关闭，直到 Agent/Embed/RunOps Live E2E 完成；当前记为 CODE_READY / E2E_PENDING。第一阶段 5 类节点整体：CODE_READY / E2E_PENDING / PRODUCTION_PENDING。

## Capability 与 Skill 命名

产品和文档默认使用 `Capability / 能力`。

`Skill` 仅用于标准 Agent Skill 包或外部协议字段。ReachAI 禁止重新引入自创的 Skill 业务资产模型；Capability 是业务资产，Tool 是调用协议，Workflow 是 GraphSpec 编排。已退役的 `capability_draft`、`runtime_skill_interaction`、`kind=SKILL` 目录和 GraphSpec `CAPABILITY` 节点不得作为现行资产模型。

`reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不再称为“技能服务”。

## 模型中心 V2（第一阶段）

模型中心只保留两个领域对象：

- `model_template`：平台维护的只读目录/创建模板；不含真实 API Key；本阶段不提供模板写接口。
- `model_instance`：对 Agent / Workflow / Knowledge 暴露的稳定可执行资源；业务继续绑定稳定 `modelInstanceId`，实例内部配置可改以支持原地替换。

关键约束：

- 模板与实例之间不建外键，实例不保存 `template_id`；从模板创建只复制快照，模板后续变化不影响已有实例。
- 协议仅 `OPENAI_COMPATIBLE`；模型类型仅 `LLM` / `EMBEDDING` / `RERANKER`。
- `project_code` 为预留字段（NULL=全局），本期只存取，不做强制项目隔离。
- 实例启停状态（ACTIVE/DISABLED/ARCHIVED）与连通测试状态（UNKNOWN/SUCCESS/FAILED）是两套语义；删除等于归档，无物理删除。
- `connection_config_json` 必须 `aesgcm:` 加密；API 仅返回掩码敏感字段；不再兼容明文读取或 `apiKeyEnv` / `BUILT_IN`。
- `baseUrl` 为 API Root，path 为相对路径；Chat/Embedding/Rerank 受保护字段不可被 defaultOptions/options 覆盖；BaseURL authority 变更必须重输凭证。
- `project_scope_key` 由数据库生成列维护；管理端旧契约仍待第二阶段原子升级。

详见 `docs/architecture/model-center-v2.md`。

## SQL 决策

`sql/initV2.sql` 是当前新库 SQL 基线入口；旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。任何 schema、索引、种子数据、字段语义相关改动，都必须同时维护：

- `sql/initV2.sql`
- `sql/upgrade-YYYYMMDD-short-name.sql`
- `sql/README.md` 或相关说明

默认不为旧数据做复杂兼容迁移。当前基线按新库重建处理，不提供旧表名兼容视图或旧数据迁移脚本；后续如需要面向存量库升级，再新增当次 upgrade SQL 并写清影响。

## ModelInstanceRuntimeCache 跨实例一致性

`reachai-model-service` 的 `ModelInstanceRuntimeCache` 是**进程内**短 TTL 缓存（配置项 `model.instance-runtime-cache.ttl-ms`，默认 5s，硬上限 30s）：

- 本机 create / update / archive 会立即 `invalidate`。
- 多实例部署下，其他副本在 TTL 窗口内仍可能使用旧的 ACTIVE runtime（含禁用、归档、凭据轮换后的短暂陈旧）。
- 当前阶段不引入分布式缓存或跨实例失效广播；缩短 TTL 是刻意的安全窗口，而不是“强一致”。
- 缓存与日志均不得输出模型凭据或解密后的连接配置。

## 品牌与技术身份

ReachAI 是产品品牌，也是新 JDK8 接入 SDK 的技术身份。新业务系统接入使用 `reachai.*` 配置、`X-ReachAI-*` header、`Reach*` 类名和 `reachai-*` Maven artifact。

历史 `eaf.*` 配置、`X-EAF-*` header、`Eaf*` 类名属于兼容敏感边界，不要为了品牌统一顺手替换。

## 文档入口

- `README.md`: 对外入口。
- `docs/README.md`: 内部知识库入口。
- `docs/01-*` 到 `docs/05-*`: 根目录只保留当前产品主线事实源。
- `docs/architecture/`: 路由、internal API、表所有权、五服务边界和旧结构退场等架构契约。
- `docs/guides/`: 面向业务系统接入方的操作指南和样例。
- `docs/reference/`: 身份授权、嵌入式对话、AI Coding、Context Governance 等长篇参考。
- `AGENTS.md`: AI 编程工具最高优先级项目规则。
- `docs/ai-memory/`: AI 工具跨会话记忆。

文档应回答“当前真实系统是什么、代码在哪里、边界是什么”。历史计划、阶段稿和讨论稿不能覆盖当前事实。
