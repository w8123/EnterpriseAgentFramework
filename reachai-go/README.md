# Go 业务系统接入

Go SDK 使用标准库 `net/http`，可接入原生 HTTP 服务，也可由支持 `http.Handler` 的框架挂载。SDK 遵循平台文档中心的“业务系统 SDK 接入协议”，提供显式业务方法、项目注册/心跳、签名回调、调用身份、Embed Token 和业务索引；业务 HTTP API 使用平台 OpenAPI 导入，不伪报 Spring MVC 来源。

源码包由平台提供，未要求模块已发布到公共仓库：下载 ZIP 并核验平台给出的 SHA-256，解压到业务仓库的 `third_party/reachai-go`，在业务模块执行 `go mod edit -require=github.com/w8123/EnterpriseAgentFramework/reachai-go@v1.0.0` 和 `go mod edit -replace=github.com/w8123/EnterpriseAgentFramework/reachai-go=./third_party/reachai-go`。执行 Go 命令的工作目录应使 replace 路径指向实际解压目录。SDK 使用标准库，无其他 Go 模块依赖；需要 Go 1.22 或以上。

```go
package main

import (
    "context"
    "encoding/base64"
    "errors"
    "log"
    "net"
    "net/http"
    "os"
    "os/signal"
    "time"

    reachai "github.com/w8123/EnterpriseAgentFramework/reachai-go"
)

func main() {
    key, err := base64.StdEncoding.DecodeString(os.Getenv("REACHAI_CREDENTIAL_KEY"))
    if err != nil { log.Fatal(err) }
    store, err := reachai.NewEncryptedFileCredentialStore(os.Getenv("REACHAI_CREDENTIAL_FILE"), key)
    if err != nil { log.Fatal(err) }
    sdk, err := reachai.New(reachai.Options{
        RegistryURL: os.Getenv("REACHAI_REGISTRY_URL"),
        ProjectCode: os.Getenv("REACHAI_PROJECT_CODE"),
        BaseURL: os.Getenv("REACHAI_BASE_URL"),
        EnrollmentToken: os.Getenv("REACHAI_ENROLLMENT_TOKEN"),
        AllowedOrigins: []string{os.Getenv("REACHAI_BUSINESS_ORIGIN")},
    }, reachai.Dependencies{
        CredentialStore: store,
        PrincipalResolver: func(r *http.Request, agentID string) (*reachai.EmbedPrincipal, error) {
            // 从业务服务端已验证登录态解析用户、租户、角色和业务会话。
            return nil, errors.New("请实现业务登录态解析")
        },
        SecurityBridge: func(ctx context.Context, identity reachai.Invocation, next func(context.Context) (any, error)) (any, error) {
            // 核验身份并建立业务上下文；业务方法仍须验证行级权限。
            return nil, errors.New("请实现业务身份与权限校验")
        },
    })
    if err != nil { log.Fatal(err) }
    err = sdk.RegisterCapability(reachai.Capability{
        Name: "order.query", Title: "查询订单", SideEffect: "READ", BusinessIdentityRequired: true,
        InputSchema: map[string]any{"type":"object", "properties":map[string]any{"orderId":map[string]any{"type":"string"}}, "required":[]string{"orderId"}},
        OutputSchema: map[string]any{"type":"object", "properties":map[string]any{"id":map[string]any{"type":"string"}}, "required":[]string{"id"}},
        Handler: func(ctx context.Context, args map[string]any) (any, error) {
            _, _ = reachai.CurrentInvocation(ctx)
            return nil, errors.New("请查询已授权的订单")
        },
    })
    if err != nil { log.Fatal(err) }
    mux := http.NewServeMux()
    // 业务自己的认证及 CSRF 中间件负责保护 Embed broker。
    mux.Handle("/reachai/", sdk.Handler())
    listener, err := net.Listen("tcp", ":3000")
    if err != nil { log.Fatal(err) }
    server := &http.Server{Handler:mux, ReadHeaderTimeout:10*time.Second}
    go func(){ _ = server.Serve(listener) }()
    appCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
    defer stop()
    if err = sdk.Start(appCtx); err != nil { _ = server.Close(); log.Fatal(err) }
    <-appCtx.Done()
    shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()
    if err = sdk.Stop(shutdownCtx); err != nil { log.Printf("ReachAI 下线未确认: %v", err) }
    _ = server.Shutdown(shutdownCtx)
}
```

上述两个业务回调必须替换为真实实现；示例本身故意拒绝业务访问。`Start` 的 context 应覆盖应用生命周期，而不是短时 HTTP 请求。关闭时使用新的有界 context 调用 `Stop`；SDK 等待已发送的心跳结束后再标记离线，超时/失败由宿主按实际状态处理。

首次注册使用一次性 token 和持久化 `CredentialStore`。文件存储使用 AES-256-GCM，密钥为来自独立秘密管理服务的 32 字节；文件目录须限制运行账号访问，Windows ACL 由宿主配置。发送注册前先持久化 pending 标记，响应丢失或存储失败不自动重用 token。先核实平台状态，再按受控流程恢复。多实例应预先由单个实例注册并安全分发凭据；默认回调 nonce 存储只覆盖单进程，多进程需注入共享原子且故障时拒绝的 `NonceStore`。

只同步显式登记的能力；确认完整清单为空时须调用 `MarkEmptyInventoryComplete`。输入、输出契约使用明确的 JSON Schema 子集：`type`、`properties`、`required`、`items`、`additionalProperties`、`enum`、`title`、`description`；支持标量、可空值、数组与固定字段对象。未知输入字段会被拒绝，`$ref`、递归、`format`、组合类型及未列出的约束不支持，会在注册时拒绝。整数限制为有符号 64 位范围；传给 Handler 的 JSON 数字为 `json.Number`，由业务方法按类型解析。成功响应保留业务返回结构；Handler 错误返回非 2xx。

`CurrentInvocation(ctx)` 返回经签名验证的请求作用域身份副本。宿主必须自己核验该用户、租户、角色和每条业务记录权限，不能信任浏览器字段或把平台用户视为业务管理员。Embed broker 要求来源在 `AllowedOrigins`，并从宿主 `PrincipalResolver` 获取真实登录态；业务退出时调用 `RevokeBusinessSession`，失败须按业务策略可靠处理。

`IndexUpsert`、`IndexBatch`、`IndexDelete` 对实际 UTF-8 正文签名且不自动重试。回源方法须设置 `BusinessIndexResolver: true` 和业务身份，输入为唯一必需的 `request` 对象，其中 `resourceType`、`resourceId`、`expectedSourceVersion` 为必需字符串；从业务数据库读取实际行并验证用户可见性后，用 `AuthorizeIndexRow` 和 `NewIndexResolution` 构造结果，SDK 校验调用用户、租户、项目和资源范围。

平台可能复用相同清单的原快照，因此同步响应的 `syncId` 可以是旧快照编号，不能当成本次请求编号的回执。SDK 校验响应项目、非空快照编号和接收数量；来源接纳状态仍应在平台核对。仓库中执行 `go test ./...` 可运行共享签名向量和隔离测试；它们不代替实际业务和目标环境验收。
