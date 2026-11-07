# Go net/http 接入

请求项目接入清单时选择 `language=go`。从 `sdkArtifacts` 选择 `language=go`、`packageName=github.com/w8123/EnterpriseAgentFramework/reachai-go`，下载清单提供的源码 ZIP，核验 `integritySha256` 后解压到业务工程稳定目录，例如 `third_party/reachai-go`。通过清单的 `go mod edit` 命令设置 v1.0.0 的本地 `replace`；平台的制品地址不是 Go 模块代理，不依赖公网上存在该模块。不要把临时目录或其他机器的绝对路径写入 go.mod。

Go SDK 只在宿主业务服务端运行。配置项目、平台 URL、业务可达 URL 和受保护的持久凭据；一次性令牌、密钥及凭据加密密钥通过秘密管理服务提供，不写入源码、日志或浏览器。明确声明业务能力及其输入输出结构，从业务系统已验证的会话解析用户、租户和角色，通过身份桥将受验证调用关联到请求上下文，并在真实业务逻辑中校验数据权限与事务。

首次启动负责注册和心跳，不应自动扫描或同步业务接口；平台明确触发 SDK 签名回调后同步完整能力清单。业务 HTTP API 使用平台 OpenAPI 导入，不伪报 Spring MVC 来源。业务退出调用 SDK 的会话撤销接口；失败后的可靠重试由宿主负责。多实例部署须提供共享原子 nonce 防重放存储，并使凭据及业务会话适配现有安全基础设施。详细 Go API、允许类型和恢复条件以 SDK 包内 README 为准。

浏览器继续使用平台的 Embed Chat 和 Page Bridge，token 必须从本业务系统 broker 获取。平台项目级自检不能证明回调或 Embed 会话来自 Go 实例，应分别验证签名、角色、跨用户数据隔离、业务读写、Workflow、MCP 和真实浏览器页面行为。
