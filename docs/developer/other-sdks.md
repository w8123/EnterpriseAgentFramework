# C#、Python、Node.js、Go 接入

> 适合：业务系统不是 Java 的开发者。读完你会知道你的语言用哪个 SDK、怎么声明业务方法、要自己实现哪两个函数。

## 做完你会得到什么

和 Java 一样：业务系统启动后注册到 ReachAI，你显式登记的业务方法出现在「业务能力」里，AI 以员工身份调用它们。

四种 SDK 遵循同一份 [SDK 接入协议](../contracts/sdk-protocol.md)，概念完全一样，只是写法随语言变化。

## 选哪个 SDK

| 语言 / 框架 | SDK | 版本要求 | 详细说明 |
| --- | --- | --- | --- |
| C# / ASP.NET Core | `ReachAI.AspNetCore`（NuGet） | .NET 10 | [C# SDK](../../reachai-dotnet/README.md) |
| Python / FastAPI | `reachai-fastapi`（wheel） | Python 3.10–3.13，FastAPI 0.115+ | [Python SDK](../../reachai-python/README.md) |
| Node.js / Express | `@reachai/node-sdk`（npm 包） | Node.js 20+，Express 4.18–5.x | [Node.js SDK](../../reachai-node/README.md) |
| Go / net/http | `reachai-go`（源码包） | Go 1.22+ | [Go SDK](../../reachai-go/README.md) |

> 这四个 SDK 都还没有发布到公共仓库，安装包在项目接入工作台下载，下载后先核对页面给出的 SHA-256。它们有完整的协议测试，但**还没有在真实业务系统上验收过**，第一次接入时请预留联调时间。

其他框架（比如 Django、Koa、Gin）暂时没有官方适配。可以先用 [OpenAPI 导入](openapi-import.md) 接入 HTTP 接口。

## 所有 SDK 都要做的四件事

### 1. 配置

每个 SDK 都需要这几项，用环境变量或密钥管理服务提供，不要写进代码仓库：

| 配置 | 含义 |
| --- | --- |
| 注册地址（RegistryUrl） | ReachAI 地址 |
| 项目编码（ProjectCode） | 和平台上一致，全小写 |
| 业务地址（BaseUrl） | ReachAI 能访问到的业务系统地址 |
| 接入令牌（EnrollmentToken） | 只有第一次注册时需要 |
| 凭据存储 | 注册成功后长期凭据保存在哪里。SDK 自带加密文件存储，密钥要和文件分开保管 |
| 允许的业务页面来源 | 要嵌入助手时，业务页面的域名 |

### 2. 显式声明业务方法

每个 SDK 都要求你**明确列出**开放给 AI 的方法，并写清楚输入输出结构。例如「查询订单」：

C#：

```csharp
[ReachCapability("order.query", SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]
public Task<OrderView> QueryAsync([ReachParam("orderId")] string orderId) { ... }
```

Python：

```python
@reach.capability(name='order.query', title='查询订单', side_effect='READ',
                  business_identity_required=True,
                  parameter_names={'order_id': 'orderId'})
async def query_order(order_id: int) -> OrderView: ...
```

Node.js：

```ts
reach.capability({
  name: 'order.query', title: '查询订单', sideEffect: 'READ', businessIdentityRequired: true,
  inputSchema: { type: 'object', properties: { orderId: { type: 'string' } }, required: ['orderId'] },
  outputSchema: { type: 'object', properties: { id: { type: 'string' }, status: { type: 'string' } }, required: ['id', 'status'] },
  handler: async ({ orderId }) => { ... },
})
```

Go：

```go
sdk.RegisterCapability(reachai.Capability{
    Name: "order.query", Title: "查询订单", SideEffect: "READ", BusinessIdentityRequired: true,
    InputSchema:  map[string]any{ /* JSON Schema */ },
    OutputSchema: map[string]any{ /* JSON Schema */ },
    Handler: func(ctx context.Context, args map[string]any) (any, error) { ... },
})
```

`sideEffect` 和 `businessIdentityRequired` 的含义和 Java 相同，见 [Java 接入](java-starter.md#第-3-步声明业务方法)。

输入输出只支持明确的结构：标量、数组、有固定字段的对象。动态字典、递归对象、任意联合类型不支持。

### 3. 实现两个函数

| 函数 | 什么时候被调用 | 你要做的 |
| --- | --- | --- |
| 身份解析（PrincipalResolver） | 业务页面上的助手换令牌时 | 从业务系统**服务端已验证**的登录态里取出用户、租户、角色 |
| 身份桥（SecurityBridge） | AI 调用业务方法时 | 把平台传来的员工身份还原成业务系统的登录上下文，再执行方法 |

这两个函数是安全的关键，原则见 [业务身份桥](business-identity.md)。SDK 示例里它们都是抛异常的占位，**必须**换成你系统的真实实现。

### 4. 挂载路由，启动注册

- C#：`app.MapReachAI()`
- Python：`reach.mount(app)`
- Node.js：`app.use(createReachRouter(reach))`，监听后调用 `reach.start()`
- Go：`mux.Handle("/reachai/", sdk.Handler())`

挂载的 `/reachai/**` 路径由 SDK 验签保护，业务系统的登录中间件要放行它。

## 多实例部署要注意

- 一次性令牌只能用一次。多个进程或副本时，先让**一个**实例完成注册，其他实例共用同一份受保护的凭据。
- 签名回调的防重放默认只在单进程内有效。多进程、多副本时，按各 SDK 文档注入共享的 nonce 存储。
- 业务系统用户退出时，调用 SDK 的「撤销业务会话」接口，失败要可靠重试。

## 核对结果

启动后回 ReachAI，项目接入工作台里「后端 SDK」「业务服务校验」变绿，「业务能力」里出现你声明的方法。见 [核对业务能力](capabilities.md)。

## 遇到问题

**注册时提示项目编码不合法。** 编码必须和平台完全一致，全小写，只能有字母、数字、下划线和连字符。

**注册响应丢了，再启动报令牌已使用。** 不要反复重试一次性令牌。到项目详情核对实例是否已注册，重新生成令牌后再注册。

更多见 [开发者常见问题](troubleshooting.md)。

## 下一步

- [业务身份桥](business-identity.md)
- [核对业务能力](capabilities.md)
