# API 市场产品与架构契约

> 状态：IMPLEMENTATION_IN_PROGRESS / LIVE_E2E_PENDING（2026-08-24）
> 事实边界：当前共享工作树已包含目录、项目接入、SQL 与管理端实现；仍须以稳定快照测试、目标库升级、运行进程和真实 HTTP/浏览器验收分别确认

2026-10-02 专项更新：按[业务方法/API 规格](../plans/BMAPI-3-API接入与使用规格.md)和[3D 任务](../../output/tasks/business-method-api/BMAPI-3D-API市场受控绑定代表流程任务.md)，明确无认证、只读 GET 的固定市场选择已接入同一项目 API owner、Runtime 当前 Console 验证与稳定 API Workflow 引用，并通过[范围限定的主任务复核](../../output/tasks/business-method-api/BMAPI-3D-主任务复核.md)。旧 READY/raw marketRef 设计不再作为运行契约。该代表流程已在隔离 HTTP/H2 与正常浏览器完成接入、实际 Console、Studio 保存/试运行/显式发布、实际已发布 MCP 调用及来源下线零追加派发，影响面回归通过，原始证据见[实施结果](../../output/tasks/business-method-api/BMAPI-3D-实施结果.md)。这不是整个市场、全部认证/schema、真实 MySQL、独立部署、公网业务或真实用户验收，整体 LIVE_E2E_PENDING 保留；本轮未开启下一批。

## 1. 产品定位

API 市场是 ReachAI 面向开发者的“外部 API 发现与接入工作台”。它解决的不是“列出很多链接”，而是把公开 API 从发现信息转换成可治理、可配置、可进入 Workflow Studio 的项目资产。

核心结果链路：

> 找到可信 API → 核对来源版本与 Operation → 选择项目/环境并固定来源 → 同一 API 显式接纳 → Runtime 连接/目标 ACL/真实 Console 验证 → Studio API 映射与只读试运行 → 显式发布和运行。

它与现有模块的边界如下：

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| API 市场 | 外部 API 目录、来源、版本、Operation、质量证据、项目接入意图 | 保存秘密、代替 Workflow 执行、把外部 API 假装成内部 SDK Capability |
| Capability Catalog | 业务方法、项目 HTTP API 的来源/契约/接纳；市场目录和项目接入选择仍由现有领域管理 | 将市场收录或项目意图当作已验证调用授权，另建人工 Tool 资产 |
| Workflow Studio | 可执行 GraphSpec、调试、发布、凭据引用 | 维护全局公开 API 来源目录 |
| Runtime | 受控 API 连接/凭据、实际 Console 验证、Workflow pin/执行、Trace、Guard | 管理目录来源、把 READY 或 raw marketRef 当作授权 |
| Agent / Workflow 市场 | ReachAI 自身可分发资产 | 外部第三方 API 目录 |
| 标准 Agent Skill 中心 | 标准 Skill 包审核、版本和 Agent 绑定 | 外部 API 发现或凭据管理 |

因此，API 市场是独立一级菜单 `/api-market`，公共 API 固定为 `/api/api-market/**`；不要复用 `/api/market/**` 或 Skill 业务模型。

## 2. 用户与任务

### 2.1 业务开发者

目标是快速判断“这个 API 能不能解决我的问题”，并在不手抄 URL、参数和认证规则的情况下形成可调试 Workflow。

关键问题：

- 是否真的可用，最近何时验证？
- 是否免费、需要什么认证、有没有副作用？
- 哪些参数必填，响应大致是什么？
- 来源是官方文档、机器目录还是社区清单？
- 接入后还缺什么配置？

### 2.2 平台开发者

目标是把公开 API 作为受治理的构建材料使用，同时保持 GraphSpec、凭据、运行审计和服务所有权清晰。

关键问题：

- 目录版本和 Operation 是否稳定可追溯？
- 草稿是否使用同一 owner API 稳定引用和既有输入/输出映射？
- API 更新后哪些项目或 Workflow 可能受影响？
- 目录元数据是否含有秘密或不可控的响应正文？

### 2.3 平台维护者 / 审核者

目标是管理来源、差异、验证和发布，而不是人工维护一份没有证据的链接表。

关键问题：

- 哪个来源发现了什么，原始 revision 是什么？
- 自动导入与人工审核之间的边界在哪里？
- 文档、OpenAPI 和连通性证据是否一致？
- 条款、认证或契约变化是否需要下架或告警？

## 3. 信息架构

API 市场页面包含四个工作区：

1. **发现 API**：搜索、分类、认证、验证状态、来源和 OpenAPI 可用性筛选；卡片优先展示用途、认证、质量、来源和更新时间。
2. **我的接入**：按授权项目管理固定版本和 Operation；直接进入同一 API 补齐条件，当前真实验证成立后创建 Studio 草稿。
3. **变更与风险**：集中展示 `STALE`、`FAILED`、`CHANGED`、废弃版本和未来的受影响 Workflow。
4. **来源与质量**：展示来源类型、信任等级、同步策略、最近同步时间和收录数量。

详情抽屉分三层：

- **先决策**：用途、认证、价格、验证状态、来源、官方文档和条款。
- **再理解**：版本、Base URL、Operation、方法、路径、参数 schema、副作用。
- **后治理**：来源键、契约状态、最近验证时间、明确风险和配置边界。

首屏不能用大面积装饰挤压检索结果。视觉身份只作为背景氛围；搜索、筛选、可信度和“接入项目”是最高层级。

## 4. 核心用户旅程

### 4.1 发现与评估

1. 用户以自然语言或 API/供应商名称搜索。
2. 服务端只返回 `PUBLISHED` 条目，并组合 Provider、Source、版本和 Operation 摘要。
3. 用户通过认证、成本、验证状态和来源缩小范围。
4. 用户打开详情，对照官方文档、条款、验证时间和 Operation 做决策。

目录收录不等于可用。`UNVERIFIED`、`STALE`、`FAILED` 必须显式展示，不允许用“热门”掩盖质量状态。

### 4.2 项目接入

1. 用户选择项目、环境、目录版本和一个或多个 Operation。
2. Control 按服务端解析的项目授权，Capability 校验项目/环境、entry/version/Operation 归属、发布状态及可执行来源事实。
3. 接入只固定来源选择，技术 `READY` 在用户侧显示“来源已选择”，不表示配置、验证或调用授权。当前仅支持明确 `NONE`、只读 GET、标量 path/query 和 JSON 对象；认证/方法/schema/响应媒体或状态不受支持时明确拒绝，不降级或猜测。
4. 正常接入创建或更新 `API_MARKET_OPERATION` 来源/候选及 Operation→API pointer；不自动接纳、配置连接、授予 ACL 或产生成功 proof。请求仅接受目录选择和备注，不接收 owner/hash/ref/origin/凭据/READY/proof。

同一项目、API 和环境只有一个接入记录。再次接入采用幂等更新，而不是制造重复资产。

外部逻辑服务使用可信 `api-market:<entryKey>` 稳定范围，隔离不同市场条目和内部同 method/path；版本、URL、凭据和数据库 ID 不进入 API identity。内部旧 scope 为空时新增字段不序列化，保留三个扫描来源的 identity/hash/ref 字节。相同选择重复请求幂等；新目录版本不自动改已有选择。显式改选或从停用/BROKEN 恢复增加 `selection_revision`，进入来源新鲜度但不进入 API 身份，v1→v2→v1 不能复活旧 proof/pin。

每次 owner 查询重读固定目录与绑定事实。发现下线、Operation 移除或事实漂移时，由 Capability 在同一选择计数上锁存 `BROKEN`；目录重新出现不自动恢复，用户必须明确重新选择。已有 API/草稿/版本和引用保留，当前使用在派发前阻断。

### 4.3 生成 Workflow

当前市场作者入口使用已接纳且当前真实验证成立的同一 API，不再由目录 URL 生成 raw HTTP_REQUEST。

1. 前端使用服务端 owner 返回的 API 稳定引用，复用既有 API 技术节点与 Workflow 输入/输出映射。
2. 输入/返回声明来自已接纳的固定 Operation 来源，不由浏览器改写目录契约。
3. GraphSpec 保存稳定 API ref、项目范围和受约束映射，不复制 origin、明文凭据或自算 hash。
4. 发布/执行从 owning service 重新验证项目、目录版本/Operation、来源/契约、连接和当前 Runtime 验证记录；raw marketRef 不能代替这些事实。
5. 前端调用现有 Workflow API 创建草稿，并进入 Workflow Studio。
6. 当前认证 Operation 在接入前明确阻断；未来支持认证仍须走 Runtime-owned 凭据契约，市场不接触明文。

代表草稿为 `API → 变量 → END`（START/END 是现有画布边界），API 节点沿用 TOOL 技术投影和 owner qualifiedName，输入映射来自已接纳声明，变量引用已声明输出。旧 raw HTTP_REQUEST 的顶层或嵌套 `marketRef`（即使 null）可保存 WIP，但最终发布/执行返回迁往 API 的明确原因；普通无市场引用 HTTP_REQUEST 保持原行为。

项目接入创建成功但 Workflow 创建失败时，保留接入记录并提示重试。这里不存在跨 Capability 与 Runtime 数据库的伪分布式事务。

### 4.4 调试、发布和运行

- Workflow Studio 负责映射、发布校验与版本发布；业务方法/API 草稿真实调试采用已确认的项目测试身份、权限和审计，限定只读。
- Runtime 在执行时解析凭据引用并注入 HTTP 请求；Trace 记录脱敏的调用事实。
- Guard、域名白名单、超时、重试、响应大小和副作用策略继续使用 Runtime 治理边界。
- 生产环境必须通过 `RUNTIME_HTTP_EGRESS_HOST_ALLOWLIST` 显式授权第三方主机；目录收录本身不自动放开网络出口。
- API 市场只提供来源和契约上下文，不能绕过 Runtime 直接从浏览器调用第三方 API。

市场专属发布/草稿执行条件要求 Runtime 自有最新 Console ledger 与 Run snapshot 对应当前 API/accepted hash/sourceSetRevision/连接及凭据修订。仅实际 `SUCCEEDED + CONFIRMED + 2xx + COMPLETED Run` 确认为 `VERIFIED`；失败、未知、事实不完整或旧修订不能回退早期成功。proof 仅返回安全标识/修订及 Run/Trace，不复制结果正文或秘密。不改变内部扫描 GET 的已确认发布条件，不将项目 proof 写成全局目录质量成功。

## 5. 领域模型

```text
ExternalApiSource 1 ── * ExternalApiEntry * ── 1 ExternalApiProvider
                              │
                              └── * ExternalApiVersion 1 ── * ExternalApiOperation
                                      │
                                      └── * ExternalApiVerification

ScanProject 1 ── * ProjectExternalApi * ── 1 ExternalApiVersion
                         │
                         └── * ProjectExternalApiOperation * ── 1 ExternalApiOperation
```

### 5.1 聚合与身份

- `entry_key`：跨来源稳定产品身份，例如 `github-rest`。
- `version_key`：目录版本身份。已发布版本视为不可变；契约变化创建新版本。
- `operation_key`：版本内稳定操作身份；优先使用规范化 `operationId`，缺失时按 method/path 生成。
- `source_entry_key`：原始来源中的身份，只用于追溯，不充当平台主键。
- `spec_hash`：规范化 OpenAPI/契约摘要，用于变化检测；不是秘密。

### 5.2 状态机

目录发布状态：

```text
DRAFT → IN_REVIEW → PUBLISHED → RETIRED
          └────────→ REJECTED
```

质量状态：

```text
UNVERIFIED → VERIFIED → STALE
     └───────────────→ FAILED
```

契约状态：

```text
NONE → VALID → CHANGED
         └──→ INVALID
```

项目接入状态：

```text
CONFIGURING / READY → DISABLED / BROKEN
       ↑                  │
       └── 显式重新选择 ──┘
```

发现只暴露 `PUBLISHED`；项目 READY 是技术来源选择状态。直接 PUT READY/CONFIGURING 不能恢复选择或伪造验证，恢复须正常显式选择。审核工作流、自动同步和全局依赖影响投影仍为后续范围。

## 6. 服务与代码边界

### 6.1 Capability Service

拥有全部 `capability_external_api_*` 和 `capability_project_external_api*` 表，负责：

- 目录搜索、详情、分类、统计、来源视图；
- 版本和 Operation 归属校验；
- 项目接入的幂等创建、列表和状态修改；
- 将固定 Operation 适配为同一项目 HTTP API 来源/候选并维护接纳、来源确认；全局质量证据与项目 Runtime 验证分离；来源自动导入/审核仍待后续。

代码包固定在 `com.enterprise.ai.capability.externalapi`，不与内部 SDK 扫描实体或旧 Market 实体混用。

### 6.2 Control Service

- 保持唯一浏览器入口 `/api/api-market/**`；
- 执行平台会话，发现按平台读取授权，项目选择/列表/状态按服务端 owning project 授权；项目列表必须明确 projectId；
- 通过 `CapabilityReviewGateway` 以 Control V1 exact-body HMAC 和可信平台 actor 转发至 `/internal/capability/api-market/**`；Capability public alias 也受签名保护，不走未签名 compatibility proxy；
- 不直接访问 Capability-owned 表，不复制目录业务逻辑。

### 6.3 Runtime Service

- 继续拥有 API 连接/凭据、Console ledger/RunTrace、Workflow、GraphSpec、pin 与执行；
- 不直接访问 API 市场表；
- 当前验证由 `RuntimeHttpApiVerificationService` 派生；发布固定既有 API contract/source/connection/credential pin，sourceSetRevision 已包含选定目录版本/Operation 及选择计数，不新增 raw marketRef 证明；
- 后续依赖影响分析应由 Runtime 发布事件或显式 internal API 投影，不允许 Capability 直接查 Runtime 表。

### 6.4 Frontend

- 只调用 Control `/api/**`；
- 目录 DTO 独立放在 `types/apiMarket.ts`；
- GraphSpec 投影为纯函数 `utils/apiMarketWorkflow.ts`，可单测且不依赖 Vue 组件；
- 页面组件只处理交互状态，不复制后端归属校验。

## 7. 公共 API

| 方法与路径 | 用途 | 关键约束 |
| --- | --- | --- |
| `GET /api/api-market/entries` | 分页发现 | 仅 `PUBLISHED`；服务端筛选与分页 |
| `GET /api/api-market/entries/{entryKey}` | 完整详情 | 返回版本与 Operation，不返回凭据 |
| `GET /api/api-market/stats` | 首页统计 | 发布、验证、OpenAPI 和接入数量 |
| `GET /api/api-market/categories` | 分类导航 | 基于已发布条目聚合 |
| `GET /api/api-market/sources` | 来源与质量 | 来源类型、信任、同步策略、条目数 |
| `GET /api/api-market/integrations` | 项目接入列表 | 必须明确 projectId，服务端 owning project 读取授权 |
| `GET /api/api-market/integrations/{id}` | 固定选择与 API pointer | 服务端从记录解析项目再授权，不信任浏览器 integrationId 作为授权 |
| `POST /api/api-market/entries/{entryKey}/integrations` | 创建或更新接入 | 项目、版本和 Operation 必须相互归属；请求体禁止秘密 |
| `PUT /api/api-market/integrations/{id}/status` | 停用/标记异常 | 不接受 READY/CONFIGURING/proof，恢复须显式选择 |

错误使用稳定业务码，如 `API_MARKET_ENTRY_NOT_FOUND`、`API_MARKET_VERSION_MISMATCH`、`API_MARKET_OPERATION_MISMATCH`，前端不能依赖数据库异常文本。

## 8. 来源、导入与发布

来源优先级：

1. 供应商官方文档或官方 OpenAPI；
2. APIs.guru 等机器可读 OpenAPI 目录；
3. public-apis 等社区发现清单；
4. 人工登记。

社区清单适合“发现”，不自动获得 `VERIFIED` 或 `PUBLISHED`。目标同步流水线为：

```text
fetch source revision
  → normalize provider/entry/version/operation
  → validate URLs and schema
  → diff against current published version
  → write DRAFT + sync run
  → human review
  → publish immutable version
  → emit change event / impact projection
```

导入器必须满足：

- 可重放：相同来源 revision 和规范化内容不会产生重复版本；
- 可追溯：保存来源 URL、revision、hash 和摘要，不保存大响应正文；
- 可隔离：单个条目失败不使整个批次伪成功；
- 可审核：自动任务不得直接覆盖已发布版本；
- 可撤回：下架目录条目不删除既有 Workflow 版本，但会进入风险视图。

## 9. 安全与合规

- API 市场所有管理端路由需要平台登录；Control 是唯一公共入口。
- 目录、验证和项目接入请求/表中禁止出现 `secret`、`token`、`apiKey`、`password` 等秘密值。
- 凭据只通过 Runtime credential API 创建和使用；返回值必须掩码。
- 验证任务只执行维护者允许的只读 Operation，禁止自动执行有副作用接口。
- 验证证据只保存 URL、状态码、时延、时间和短摘要，不保存第三方响应正文或个人数据。
- URL 导入与验证必须防 SSRF：只允许 HTTPS、解析后阻断环回/链路本地/内网/云元数据地址，并限制重定向、响应大小和超时。
- Runtime 每个请求跳只使用 Egress Policy 已批准的解析地址；HTTP transport 通过请求级 pinned DNS resolver 连接该地址，同时保留原域名的 `Host`、TLS SNI 和证书主机名校验。禁止通过信任所有证书、关闭 hostname verification 或重新自由解析域名来绕过失败。
- `terms_url`、价格、限流、数据区域和许可证是决策信息；无法确认时显示“未知”，不能推断。
- 生成 Workflow 时默认只选用户确认的 Operation，不自动扩大权限或加入写操作。

## 10. 变化与影响治理

风险页最终应回答“变化影响谁”，而不只是列出异常条目。

推荐事件契约：

```json
{
  "eventType": "EXTERNAL_API_VERSION_PUBLISHED",
  "entryKey": "github-rest",
  "oldVersionKey": "2022-11-28",
  "newVersionKey": "next",
  "changedOperationKeys": ["get-repository"],
  "breaking": true,
  "occurredAt": "..."
}
```

当前同一 API 复用 Runtime-owned 草稿/版本引用与发布 pin，Control 按既有 internal API 聚合，未知查询明确 UNKNOWN 而非“无引用”。目录级风险仍展示 `STALE / FAILED / CHANGED`；全局事件/影响页面扩建没有因此完成，也不从 raw marketRef 建立可信依赖。

## 11. 指标与验收

### 11.1 产品指标

- 搜索后进入详情率；
- 详情到项目接入转化率；
- 接入到 Workflow 草稿创建成功率；
- 从发现到首次调试成功的中位时长；
- `VERIFIED` 条目覆盖率和验证新鲜度；
- 因契约、认证或限流导致的运行失败率；
- 目录变化通知到受影响 Workflow 处置完成时长。

### 11.2 首期验收

- 菜单可进入 API 市场，发现、我的接入、风险、来源四个工作区可用；
- 条目可按关键词、分类、认证、验证状态、来源和契约可用性筛选；
- 详情可看到来源、文档、条款、认证、版本、Operation 和 schema；
- 接入请求服务端校验项目/版本/Operation 归属且不接受秘密；
- 明确只读无认证 Operation 正常接入同一 API、接纳/连接/ACL/实际 Console 后生成稳定 `API → 变量 → END` 草稿，保存、只读试运行、显式发布和实际已发布调用；
- 当前不支持认证/schema/方法明确拒绝，不猜测或降为 NONE；旧 raw marketRef 最终发布/执行拒绝，普通 raw HTTP 保留；
- Control 鉴权与 BFF、Capability 表所有权、Runtime 执行边界均有自动测试或静态检查；
- 新库基线和已有库升级脚本包含相同 API 市场表与种子；
- 至少一个无认证只读项目 API 完成真实 HTTP/RunTrace 验证；目录全局质量不因此变为 VERIFIED，项目当前 proof 与配置分开；
- 浏览器验收覆盖页面首屏、详情、接入对话框和 Workflow 跳转；若环境未应用 SQL 或服务未启动，必须标记为 `PENDING`，不能以构建通过代替。

## 12. 演进顺序

1. **首期闭环**：可信目录、发现详情、项目接入、Workflow 草稿、来源/风险视图、首批人工种子。
2. **来源运营**：OpenAPI/社区适配器、sync run、差异审查、人工发布、定时只读验证。
3. **运行闭环**：Runtime dependency projection、契约变化影响、验证新鲜度告警、RunOps 反哺质量。
4. **企业治理**：租户可见性、许可证与数据区域策略、审批、配额/成本、私有 API 源。
5. **智能发现**：语义搜索、按业务意图推荐、根据现有 Workflow 上下文推荐 Operation；推荐必须解释来源和风险，不替用户自动授权。

任何阶段都保持三条不变：Catalog 在 Capability、执行和秘密在 Runtime、浏览器只进 Control。
