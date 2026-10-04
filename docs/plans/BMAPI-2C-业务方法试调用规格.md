# BMAPI-2C 业务方法试调用规格

更新时间：2026-09-16。状态：2C-A 后端与 [2C-B 页面](./BMAPI-2C-B-业务方法试调用页面规格.md)均已通过本批代码和隔离验证，分别见[后端复核](../../output/tasks/business-method-api/BMAPI-2C-A-主任务复核.md)与[页面复核](../../output/tasks/business-method-api/BMAPI-2C-B-主任务复核.md)。当前转入 2D Workflow 接入；真实部署环境和外部业务端到端验收尚未完成。

关联[实施基线](./业务方法与API重构实施基线.md) U1 第 4 步、U2 第 3 步；读取依据见[执行契约核对](../../output/tasks/business-method-api/BMAPI-2C-执行契约核对.md)。执行报告是事实线索，其中“健康默认实例 / 身份桥接”等建议不直接作为实施决定，以本文为准。

## 1. 已选择的方案

| 问题 | 本批决定 |
| --- | --- |
| 发起身份 | 平台登录账号负责 Console 授权与审计；使用服务器持有的项目接入凭据调用业务系统 |
| 业务身份 | 不将平台账号或角色复制为业务用户 / 角色；不伪造 tenant、external/global user、部门。需要业务用户的调用条件无法满足时明确拒绝 |
| 发起权限 | 新增项目可限定的 `capability:invoke`，同时要求目录读取权与现有 Tool ACL 精确 ALLOW |
| 调用目标 | 使用当前已接纳契约中的地址与路径，与现有调用路径一致；不自动换到另一个健康实例 |
| 执行与查询 | 独立 Console 公共入口，Control 签名后交 Runtime；复用 Capability typed invocation、SDK 签名与 Run/Trace 基础 |
| 重复与超时 | 每次尝试具有稳定 invocationId；持久化去重先于派发。响应丢失查询原记录，未知结果不自动重试 |

本批不建设平台用户到企业用户的映射管理产品，不新增环境路由 / 负载均衡，也不以 MCP 发布或 Workflow 调试作为前置步骤。

## 2. 身份、权限与真实调用边界

1. 公共 API 沿用平台 session、CSRF 和项目资源授权。增加 `CAPABILITY_INVOKE = capability:invoke`；权限种子与界面常量保持一致，不以 platform:write、Workflow debug 或 RunOps 权限替代执行权。
2. `ControlToolAclDecisionService` 使用当前平台会话 roles、owner 确认的 projectId/projectCode、`targetKind=TOOL` 和 canonical qualifiedName。这里只判断平台操作者是否能发起该资产调用；平台 roles 不进入 SDK claims。SKIPPED、空角色、无命中、DENY 或查询失败全部停止派发。
3. Control → Runtime 命令的 platformActorId 必须与已验签的平台主体一致。Runtime 的 Console service 不把该 actor 构造成可信业务用户；Run 中明确记录 actor 的平台身份来源。
4. Runtime → Capability 复用既有已签名请求中“没有可信业务身份声明”的形态（当前 `RUNTIME_UNTRUSTED`）；它仍有服务 HMAC，名称中的 untrusted 指业务身份。使用现有 gateway 清除身份字段，不传 `WorkflowExecutionIdentity.fromAgent(actorId)` 等提升身份的 marker。
5. SDK token 中的 project/app 来自 Capability 当前有效凭据；业务 tenant/user/roles/部门为空。平台 actor、invocationId、Run/Trace 作为平台审计关联，不能伪装进业务 externalUserId 或 roles。
6. 服务端强制签名调用、BUSINESS_METHOD 类型、canonical project / qualifiedName 和预期契约校验。来源 `requiredRoles` 非空或已有可信调用策略明确要求业务用户时，返回缺少业务身份条件；不把声明角色交给操作者自由输入。
7. 现有 `requireUserIdentity` 是调用约束，不是已普遍存在的 SDK 注解属性。不能把不存在的来源字段写成已支持能力，也不能据字段缺失宣称业务系统不需要用户。业务系统自有 security bridge / resolver 仍可拒绝无业务用户调用，界面应说明“以项目接入凭据调用，业务系统仍会校验”。
8. SQL 仅定义新权限和必要结构，不批量授予现有角色或执行任何真实授权变更；隔离测试显式创建测试授权和 ACL。

## 3. Capability 拥有调用目标与最后一道校验

- 提供精确签名保护的内部 execution-context 查询，按现有 name 解析实际资产；复用 2B 的 owner 身份、来源与参数解析职责，不复制目录 / 注册模型。
- 内部快照包含 canonical project、name / qualifiedName / sourceQualifiedName、assetType、当前 / 已接纳 / 来源 contract hash、source availability、enabled、sideEffect、参数与输出声明、安全的目标描述及凭据是否可用。密钥、token、认证头不离开 owner。
- 只接纳 BUSINESS_METHOD、有效项目与来源绑定、enabled、READY，且 current/accepted/source hash 一致的目标。公开 DTO 返回安全摘要与 expectedContractHash，不能作为随后执行的唯一事实源。
- 实际调用前 Capability 再读当前定义并执行同样的类型、项目、hash、来源及签名条件。Control 准备后发生类型变更 / 来源移除 / 凭据失效，也应在 outbound HTTP 前拦截；不能只在 Control 做一次检查。
- 保留已接纳 baseUrl/contextPath/endpointPath，contract hash 已包含这些字段。默认实例或任意同项目实例的 baseUrl 不能覆盖它。浏览器不能提交 URL 或认证配置。
- 注册实例只作已有目标的辅助证据：有唯一、可信的精确关联时记录 instanceId / 状态；无法关联时写未知，不强制猜一个实例或称为健康。可确定目标实例已被管理性停用时拒绝，不能把不相关实例状态当成该目标状态。
- 不把心跳当作真实调用成功；真正连通性和返回由本次调用记录证明。执行目标提示展示已接纳项目和安全地址，未有环境标签时不称“测试环境”。

## 4. 公共与内部接口

| 入口 | 作用 |
| --- | --- |
| `GET /api/business-methods/{name}/invocation-context` | 取得已授权的参数 / 目标摘要、预期契约、身份模式、操作影响与当前阻塞原因 |
| `POST /api/business-methods/{name}/invocations` | 发起一次试调用；固定目标来自路由和 owner |
| `GET /api/business-method-invocations/{invocationId}` | 查询该尝试状态和允许查看的安全结果；绝不执行 |

POST 请求只有 `invocationId`（UUID）、`expectedContractHash`、`input`（JSON object）、`confirmedSideEffect`。服务端约束和身份不能来自请求；拒绝顶层身份 / URL / role / context / target 覆盖字段。input 内合法业务字段按真实参数契约处理，不因字段恰好叫 userId 就将其升级为调用身份。

- `invocation-context` 先核对项目读取及执行权限；ACL / 来源不满足时给出可理解的不可执行原因，不返回密钥或允许旁路。
- 有写入 / 不可逆影响的方法须显式确认，并绑定当前 expectedContractHash。未知副作用按可能修改数据处理，不按 GET/POST 推断。缺确认、旧 hash 或输入无效不派发。
- 内部新增独立 Console command / query 精确路由（建议 `/internal/runtime/console-capability-invocations` 及 `/{id}`），复用当前 Control → Runtime 签名基础并绑定 actor、project、target、input digest 与 deadline。不得使用 MCP client/publication 或 debug 身份冒充。
- 同步发起返回当前 invocation outcome；内部超时或响应丢失时返回可查询的 invocationId 和“结果尚未确认”，不把未收到答复等同于未执行。查询暂未发现记录也不构成重新 POST 的许可。

## 5. Runtime 持久化、状态与重复请求

- Run 类型使用 `CONSOLE_CAPABILITY`，entry 使用 `CONSOLE`，复用 root Run/Trace 的创建、结束和证据写入。Console 使用窄应用服务，不拷贝 MCP JSON-RPC、发布范围、Workflow 执行和审计整条链。
- Runtime 可新增 `runtime_console_capability_invocation` 作为该 Run 的幂等与结果扩展记录；owner 为 Runtime。它保存 invocationId、平台 actor、canonical project/target/hash、规范化请求 fingerprint、run/trace 关联、派发阶段、结果与时间；不保存原始输入 / 凭据。
- 数据库唯一约束及原子状态转换保证同一 invocationId 的重复 / 并发请求只接纳一次。相同 ID 且 fingerprint 相同返回原记录；不同内容或归属返回冲突 / 不可访问，不能改写原记录。
- 先持久化接纳记录与 root Run，再允许派发；持久化失败不得调用业务系统。派发前原子 claim；同一个已 claim 请求即使进程恢复也不重新发送。扩展记录与 Run 摘要在本服务事务中保持一致。
- 状态区分 `ACCEPTED`、`DISPATCHING`、`SUCCEEDED`、`BUSINESS_FAILED`、`NOT_DISPATCHED`、`UNKNOWN`。NOT_DISPATCHED 必须有确定的派发前拒绝证据；已派发后的超时、断连、进程中断、响应无法关联等为 UNKNOWN。
- 现有 typed response 的 TECHNICAL_FAILED / retryable 不足以证明未派发。Console outcome 应保留实际 transport 阶段证据；缺少该证据时采用 UNKNOWN，不利用异常文案猜“没有执行”。可为现有 invocation 增加窄的可选阶段证据，保持既有消费者兼容。
- 自动重试统一关闭，包括 HTTP client、Feign、任务调度与页面。Idempotency-Key 传给下游属于辅助保护，不能据此承诺业务系统具备 exactly-once。
- 在调用前已有确认的失败可新建尝试；UNKNOWN 先提供只读查询。用户明确新建写操作尝试时再次提示前次可能已生效，不把“查询失败”按钮实现为重新执行。
- 超时沿用来源有效声明与现有执行上限，默认 30 秒、上限 60 秒；deadline 从 Runtime 到 Capability 一致传递。取消等待不表示已取消业务副作用。已过 deadline 的未 claim 记录不得随后派发。
- 去重记录在关联 Run 的审计保留期内保留；结果过期不删除幂等身份或使同 ID 重新执行。本批不建设全平台清理机制或自动重放。

## 6. 结果查看与信息处理

- 普通发起者凭当前项目读取 / 调用权和 owner actor 校验查看自己的记录；其他账号需要现有该项目 RUNOPS_READ，并使用受控只读入口。不能仅凭 invocationId、traceId 或浏览器 userId 返回结果。
- 查询以 Runtime 记录中的 canonical project / actor 为依据，当前项目权被撤销后拒绝读取；方法随后移除不改变历史记录的归属。通用 RunOps 原有授权继续有效。
- 结果至少提供 invocationId、runId/traceId、派发 / outcome 状态、目标与身份模式、耗时、安全业务结果或错误类别、是否截断及结果有效期。来源校验失败、权限拒绝、输入错误、业务失败、上游不可达 / 结果未知分别表达。
- 输入按已接纳声明确定性校验 required、可识别类型 / 嵌套形态、已声明枚举与边界。沿用实际 SDK 绑定规则：平铺 map，单 DTO 根对象按 SDK 语义映射；不擅自再包一个 arguments/request。无法可靠解析的字段保留明确诊断，不伪造完整 JSON Schema。
- 接受的 example 只作可选择的来源提示，不能自动提交；对象 / 数组样例按真实 JSON 展示。未知返回 schema 可展示经过处理的实际 JSON，不把缺少 schema 写成不存在返回值。
- 请求在内存中绑定和派发；不写入 URL、localStorage、原始 Run/Trace、日志或未经处理的异常。敏感字段按声明路径处理，输出 / 错误中回显的敏感输入值及已知凭据字段也应遮盖。平台签名和项目密钥不得进入结果。
- Runtime / Trace / ToolCall 持久化及返回浏览器前完成统一信息处理；复制只复制处理后的结果，原始请求输入仅留在当前编辑会话内。不能先写入原始 Trace 再仅在 UI 遮盖。
- 安全结果默认保留 24 小时，最大持久化 64 KiB，可配置；超出上限明确标注截断，过期保留状态与审计关联并清除结果体。使用仅针对本扩展表的过期处理，不删 Run/幂等身份。过期或未记录的内容不得伪造成原业务返回。

## 7. 分批实现与页面要求

### 2C-A：先交付受控执行和可查询记录

允许修改 Capability / Control / Runtime / 必要 ai-common 契约、SQL 与测试。新增权限及 Runtime 扩展表同步 initV2、upgrade、sql/README 和 service-table-ownership；不执行真实开发库升级。新增 internal 路由、client、签名与源码依赖保持 owning service 边界。

优先复用现有 typed invocation、签名、来源校验、项目授权、ACL 和 root Run/Trace；只抽取当前确实共享的窄组件。MCP 与 Workflow 现有消费者保持行为，并执行受影响的回归。SDK 若无需改契约则保持不变；隔离样例可以使用当前 SDK Endpoint 验证签名和 DTO 绑定。

本子批次完成时不宣称“页面可试用”；提交后端实际调用 / 拒绝 / 恢复证据，主任务复核后下发 2C-B。

### 2C-B：随后交付方法详情中的试调用面板

- 入口来自具体业务方法，用户先看到调用目标、项目凭据身份模式、参数和副作用。动态读取 invocation-context；范围 / 账号改变时关闭旧面板并丢弃旧输入与响应。
- 表单与 JSON 输入遵从来源声明；保持嵌套对象 / 数组可编辑，不做大量自造字段控件。空参方法可直接确认调用；有副作用的方法显式确认后发送。
- 一次点击生成并保存本次 invocationId；按钮避免重复提交，未知结果提供“查询本次结果”。页面只持久化按账号 / 项目隔离的尝试引用，不持久化输入或结果。
- 结果展示真实状态、处理后的 JSON、错误与安全 Trace 阶段；有 RUNOPS_READ 才链接通用 Trace 页面。输入错误定位到字段，未知结果不展示可误点的自动重试。
- 延续 2B shared overlay / 状态 / 主题与 frontend-design 技能要求，验证窄屏、焦点、关闭、重新打开、账号 / 项目切换和迟到响应。

## 8. 2C-A 验收要求

| 类别 | 必须证明的行为 |
| --- | --- |
| 权限 / 身份 | 未登录 / CSRF / 无调用权 / 跨项目 / ACL 非 ALLOW 不派发；平台角色不会成为业务角色；无业务用户声明仍有合法服务签名与项目 token |
| 目标 | 非方法、停用、来源漂移 / 移除、accepted/current/source hash 不一致、旧 expectedHash、缺凭据拒绝；准备后变化也在实际调用前被捕获；不会切换已接纳 URL |
| 参数 / 副作用 | 标量、单 DTO、嵌套 / 数组按真实 SDK 绑定；错误输入和未确认写操作不派发；声明要求业务身份时明确阻断或由真实 SDK bridge 拒绝 |
| 结果 / 重复 | 真实本地 HTTP 成功、业务失败、发送后延迟 / 断连；同 ID 并发和重复不增加 stub 请求次数；不同 payload 冲突；持久化失败为零调用；结果丢失可 GET 查原记录 |
| 崩溃 / 未知 | 已派发进程中断 / 超时只进入 UNKNOWN，无自动重试；过期未 claim 不可迟派发；只读查询不增加调用计数 |
| 记录 / 保密 | 每次接纳有独立 Run/Trace/owner；声明敏感输入及回显不出现在存储、日志、结果、复制模型；结果超限 / 过期不重新执行 |
| 回归 | 相关模块测试 / 必要编译、MCP/Workflow 受影响身份与 typed invocation 测试、架构 / SQL / 文档守卫及 diff |

使用隔离 H2、固定测试凭据与真实本地 HTTP stub / SDK Endpoint；记录 stub 接收到的安全头字段、业务身份字段是否为空、body 绑定和请求计数。不得读取真实凭据或通过更改真实角色 / 业务权限完成验收。

执行任务报告写 `output/tasks/business-method-api/BMAPI-2C-A-实施结果.md`，保留日志、测试名、改动范围、未验证项与请求次数证据；执行结束回调主任务一次并停止，不自行进入 2C-B。完整方法路径与真实环境在 2D 及后续验收中继续验证。
