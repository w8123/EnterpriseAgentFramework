# C# 业务系统接入

`ReachAI.AspNetCore` 支持 .NET 10 / ASP.NET Core 10。项目提供 NuGet 制品，使用平台接入向导提供的版本、下载地址与 SHA-256。离线安装时，将制品放入本地 NuGet 源，再在业务项目中添加 `ReachAI.AspNetCore`；不要将凭据写入项目文件或提交到仓库。

```csharp
builder.Services.AddReachAI(builder.Configuration.GetSection("ReachAI"))
    .AddCapabilitiesFrom<OrderCapabilities>();
builder.Services.AddScoped<IReachAiEmbedPrincipalResolver, BusinessPrincipalResolver>();
builder.Services.AddScoped<IReachAiSecurityContextBridge, BusinessSecurityBridge>();
// 配置业务系统的认证中间件。
var app = builder.Build();
app.UseAuthentication();
app.UseAuthorization();
app.MapReachAI();
```

使用环境变量或受保护配置提供 `ReachAI__RegistryUrl`（平台公共地址）、`ReachAI__ProjectCode`、`ReachAI__ProjectName`、`ReachAI__BaseUrl`（平台能访问的业务地址）、`ReachAI__ContextPath`、`ReachAI__Environment` 和可选的 `ReachAI__InstanceId`。项目编码必须与平台注册的规范编码完全一致：使用小写字母、数字、下划线和非连续的内部连字符；SDK 在发送请求前拒绝大小写、空格或其他未规范化形式，避免一次性令牌被消费后无法保存凭据。首次注册提供 `ReachAI__EnrollmentToken` 与应用目录外的 `ReachAI__CredentialFilePath`；已有项目凭据可通过秘密管理器提供 `ReachAI__AppKey` 和 `ReachAI__AppSecret`。两种凭据项不得混用。

默认凭据文件使用 ASP.NET Core Data Protection 加密并原子写入。生产环境必须自行配置持久化、受保护的 Data Protection 密钥环，确保重启和多实例恢复时可解密；Linux 的凭据文件仅授予当前用户读写，Linux 上密钥环本身需要配置证书或其他密钥服务来保护静态密钥。密钥环丢失导致解密失败时接入失败，不自动重用 Enrollment Token；应先恢复密钥环或经平台恢复项目凭据。也可以注入自己的 `IReachAiCredentialStore`，由企业密钥管理服务保存。注册响应丢失后的结果可能不确定，应先到平台核对并恢复凭据；存储故障时不得反复使用一次性 Enrollment Token。

在业务服务的公开方法上声明 `[ReachCapability("order.query", SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]`，参数可用 `[ReachParam("orderId")]` 标注。`CancellationToken` 不计入业务参数。仅显式登记的类型会被发现。宿主声明完整的身份要求、读写影响与参数契约，业务系统继续负责最终角色、租户、行权限和事务。同步由平台签名回调或显式调用 `ReachAiRegistryClient.SyncAsync` 触发；启动只注册和发送心跳。

实现 `IReachAiEmbedPrincipalResolver` 从宿主已验证登录态读取用户、角色、租户和可撤销的业务会话；浏览器不得指定可信用户或租户。实现 `IReachAiSecurityContextBridge` 将经验证的调用身份恢复到现有业务鉴权上下文。业务代码也可以注入 scoped 的 `ReachAiInvocationContextAccessor`。嵌入 broker 是受宿主认证保护的 `POST /reachai/embed/token`，宿主应按照现有会话策略配置 CSRF 防护。登出使业务会话失效后调用 `ReachAiEmbedClient.RevokeBusinessSessionAsync`；网络失败须由业务系统可靠重试，直到平台确认。令牌交换使用正文签名和防重放，业务调用维持现有 invocation token 的兼容语义。

首版支持显式 DTO、标量、数组、列表、简单枚举，以及非递归嵌套对象；不支持动态对象、字典、递归对象、接口/抽象类型或自定义 JSON 转换器。业务方法名称必须唯一。运行期 JSON 使用 SDK 的 camelCase 属性名和数字枚举；`JsonPropertyName` 可固定字段名，属性级转换器不支持。null 与缺失分别处理，签名失败、参数不匹配与业务异常均不会回退到无身份执行。

业务索引写入使用 `ReachAiBusinessIndexClient` 的 `UpsertAsync`、`BatchUpsertAsync`、`DeleteRecordAsync`，请求使用精确正文签名。回源能力使用 `[ReachBusinessIndexResolver]` 与要求业务身份的 `[ReachCapability]`，唯一业务参数声明为 `ReachBusinessIndexResolverRequest request`，返回 `ReachBusinessIndexResolution`。宿主必须先根据当前用户在实际业务数据中检查租户、对象和行权限，再用 `ReachBusinessIndexAccessScope.AuthorizeRow` 及 `CreateAuthorized` 创建结果；SDK 会核对签名身份与请求对象范围。`expectedSourceVersion` 仅为提示，返回的 `sourceVersion` 必须来自实际业务数据。系统不会把客户端声明当作授权。

`SampleOrders` 是可运行的身份与订单夹具，不连接真实数据库。设置上述配置及 `REACHAI_DEMO_PASSWORD` 后运行 `dotnet run --project reachai-dotnet/SampleOrders`。`POST /demo/login` 接受 `{"user":"alice","password":"..."}` 或 `bob`，两者映射不同租户和角色；`POST /demo/logout` 通知撤销。样例只用于受控验证，不应直接上线。

运行 `dotnet run --project reachai-dotnet/ProtocolTests -c Release` 验证 Java 与 C# 共用签名向量、无效 token、输入绑定及索引 guard；`dotnet build reachai-dotnet/SampleOrders/SampleOrders.csproj -c Release` 构建样例后，运行 `python reachai-dotnet/ProtocolTests/http_fixture_test.py <dotnet可执行文件路径>` 会自动选择本机空闲端口、启动样例并在验证后退出，覆盖调用、异步并发身份隔离和本地退出。`dotnet pack reachai-dotnet/ReachAI.AspNetCore/ReachAI.AspNetCore.csproj -c Release` 生成 NuGet 制品。该 HTTP 验证使用测试密钥、模拟上游和内存订单；样例的撤销重试队列也只存在于内存，不能替代生产系统的持久重试。夹具和构建结果不代表真实业务系统或部署环境验收。
