# 业务系统 SDK 接入协议

本文描述 SDK 与 ReachAI 当前使用的语言无关 HTTP/JSON 接入协议。接口归属与认证入口见[公共路由](public-routes.md)；接入步骤与业务身份要求见[系统接入](../integration.md)。协议描述现有行为，不表示所有语言 SDK 已实现所有功能。

业务系统通过 Control 公共入口注册和同步能力；Capability 拥有项目、来源、业务方法及其接纳状态。Runtime 使用已接纳的业务方法和固定版本，能力投影再调用业务实例。宿主负责执行自身数据权限和事务校验。语言、类名或框架不改变项目和能力的业务身份。

## 生命周期和来源

| 方向 | 方法和路径 | 内容 |
| --- | --- | --- |
| 宿主 → ReachAI | `POST /api/registry/projects/register` | `projectCode`、`name`、`baseUrl` 等；首次使用 `X-ReachAI-Registry-Enrollment-Token`，后续使用项目签名 |
| 宿主 → ReachAI | `POST /api/registry/projects/{projectCode}/instances/heartbeat` | 实例 `instanceId`、`baseUrl`、`sdkVersion` 和 `metadata` |
| 宿主 → ReachAI | `POST /api/registry/projects/{projectCode}/instances/offline` | 请求体提供 `instanceId`，按项目签名 |
| 宿主 → ReachAI | `POST /api/registry/projects/{projectCode}/capabilities/sync` | `syncId`、`source`、`apply`、完整 `capabilities` 清单，可选 `httpApis` |
| ReachAI → 宿主 | `POST /reachai/registry/capabilities/sync` | 验证项目签名后扫描并同步；响应提供 `instanceId`、`capabilityCount`、`httpApiCount`、`registryResponse` |
| ReachAI → 宿主 | `POST /reachai/capabilities/{capabilityName}/invoke` | `X-ReachAI-Invocation-Token`，请求体为业务参数对象，成功响应是业务方法实际返回值 |
| 宿主浏览器 → 宿主 | `POST /reachai/embed/token` | 业务登录态解析与短期令牌 broker，浏览器只提交页面上下文 |
| 宿主 → ReachAI | `POST /api/embed/token/exchange`、`POST /api/embed/token/business-session/revoke` | 项目签名及正文签名；业务身份、会话绑定和退出撤销 |

首次注册的响应包含 `appKey`、`appSecret`，后续注册不再返回密钥。凭据需要受保护的持久存储；首次响应丢失或写入失败时，必须核实项目凭据并走恢复流程，不能假设重用一次性 token 会再次返回密钥。

业务方法清单是项目范围的完整来源快照，扫描失败或清单不完整时不能发送空清单冒充成功。能力稳定名在项目内唯一；每个项目、能力名和来源对应同一个业务方法，不因类或语言变化生成新身份。`metadata.assetType` 明确为 `BUSINESS_METHOD`，具体注册结构以 [RegistryContracts](../../reachai-capability-service/src/main/java/com/enterprise/ai/agent/registry/RegistryContracts.java) 和 [参数定义](../../reachai-capability-service/src/main/java/com/enterprise/ai/agent/capability/catalog/tool/definition/ToolDefinitionParameter.java)为准。能力声明包含读写影响、身份要求及输入参数，`apply` 不赋予 SDK 跳过接纳规则的权限。

当前 Capability 服务对同一项目、来源和内容的重复同步会复用已有快照；响应中的 `syncId` 可能是该快照的原始标识，而不是本次请求的标识。客户端须核验响应的项目、有效快照标识和接收数量，不能把两个标识相等当作成功的必要条件，也不能把旧快照标识当作本次请求的独立回执。

`httpApis` 省略表示本次同步不提供 HTTP API 清单；空数组表示已支持该来源且完整清单为空。当前该字段的来源实现为 Spring MVC，其他框架使用 OpenAPI 导入，不能直接伪报为 Spring 来源。业务方法和 HTTP API 是独立资产。

旧业务方法的完整注册对象和 metadata 参与指纹计算。兼容现有 Java 时不得统一重写旧声明、类型名或 metadata；新增运行时信息可随心跳放入实例 metadata。声明变化仍按来源变化、接纳和固定版本规则处理。

## Registry 签名

首次 enrollment 之后的 Registry 请求和平台触发的同步回调携带：`X-ReachAI-App-Key`、`X-ReachAI-Timestamp`、`X-ReachAI-Nonce`、`X-ReachAI-Signature`。`timestamp` 为 Unix 毫秒，`nonce` 为非空随机值；签名原文为三个 UTF-8 字段，以 LF（`\n`）连接：

```text
projectCode\ntimestamp\nnonce
```

使用项目 `appSecret` 的 UTF-8 字节计算 HMAC-SHA256，输出小写十六进制。当前 Registry 验证时间窗，但没有将请求方法、路径或正文绑定进此签名，也没有消费 nonce 来阻止重放。此兼容协议的边界必须保持清楚；引入新的防重放或正文签名方式需单独版本化并保持旧 Java 客户端兼容。

## 正文绑定的项目请求

知识写入与 Embed 交换/撤销使用 `REACHAI_PROJECT_REQUEST_V1`。正文按实际发送的字节计算 SHA-256，转为小写十六进制；签名原文用 LF 连接以下 UTF-8 字段：

```text
REACHAI_PROJECT_REQUEST_V1
METHOD
/absolute/path
projectCode
appKey
timestamp
nonce
bodySha256
```

`METHOD` 转为大写；路径必须以 `/` 开始，不允许 query、fragment、`..` 或 CR/LF。其余字段去除首尾空白且不得包含 CR/LF。使用 `appSecret` 计算 HMAC-SHA256，输出小写十六进制。签名前只序列化一次，并原样发送签过的 UTF-8 字节，不依赖两种语言产生相同 JSON 字段顺序。

直接的正文签名请求携带 `X-ReachAI-App-Key`、`X-ReachAI-Timestamp`、`X-ReachAI-Nonce`、`X-ReachAI-Body-SHA256`、`X-ReachAI-Signature`。Embed 交换与撤销同时携带上述 Registry 签名头，并将正文签名放在 `X-ReachAI-Body-Signature`；两种签名共用 timestamp 和 nonce。正文验签服务按项目记录 nonce 防重放。详细实现见 [正文签名器](../../reachai-capability-sdk/src/main/java/com/enterprise/ai/reach/sdk/auth/ReachAiProjectRequestSigner.java) 与 [Embed 客户端](../../reachai-spring-boot2-starter/src/main/java/com/enterprise/ai/reach/spring/ReachAiEmbedTokenClient.java)。

## 调用身份与响应

平台将业务参数作为 JSON 对象发送到已接纳的能力调用地址，并携带 `X-ReachAI-Invocation-Token`。当前令牌为 HS256 JWT；claims 包含 `projectCode`、`appKey`、`capabilityName`、业务租户和员工身份、角色、Agent / session / trace 等；`iat`、`exp` 为 Unix 秒。接入端校验签名、有效期、项目、能力与项目凭据标识，再在请求作用域还原业务身份并执行宿主授权。当前 token 未绑定参数正文，`jti` 未用于单次执行保证；超时不应自动重发非幂等操作。

成功响应保留业务方法实际返回值及其原有包装，不能固定增加另一层 `data`。宿主应保证 SDK 调用失败返回非 2xx；Java 实现将执行异常返回 `CAPABILITY_EXECUTION_FAILED`，防止宿主全局异常处理器将失败包装成 HTTP 200。未通过调用身份校验不得进入业务方法。

Embed broker 的业务用户必须来自宿主已验证登录态；`principal.attributes.tenantId` 由宿主提供，页面上下文不能替代可信身份。交换请求字段为 `projectCode`、`agentId`、`pageKey`、`pageInstanceId`、`route`、`origin`、`principal`、`businessSessionId` 和 `businessSessionExpiresAt`。业务会话 ID 应是不复用的高熵不透明值，到期时间为 Unix 秒；当请求提供会话绑定或到期时间时，应分别核对响应的 `businessSessionBound`、`businessSessionExpiryApplied`。业务退出后以项目身份调用撤销接口；失败应按业务策略重试直到收到确认。

## 跨语言测试向量

[共享测试向量](../../ai-runtime-contract/src/main/resources/contracts/reachai-sdk-protocol-v1.vectors.json)只包含公开的测试密钥，覆盖 Registry 签名、含中文和空正文的项目请求签名、业务调用 JWT。`body` 字段经 UTF-8 编码后的原始字节是计算依据；`signature`、`bodySha256` 和 `token` 是期望结果。

SDK 应读取同一文件验证这些向量，并测试正文变更、错误项目或能力、过期令牌，以及应用密钥不匹配。Java 侧测试为 [ReachAiSharedProtocolVectorsTest](../../reachai-capability-sdk/src/test/java/com/enterprise/ai/reach/sdk/auth/ReachAiSharedProtocolVectorsTest.java)。这些向量验证协议计算，不代替实际服务、业务权限和端到端验收。
