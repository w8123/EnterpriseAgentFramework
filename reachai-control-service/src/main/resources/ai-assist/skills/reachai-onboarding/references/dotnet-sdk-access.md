# C# ASP.NET Core 接入

适用 ASP.NET Core / .NET 10。先核对业务项目结构、现有登录与用户/租户授权，再改动业务系统。平台基础地址不是 NuGet feed。

1. 读取 onboarding manifest 时附加 `?language=csharp`，从 `sdkArtifacts` 选择 `language=csharp` 且 `packageName=ReachAI.AspNetCore` 的条目。必须取得非空的 `downloadUrl` 和 `integritySha256`，下载 nupkg 后验证 SHA-256；缺少文件或摘要时报告制品未就绪，不猜测 NuGet 公网源。
2. 将验证后的包保存到业务项目长期可访问的本地 NuGet feed，在项目或组织的 NuGet.Config 中持久配置该 feed，再使用 manifest 指定版本安装 `ReachAI.AspNetCore`。确保干净环境可恢复；不要引用临时目录或把凭据放入 NuGet.Config。
3. 用 `services.AddReachAI(configuration.GetSection("ReachAI"))` 注册 SDK，并对业务负责的类调用 `AddCapabilitiesFrom<T>()`，映射 `app.MapReachAI()`。配置 `RegistryUrl`、`ProjectCode`、`ProjectName`、`BaseUrl`、`ContextPath`、`Environment`；`BaseUrl` 必须能被平台访问。Enrollment Token / 项目凭据只使用环境变量或安全密钥存储；默认配置提供程序对应环境变量 `ReachAI__EnrollmentToken`、`ReachAI__AppKey`、`ReachAI__AppSecret`，不要使用 Java 专属的 `REACHAI_REGISTRY_APP_SECRET` 代替。持久凭据文件放在应用包外并限制访问。
4. 用 `[ReachCapability]` 明确声明开放方法、描述、副作用、是否需要业务身份，参数用 `[ReachParam]` 声明；能力名在实例内唯一。对外身份来自已验证登录态，业务服务仍负责租户、行级数据权限和事务。实现 `IReachAiEmbedPrincipalResolver` 与 `IReachAiSecurityContextBridge`，不得信任浏览器自报身份。业务退出时调用 `ReachAiEmbedClient.RevokeBusinessSessionAsync`，并处理撤销失败的重试。
5. 为 `/reachai/registry/capabilities/sync` 和 `/reachai/capabilities/{name}/invoke` 保留 SDK 的项目签名或调用令牌验证；只对 ReachAI 访问开放必要网络路径。`/reachai/embed/token` 应处于宿主正常业务登录与 CSRF 保护之下，不把项目密钥暴露给浏览器。
6. 业务实例注册与心跳正常后，通过任务 `SDK_SYNC` 验证或平台手动发起受签名的显式同步。普通 HTTP API 的接入使用平台 OpenAPI 导入；不要把 ASP.NET API 冒充 Spring MVC 的 SDK 清单。
7. 复用浏览器 `@reachai/embed-chat` 和 Page Bridge。依次验证签名拒绝、注册心跳、真实同步、带员工身份的只读调用、写确认和 Trace、退出撤销、跨租户拒绝和真实页面；不要把编译、心跳或 mock 响应当作业务验收。
