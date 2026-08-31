# A2A 互联中心产品设计

> 状态：CORE_E2E_VERIFIED_PRODUCTION_GATES_PENDING
> 产品名：A2A 互联中心（A2A Hub）
> 适用协议基线：A2A Protocol 1.0
> 决策日期：2026-08-23

## 1. 产品决策

A2A Hub 是 ReachAI 的独立产品模块，不是“Agent 暴露开关”，也不是现有 A2A Controller、三张表和两个页面的增量升级。

本次采用 clean-slate replacement：

- 不兼容旧 `/a2a/{agentKey}/**` 路由、旧 `0.2.0` AgentCard、旧请求结构和旧调用日志语义。
- 不迁移 `control_a2a_endpoint`、旧版 `control_a2a_task`、`control_a2a_call_log` 数据。
- 不保留双写、兼容 Adapter、旧新页面并存或“先复用以后再拆”的长期过渡结构。
- 允许复用 ReachAI 已成熟的平台能力：平台会话、服务间 HMAC、Runtime 执行、Supervisor、RunOps、Trace、Guard、Tool ACL、加密工件与管理端设计系统。
- A2A 是开放渠道，不是 Agent 类型；本地 Agent 仍以 `runtime_agent` 为事实源。

## 2. 产品定义

A2A Hub 负责让企业能够安全地发布本地 Agent、发现和治理外部 Agent，并把跨 Agent 协作纳入统一任务、身份、策略与可观测体系。

它交付的不是“协议通了”，而是四个企业结果：

1. **可发布**：平台管理员可以把一个已发布的 ReachAI Agent 变成经过校验、可发现、可授权的 A2A 服务。
2. **可接入**：Agent 开发者可以通过 Agent Card URL 接入外部 Agent，验证身份、能力、协议和健康状态。
3. **可协作**：本地 Supervisor 或 Workflow 可以在明确授权范围内把任务委派给远程 Agent，并处理长任务、多轮输入、授权等待、产物和取消。
4. **可治理**：安全和运维人员可以回答“谁在调用谁、凭什么调用、处理了什么类型的数据、任务现在怎样、失败在哪一跳”。

## 3. 产品边界

### 3.1 与其他开放能力的区别

| 场景 | 产品入口 | 调用主体 | 核心资源 |
| --- | --- | --- | --- |
| 用户在业务页面与 Agent 对话 | Embed | 业务用户 | Conversation / Interaction |
| 企业应用调用 Agent | Gateway | 应用客户端 | Request / Run |
| AI 客户端调用 Tool | MCP | MCP Client | Tool / Resource |
| 一个 Agent 委派工作给另一个 Agent | A2A Hub | Remote Agent Principal | Context / Task / Message / Artifact |

A2A Hub 不取代 MCP、Gateway、Embed、Workflow Studio 或 RunOps。它把跨组织、跨平台 Agent 协作投影到这些既有执行与治理能力之上。

### 3.2 领域语言

- **本地发布（Local Publication）**：一个 ReachAI Agent 对外可见的、版本化且可回滚的 A2A 契约。
- **远程 Agent（Remote Agent）**：通过 Agent Card 发现的外部 Agent 资产，不属于 ReachAI Capability，也不是内部 Skill。
- **协议 AgentSkill**：Agent Card 中描述 Agent 擅长行为的标准字段，只用于发现和协商；不创建 ReachAI 自有 Skill 业务模型。
- **主体（Principal）**：通过凭据认证后的远程 Agent 或远程客户端身份。
- **信任策略（Trust Profile）**：认证方式、授权范围、租户、数据等级、限流和委派身份规则的组合。
- **上下文（Context）**：跨多个任务与消息的连续协作范围。
- **任务（Task）**：A2A 有状态工作单元；是协议资源，不替代 Runtime Run。
- **产物（Artifact）**：任务输出；回答、文件或结构化结果应作为 Artifact，而不是混入状态消息。

## 4. 目标用户与核心任务

### 4.1 平台管理员

目标：在不手写 Agent Card、不理解底层 transport 细节的情况下，安全发布本地 Agent。

核心任务：

- 选择一个满足发布条件的本地 Agent。
- 配置对外名称、说明、协议 AgentSkill、输入输出媒体类型和公开域名。
- 绑定认证与信任策略。
- 执行发布前检查和兼容性测试。
- 发布、暂停、创建新修订、回滚到已验证修订。

### 4.2 Agent 开发者 / 集成开发者

目标：快速确认一个外部 Agent 是否真实、兼容、可用，并安全地让本地 Agent 调用它。

核心任务：

- 输入 Agent Card URL，完成发现。
- 查看提供方、签名、TLS、协议版本、接口、AgentSkill 和安全要求。
- 配置出站凭据并测试最小请求。
- 将远程 Agent 的固定修订绑定给本地 Agent。
- 在诊断台构造消息、观察 Task/Event/Artifact，并获得可复制的调用样例。

### 4.3 安全管理员

目标：确保 Agent 间调用有可验证身份、有最小权限、无越权读取和秘密泄露。

核心任务：

- 管理 Principal、凭据、信任等级和授权范围。
- 限制可调用的本地发布、协议 AgentSkill、数据等级、租户、速率和并发。
- 审核 Agent Card 签名、证书与来源变化。
- 处理隔离、吊销、凭据轮换和异常调用。

### 4.4 运维 / SRE

目标：在一次跨 Agent 任务失败时，快速定位到协议、网络、身份、策略、Runtime 或远端中的具体一层。

核心任务：

- 查看入口成功率、P95 延迟、活跃任务、积压、远端健康和异常变化。
- 从 Task 一键跳转 Runtime Run、Trace 和 A2A_DELEGATION Span。
- 查看安全摘要、状态时间线和重试/取消证据。
- 执行只读连通性检查、协议预检和 TCK。

## 5. 信息架构

A2A Hub 在管理端提供一个一级产品入口，内部由六个任务型工作区组成。

| 工作区 | 用户问题 | 关键操作 |
| --- | --- | --- |
| 总览 | 互联是否健康，哪里需要处理？ | 查看指标、风险队列、最近任务、远端变化 |
| 本地发布 | 哪些本地 Agent 已能安全对外服务？ | 创建草稿、预检、测试、发布、暂停、回滚 |
| 远程 Agent | 我们信任并可调用哪些外部 Agent？ | 发现、验证、固定修订、配置凭据、健康检查、禁用 |
| 任务中心 | 一次 Agent 协作发生了什么？ | 筛选 Task、查看时间线/消息/产物、取消、继续输入、跳转 Trace |
| 信任与策略 | 谁能调用谁，允许做什么？ | 管理 Principal、Trust Profile、凭据、范围、吊销和轮换 |
| 开发与诊断 | 集成方怎样接入并证明兼容？ | 预检、请求构造器、代码样例、TCK、协议与配置诊断 |

### 5.1 总览

首屏必须回答状态，不展示装饰性大卡片。默认内容：

- 24 小时入站/出站成功率、P95、当前工作中/等待输入/等待授权任务数。
- 已发布本地 Agent、可信远程 Agent、即将过期凭据、隔离对象数量。
- 待处理队列：签名变化、连续健康失败、任务积压、策略拒绝、TCK 退化。
- 最近跨 Agent 任务，支持直接进入任务详情。

### 5.2 本地发布

采用“草稿修订 → 预检 → 兼容性测试 → 发布”的向导，不提供原始 JSON 作为主编辑界面。

发布向导：

1. 选择本地 Agent：只列出已发布配置版本且 Runtime 可解析的 Agent。
2. 描述能力：填写产品名称、说明和协议 AgentSkill；可从已绑定 Workflow/Capability 生成建议，但必须人工确认。
3. 定义契约：选择输入/输出媒体类型、streaming/push/extended card 能力；界面只允许勾选当前真实实现的能力。
4. 配置入口：生产使用独立 HTTPS host；开发模式可以生成显式 publication-scoped URL。
5. 绑定安全：必须选择 Trust Profile；生产环境禁止匿名发布。
6. 发布前检查：Agent 状态、接口绝对 URL、协议版本、认证声明、证书、策略、AgentCard schema、TCK MUST 项。

发布规则：

- 修订发布后不可变；修改必须创建下一修订。
- Agent Card 由类型化字段确定性生成，保存内容哈希；不接受任意字段覆盖。
- 暂停只停止新任务，既有任务按策略完成或取消；归档不可恢复为同一修订。
- 回滚是把 current revision 指针切到一个仍通过安全门禁的历史修订，不修改历史记录。

### 5.3 远程 Agent

发现流程：

1. 用户输入 Agent Card URL。
2. 系统执行 SSRF 防护、DNS/IP 策略、HTTPS/TLS 校验、大小/超时限制。
3. 解析 Agent Card，校验 1.0 字段、supportedInterfaces、协议能力与安全声明。
4. 验证 JWS（若提供）、记录提供方与内容哈希。
5. 展示差异和风险，用户选择 Trust Profile 与首选接口。
6. 创建不可变远程修订并固定到本地 Agent；不得静默跟随远端 Card 变化。

远端 Card 发生变化时，系统创建候选修订并标记 `CHANGE_REVIEW_REQUIRED`；旧固定修订继续服务，直到管理员审核切换。

### 5.4 任务中心

任务列表以工作状态和责任为中心，至少支持：

- 方向：入站 / 出站。
- 本地发布 / 远程 Agent / Principal / 租户。
- 状态：SUBMITTED、WORKING、INPUT_REQUIRED、AUTH_REQUIRED、COMPLETED、FAILED、CANCELED、REJECTED。
- 时间、traceId、runId、协议版本、transport、错误类别。

任务详情采用一条可扫描时间线：

- 已认证 Principal 与命中的 Trust Profile。
- Message、状态变更、Artifact、策略决策、Runtime Run、远端请求与重试。
- 内容默认展示脱敏摘要；有独立权限时才能查看保留期内的正文。
- 每次状态变化有递增序号，页面能识别事件缺口和乱序。
- `INPUT_REQUIRED` 提供继续输入操作；`AUTH_REQUIRED` 只展示授权需求和安全通道指引，不允许把秘密直接写入普通 Message。
- 取消必须显示“已请求 → Runtime/远端确认 → 已取消”证据，不把本地字段更新伪装成成功。

### 5.5 信任与策略

Trust Profile 至少覆盖：

- 方向与环境。
- 认证方式：API Key 哈希校验、HMAC、OAuth 2.0 Client Credentials、mTLS；匿名仅允许本地开发且显式标红。
- 可访问的 Publication、协议 AgentSkill 和操作。
- 租户绑定与 delegated user identity 策略。
- 数据等级、附件/URL Part 规则、最大请求体与 Artifact 大小。
- 速率、并发、超时、重试、熔断与日配额。
- Personal Memory 策略；远程 Agent Principal 默认禁用个人记忆。

Principal 必须来自认证结果。请求 `metadata` 中的 `userId`、tenant、role 或 scope 只是非可信输入，不能提升为安全主体。

### 5.6 开发与诊断

诊断页必须提供：

- Agent Card 预览与 schema 校验结果。
- 绑定、协议版本和 capability 一致性检查。
- 脱敏的 curl / Java / JavaScript 调用样例。
- 消息构造器和响应/事件查看器。
- 官方 A2A TCK MUST/SHOULD/MAY 报告入口。
- DNS、TLS、签名、认证、授权、Runtime 和远端分层诊断，不只返回“调用失败”。

## 6. 关键用户旅程

### 6.1 首次发布本地 Agent

成功标准：一个不了解 ReachAI 内部表结构的管理员，可以从 Agent 选择开始，在一个工作流中完成配置、风险修正、TCK 验证和发布；外部开发者仅凭 Card URL、凭据和文档即可完成第一次任务。

### 6.2 首次接入远程 Agent

成功标准：开发者粘贴 Card URL 后能判断“是谁、支持什么、怎么认证、是否可信、是否兼容”；绑定使用固定修订，远端改变不会未经审核进入生产。

### 6.3 Supervisor 动态委派

成功标准：Agent 只看到其发布配置版本中允许的远程 Agent 摘要；选择后由 Runtime 的协议中立端口发起委派，Control 处理 A2A；结果以 Artifact 返回，Trace 中形成 A2A_DELEGATION span。

### 6.4 长任务与多轮恢复

成功标准：服务重启后 Task、事件序列和 Runtime 关联仍可恢复；客户端可以轮询或重连；INPUT_REQUIRED/AUTH_REQUIRED 不被误判为失败或完成。

### 6.5 故障调查

成功标准：运维从一个 taskId 能定位 Principal、Policy、Publication/Remote Revision、协议调用、Runtime Run/Trace、最终状态和安全摘要，不需要查询原始请求日志或手工拼表。

## 7. 角色与权限

建议平台权限：

| 权限 | 用途 |
| --- | --- |
| `a2a-hub:read` | 查看总览、目录与脱敏任务摘要 |
| `a2a-hub:publication:manage` | 创建、预检、发布、暂停和回滚本地发布 |
| `a2a-hub:remote-agent:manage` | 发现、审核、固定和禁用远程 Agent |
| `a2a-hub:trust:manage` | 管理 Principal、Trust Profile 和授权范围 |
| `a2a-hub:credential:manage` | 创建、轮换、吊销凭据；接口永不回显秘密 |
| `a2a-hub:task:operate` | 取消、继续输入、重试允许的任务 |
| `a2a-hub:payload:read` | 查看保留期内经审计授权的正文/Artifact |
| `a2a-hub:conformance:run` | 运行诊断和 TCK |

生产默认采用职责分离：发布者不能自动批准自己创建的高信任策略；凭据管理者不能读取任务正文。

## 8. 产品状态与状态机

### 8.1 Publication

`DRAFT → VALIDATING → READY → PUBLISHED → SUSPENDED → ARCHIVED`

- `VALIDATING` 失败回到 `DRAFT` 并保留检查结果。
- 只有不可变 revision 可以进入 `PUBLISHED`。
- `SUSPENDED` 可恢复同一 revision；`ARCHIVED` 为终态。

### 8.2 Remote Agent

`DISCOVERED → VERIFYING → REVIEW_REQUIRED → TRUSTED → DISABLED / QUARANTINED → ARCHIVED`

- 签名或 host 身份变化优先进入 `QUARANTINED`，不得只显示弱提醒。
- 连续健康失败只改变 health status，不自动把新修订设为可信。

### 8.3 Task

严格使用 A2A 1.0 状态：

`TASK_STATE_SUBMITTED → TASK_STATE_WORKING → {TASK_STATE_INPUT_REQUIRED | TASK_STATE_AUTH_REQUIRED | terminal}`

终态：`TASK_STATE_COMPLETED`、`TASK_STATE_FAILED`、`TASK_STATE_CANCELED`、`TASK_STATE_REJECTED`。

从 interrupted state 收到合规后续输入或安全通道授权后可回到 `TASK_STATE_WORKING`。任何非法迁移都必须被领域层拒绝并记录审计事件。

## 9. 企业级非功能要求

### 9.1 安全

- 生产 HTTP binding 只允许 HTTPS；远端 TLS 身份必须校验。
- 每个协议请求都认证并重新授权，不依赖首次会话结果。
- 任务、上下文、消息、Artifact 查询必须同时限定 Principal 和租户。
- 凭据只保存哈希、密文或外部 secret reference；创建后不回显。
- URL Part、Card URL 和 webhook 必须经过 SSRF 防护。
- 默认日志不保存原始 request/response body、Authorization、Cookie、token、文件正文或模型输入输出。

### 9.2 可靠性

- Message ID 在 Principal 作用域内幂等；重复请求返回同一资源或确定性冲突。
- Task 和事件先持久化再投递；跨服务/推送使用 durable outbox。
- 状态事件单 Task 严格递增，消费者可重复处理。
- 超时与客户端断连不等于任务失败；Task 生命周期独立于单条连接。
- 取消是协作协议，有明确 requested/accepted/terminal 证据。

### 9.3 可观测性

- 每个请求具有 requestId、taskId、contextId、principalId、publication/remote revision、runId、traceId。
- 指标按方向、主体、Agent、状态、操作和错误类别聚合，不把高基数正文放入 label。
- Transport 只保存脱敏摘要、字节数、哈希、状态码、耗时和错误类别。

### 9.4 性能与配额

首发目标：

- Agent Card P95 小于 200 ms（不含外部网络）。
- 已接收消息在 1 秒内持久化 Task 并返回 SUBMITTED/明确拒绝。
- Task 查询 P95 小于 300 ms。
- 每个 Trust Profile 可独立配置 QPS、并发、日配额、请求体与 Artifact 上限。

## 10. 产品指标

北极星指标：**每周成功完成且可追溯的跨 Agent 任务数**。

配套指标：

- 发布漏斗：草稿 → 预检通过 → TCK MUST 通过 → 首次真实调用成功。
- 远端接入漏斗：发现 → 验证 → 信任 → 绑定 → 首次委派成功。
- 任务成功率、P50/P95/P99、INPUT_REQUIRED 恢复率、真实取消完成率。
- 认证失败、策略拒绝、越权读取拦截、签名变化、凭据过期数量。
- Task → Run/Trace 可关联率，目标 100%。
- transport 日志正文/秘密泄露扫描，目标 0。

## 11. 版本范围

### 11.1 首个可用版本必须具备

- A2A 1.0 HTTP+JSON inbound binding。
- 标准 Agent Card、A2A-Version 协商、Message/Task/Artifact 基础模型。
- 本地 Publication 版本化发布与 host/publication 路由。
- Principal、Trust Profile、任务所有权、速率/大小基础策略。
- Message 幂等、持久 Task/Event、真实 Runtime 关联、脱敏审计。
- Remote Agent 发现、不可变修订、固定版本、健康与凭据引用。
- Supervisor 受控出站委派。
- 官方 TCK MUST 门禁和一条真实双系统 E2E。

### 11.2 后续增量

- Streaming、Subscribe、Push Notification。
- INPUT_REQUIRED / AUTH_REQUIRED 完整恢复 UI 和安全授权通道。
- GraphSpec `REMOTE_AGENT` 确定性节点。
- JSON-RPC、gRPC binding。
- JWS 签名与多密钥轮换、mTLS、OAuth 2.0 全链路。
- 多实例事件广播、推送重试、熔断和容量治理。

后续增量只能扩展新领域模型，不能重新引入旧 endpoint/call-log 结构。

## 12. 发布验收门禁

截至 2026-08-24，clean-slate 领域模型、Control/Runtime 边界、六个产品工作区、入站 HTTP+JSON、固定版本出站委派、远端 Task 自动查询/取消、加密内容与凭据、策略交集和安全审计已进入源码。远程开发库已完成 clean-slate 迁移：70 条语句连续执行两遍，19 张目标表、8 项权限、关键列/索引和旧结构退场回查全部通过。运行中的隔离 Control/Runtime、独立 HTTPS 远端、浏览器业务流、真实 Supervisor 委派、受控正文审计和 Trace 回跳均已验证；官方 TCK 也已完整执行并逐项归类差异。当前状态是 **核心 E2E 已验证、生产门禁待完成**，不能称为企业生产可用。

以下证据全部成立才可称为 A2A Hub 可用：

1. 外部开发者从 Card URL 和凭据独立完成第一次调用。
2. 不同 Principal 使用相同 taskId/contextId 无法越权读取。
3. 重复 messageId 不产生第二个 Runtime 执行。
4. 服务重启后工作中 Task 可查询、恢复或确定性收敛。
5. 取消实际触达 Runtime/远端并形成最终状态证据。
6. Remote Agent 固定修订可被本地 Agent 绑定并完成一次委派。
7. INPUT_REQUIRED 至少完成一次中断和继续。
8. Task 页面可一键进入真实 Run/Trace，关联率 100%。
9. 源码、数据库和日志扫描证明无秘密和原始协议正文泄露。
10. A2A TCK MUST 通过；相关 Maven 测试、前端构建、浏览器工作流和真实双系统 E2E 通过。

当前门禁判定：第 6、8 项及主要端到端证据已通过；第 7 项和生产级第 4、5、9 项仍需在 M3/M4 完成。TCK 当前为 235 tests 中 48 passed、12 failed、175 skipped，失败包含业务输出假设、TCK 幂等键复用、规范/TCK 状态码冲突及 TCK 客户端问题，不能用报告百分比替代逐项判定；在稳定工作树完成全量 Runtime 回归前也不进入发布候选。

## 13. 已退役结构守卫

当前源码和新库基线已经删除下列旧结构；后续变更必须保持它们不存在：

- `ControlA2aEndpointController`、`ControlA2aAdminController`。
- 旧 endpoint/call-log/task Entity 与 Mapper。
- `A2aEndpointList.vue`、`A2aSessionMonitor.vue`、旧 `api/a2a.ts` 与 `types/a2a.ts`。
- `/a2a/{agentKey}/.well-known/agent.json`、`/a2a/{agentKey}/jsonrpc`、`/api/admin/a2a/**`。
- 旧 `control_a2a_endpoint`、`control_a2a_call_log` 和旧结构 `control_a2a_task`。
- `protocolVersion=0.2.0`、旧小写 task state、`kind: text`、完整 request/response body 审计和自报 `metadata.userId` 身份逻辑。

退役状态不以“菜单隐藏”为准，必须持续以源文件、路由、表结构、测试和全文搜索均不存在旧契约为准。
