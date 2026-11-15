# 系统接入

目标是让平台发现并受控调用业务系统明确开放的操作，同时沿用原系统的身份、权限和事务。SDK 接入、页面嵌入和员工工具连接分别验证，不用注册成功代替真实业务调用成功。

## 手动接入顺序

1. 首次接入从「项目管理 → 接入项目 → SDK 接入」生成一次性接入令牌。在弹窗内选择技术栈，下载并核对平台制品，按对应 SDK 的配置使用令牌。首次启动注册成功后，刷新项目列表并打开「项目详情 → 项目接入工作台 → 手动接入」。后续启动沿用业务后端的受保护凭据存储。
2. 在业务代码中声明本次要开放的方法，优先从只读方法开始。按所选 SDK 声明稳定名称、入参与返回结构、副作用和业务身份要求。
3. 从业务系统既有登录态取得用户、租户与角色。通过调用身份桥把已验证身份带入业务授权。嵌入对话时配置后端 Token Broker。
4. 确认 ReachAI 能从服务端访问项目的后端地址和 Context Path。对同步回调与业务方法调用保留签名、令牌和追踪请求头。对话令牌接口继续使用业务登录认证与 CSRF 防护。通过网关代理 Embed API 时，代理保留短期 Embed Token 的 Authorization 头。
5. 启动或重启业务服务，在工作台刷新平台自检，看到在线实例后前往「业务能力 → 接入与变更 → 同步与发现 → 扫描并同步接口」。签名回调成功后，在能力目录核对本次开放的方法和契约，显式接纳需生效的变化。
6. 需要页面对话时，按[嵌入指南](developer/embed-chat.md)配置已发布 Agent 和允许来源，安装 Embed Chat SDK，在已登录的业务页面挂载对话。
7. 用有权限和无权限的业务测试账号验证真实调用与拒绝行为。对开放的能力，在原业务系统核对结果并回查 Trace。接入页面时同时验证登录、退出、会话隔离和本次使用的页面动作。

回调超时时核对平台到业务服务的网络。出现 401 或 403 时区分业务登录拦截、CSRF 和 SDK 签名拒绝。出现 404 或 405 时检查 Context Path、转发规则和 POST 方法。

## Java 系统

在业务启动模块引入 [Starter](../reachai-spring-boot2-starter/README.md)，声明注解的业务模块引入 [Capability SDK](../reachai-capability-sdk/README.md)。使用平台给出的版本与制品，并在 Spring Bean 的公开方法上声明允许调用的业务能力。

```yaml
reachai:
  registry:
    url: https://reachai.example.com
    enrollment-token: ${REACHAI_REGISTRY_ENROLLMENT_TOKEN}
  project:
    code: order-service
    name: 订单系统
    base-url: https://orders.example.com
  capability:
    scan-mode: ANNOTATED_ONLY
    scan-packages:
      - com.company.order
```

注册完成后 Starter 将项目凭据原子写入受保护的 credential store，不持久化 Enrollment Token。凭据文件应位于应用包之外，浏览器不持有 appSecret。已配置静态凭据的系统按 Starter 当前配置契约使用，不混用不同项目身份。

```java
@ReachCapability(name = "order.query", title = "查询订单",
    description = "查询当前业务用户可见的订单", sideEffect = ReachSideEffectLevel.READ,
    businessIdentityRequired = true)
public OrderView query(@ReachParam(name = "orderId", required = true) String orderId) {
    return loadAuthorizedOrder(orderId);
}
```

业务方法与 HTTP API 是两种契约，不能按是否走 HTTP 或 `source=sdk` 猜测类型。同一 Java 实现同时声明两种入口时保留各自来源和身份；响应 schema 保留实际业务包装，例如 `{success,data:{count}}` 的字段路径是 `data.count`。

同一业务进程中的 `@ReachCapability` 名称必须唯一，包括省略 `name` 时使用的方法名。发现重名时 SDK 会拒绝同步与调用，修正声明后再重新同步，避免将一个名称绑定到错误的实现。

## C# / ASP.NET Core 系统

使用 [C# SDK](../reachai-dotnet/README.md) 接入 ASP.NET Core：从平台接入向导选择 C#，下载并核验 NuGet 制品；配置项目、平台地址、业务服务地址和受保护凭据存储；通过 Attribute 显式声明业务方法，并注册业务身份桥与已登录用户解析器。启动后在项目来源核对实例、完整能力清单和来源变化，再按真实业务身份验证调用。

SDK 支持将业务方法的返回结构作为来源契约同步，Studio 据此提供字段引用；业务方法与 HTTP API 仍是独立资产。ASP.NET Core HTTP API 使用下述 OpenAPI 导入，不将其声明为 Spring MVC 来源。业务系统负责校验业务权限与事务，退出时调用业务会话撤销接口；断网时应按业务策略可靠重试。

## Python / FastAPI 系统

从接入向导选择 Python，下载并校验 `reachai-fastapi` wheel；配置平台地址、项目编码、业务实例地址和受保护的凭据存储。通过装饰器显式声明业务方法，接入真实登录态解析器和业务身份桥，启动后同步并审核能力，再用真实业务用户验证权限和数据结果。详细配置、受支持的类型、一次性注册恢复及多进程注意事项见 [Python SDK](../reachai-python/README.md)。

首版适配 FastAPI；其他 Python Web 框架尚无官方适配器。FastAPI 的 HTTP API 可通过 OpenAPI 导入；嵌入对话与页面动作继续使用通用前端 SDK。业务系统负责校验用户、租户、数据权限与事务，并在退出时可靠撤销业务会话。

## Node.js / Express 系统

使用 [Node.js 后端 SDK](../reachai-node/README.md) 接入 Express 业务系统：在平台选择 Node.js，下载并校验安装包，配置平台和业务服务地址、受保护的项目凭据以及允许嵌入的业务站点来源。显式声明业务方法及其输入、输出契约，接入真实业务登录态和身份桥；同步、审核后，用真实业务用户验证权限及业务结果。浏览器继续使用现有 Embed Chat 和 Page Bridge，项目密钥只能保存在业务后端。

首版适配 Express；其他 Node.js Web 框架的适配范围以 SDK 文档为准。业务系统 HTTP API 可使用 OpenAPI 导入；多进程凭据、签名回调防重放和业务会话撤销按 SDK 文档配置，宿主仍负责用户、租户、数据权限与事务。

## PHP 系统

使用 [PHP SDK](../reachai-php/README.md) 接入原生 PHP、Laravel 或 ThinkPHP 业务系统。从平台下载并校验 Composer 制品包，通过 artifact 仓库安装；配置平台地址、业务实例地址和受保护的项目凭据。显式声明业务方法及参数、返回结构，从框架登录会话解析员工并在身份桥里恢复业务用户。PHP-FPM 没有常驻进程，首次注册和心跳由命令行执行（cron 或常驻进程），Web 请求只处理签名回调、业务调用和 Embed 换票。多台服务器需配置共享 nonce 存储。

## Go 系统

使用 [Go SDK](../reachai-go/README.md) 接入 Go 业务系统。从平台下载并校验源码包，在业务项目中通过本地模块替换引入；配置平台地址、业务实例地址和受保护的项目凭据。显式声明业务方法及输入、输出契约，在 `net/http` 服务中挂载接入端点，并实现基于已验证业务会话的身份桥。启动后在平台同步、审核能力，再用真实业务用户验证权限与业务结果。

首版提供标准库 `net/http` 适配，业务 HTTP API 可继续使用 OpenAPI 导入。前端嵌入使用现有 Embed Chat 和 Page Bridge；密钥只保存在后端。多实例回调防重放、业务会话撤销及数据权限由宿主按 [Go SDK](../reachai-go/README.md) 配置和验证。

## 没有 SDK 的系统

无法使用 SDK 的系统，在「项目管理 → 新建项目 → OpenAPI 导入」上传 Swagger / OpenAPI 导出的 `.json`、`.yaml` 或 `.yml` 文档（最大 5MB）。文档内容保存在平台数据库，之后在项目来源的「同步与发现」里重新导入即可刷新接口；更换文档在项目详情「编辑」里重新上传。

导入并接纳后的 HTTP API 默认只能在 Workflow 里使用。要让员工的 AI 工具和内嵌助手直接调用，在「API」页点「发布给员工和助手」：平台为每个选中的 API 生成一条「调用一次接口」的流程（不含模型节点，不消耗 token），发布后加入所选的员工发布，再到该发布页重新发布即可生效。只支持已接纳、且参数形态受流程支持的 API（GET 只读，或来源明确声明为写操作的 POST JSON）；写操作仍走写审批。API 没有中文名称，生成时建议填一个给员工和助手看的名称，方便助手按名称找到。

平台不读取服务器上的源码或文件目录，也不再提供 Controller 源码扫描。Java / Spring 系统用 Starter 发现 Spring MVC 接口，可以拿到完整的参数和响应结构。旧库升级见 [upgrade-20261028-remove-source-code-scan.sql](../sql/upgrade-20261028-remove-source-code-scan.sql)。

## 业务身份

Starter 验证调用签名与令牌，再通过 `ReachAiSecurityContextBridge` 还原业务用户、租户和角色。业务系统保留最终数据权限与事务校验；不能把平台账号、浏览器提交的用户 ID 或缺失身份自动映射成业务管理员。

业务方法即使不要求特定角色，只要依赖当前业务用户，也应声明 `businessIdentityRequired = true`。平台会拒绝无签名用户身份的调用以及控制台项目身份试调用；声明不能替代业务系统对用户、租户和数据权限的验证。新增或修改声明会改变能力契约，需重新同步并评审。

Registry 心跳、下线和同步回调按项目签名；来源可发现不代表已接纳或可执行。来源变化、试调用和发布保护见[Workflow](workflow.md)。

## 嵌入业务页面

1. 业务后端实现 `ReachAiEmbedPrincipalResolver`，从当前登录态解析用户、租户和角色；Starter 提供 `POST /reachai/embed/token`。
2. 在 Agent 的嵌入配置中设置允许的项目、来源域名和令牌策略，核对 readiness。
3. 前端按平台生成的接入代码使用 `createEafChat()`；令牌由后端 broker 提供，不能在前端签发。
4. 用 `createEafPageBridge()` 注册当前页面允许的读取、筛选、导航和表单动作。动作契约与页面上下文传给平台，业务数据写入仍走业务方法或 API。

Embed Token、会话、项目、用户与 Origin 必须一致。Starter 的令牌交换以项目凭据签名完整请求正文并防重放；Control、Capability 和 Starter 需要一起升级，旧版仅签名项目标识的令牌交换请求会被拒绝。业务身份中的 `attributes.tenantId` 由业务后端从已验证登录态提供，未提供时使用 `default`；浏览器请求体不能指定可信身份。页面注册使用 Embed Token。跨页面动作遵循 `NAVIGATE → TARGET_READY → PAGE_ACTION`，只在目标页面就绪后执行；动作要求确认时，跳转和目标动作分别需要用户确认，取消或超时不会继续执行后续阶段。

Control 在动作入队时固定服务端页面及动作目录声明的摘要，并在跨页恢复、动作认领前复核；目录发生变更时需要重新发起请求。写入、不可逆以及 SDK 或扫描自动登记的动作至少需要确认，风险缺失或无法识别时拒绝执行。升级前已入队且缺少摘要的动作不能继续执行。摘要只证明目录声明未变，不能证明业务页面回调的实际行为；宿主负责审查回调及其可能触发的自动保存。

页面动作认领后的超时、执行异常或服务间连接中断可能发生在业务操作已开始之后。此时返回 `PAGE_BRIDGE_RESULT_UNCONFIRMED`，助手应先提示用户核对业务记录；本次运行不再发起新的执行工具调用，已经并发开始的调用仍需核对。认领前由 Control 确认的超时仍表示业务动作未开始。

SDK 的 `createEafChat({ onEvent })` 在 Control 接收并确认对应页面动作结果后发出 `page.action.result.delivered`，`event.data` 只含 `requestId`、`actionKey`、`status`、`userConfirmed`。宿主需要在回执后执行后续操作时，应关联本次登记的 `requestId`，并检查具体状态和用户确认；失败、取消或没有收到可验证回执时不能按成功处理。此事件只表示动作结果已被平台接收，不表示后续跳转、页面加载或业务结果已完成。

对于需要全页跳转的业务动作，宿主可等待 `page.action.result.settled`：该事件还要求同一 Embed 会话、同一服务端 trace 的 Supervisor 真实完成，`data` 在上述字段外增加 `traceId`。宿主仍须核对自己待处理的 `requestId`、成功状态和必要的用户确认，再跳转到预先允许的同源地址。等待交互、失败、取消、缺少可信 trace 或未收到回执时不会产生可用于跳转的完成事件；旧服务端缺少关联信息时不会触发该事件，普通页面动作仍可使用。结果页是否加载成功需在目标页单独验证。

多文档应用中，跨页动作可以在 Bridge 显式设置 `documentNavigation: true` 并实现 `onNavigate`。回调只登记经过宿主白名单验证的目标；同会话、同 trace 的用户确认、页面回执和持久等待状态齐备后 SDK 发出 `page.navigation.ready`。宿主核对对应请求并跳转，目标页用新的业务身份令牌创建 Bridge，调用 `resumePageNavigation` 重新绑定、恢复可见历史并继续原 Workflow；目标动作仍按目录要求单独确认。不要在收到普通回执时直接跳转，也不要重发原提问。取消、过期、身份变更或续接结果不明确时应保留失败提示。

普通同标签页面切换可显式启用 `pageTransfer: true`，在空闲时用 `preparePageTransfer()` 预取短期一次性票据，目标页调用 `resumePageTransfer()`；`page.transfer.invalidated` 表示新一轮开始或上下文已清空，应立即丢弃旧票据。票据可暂存于当前标签的 `sessionStorage`，不能保存 Embed Token；新标签可能复制会话存储，宿主应使用 `noopener` 并避免有意共享，服务端只允许一次领取。旧服务端或没有业务登录会话绑定的系统不能使用该能力。服务端在领取时重新核验身份、目标页、对话代次和运行状态，失败不能把旧消息拼接到新会话。后台休眠、网络中断、复制标签或超时可能使票据失效，宿主应提示并安全重试；运行中的操作及不确定结果不会自动重发。

Starter 的平台注册地址应直接指向目标服务，不能依靠 HTTP 重定向；带项目签名的请求遇到重定向时会失败，避免将请求凭据发送到其他地址。Starter 接收的单次响应上限为 8 MiB；HTTP 错误只保留状态码，不记录上游错误正文。

SDK 对话主题隔离于宿主页面。参数与导出类型以 [eafChat.ts](../ai-admin-front/src/sdk/eafChat.ts)、[eafPageBridge.ts](../ai-admin-front/src/sdk/eafPageBridge.ts)及生成的类型声明为准。

## 员工工具与样例

管理员在 MCP 服务中开通员工使用；员工从业务系统面板生成个人连接，再复制生成的配置到自己的 AI 工具。身份、写审批与审计规则见[开放协议](protocols.md)。

- [芋道 ERP 接入实战](developer/yudao-example.md)：后端身份桥、页面补丁与验收。
- [员工使用自己的 AI 工具](business/own-ai-tool.md)：生成连接、写审批、Skill 下载与断开。

验收覆盖签名拒绝、非白名单来源、跨项目/用户隔离、只读调用、写确认、契约变化与运行回查。当前环境的检查条件见[运维](operations.md)。
