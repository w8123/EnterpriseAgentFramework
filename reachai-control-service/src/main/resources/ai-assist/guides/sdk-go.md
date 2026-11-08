# Go（net/http）后端接入

> 验证情况：按 SDK 契约编写，尚未在真实项目验证。

## 适用范围

Go 1.22+，net/http（Gin、Echo 等基于 net/http 的框架也可挂载 Handler）。

## 准备

1. 调用 `reachai_onboarding_resources` 获取所有语言的 `sdkArtifacts` 和 `skillPackageUrl`，选择当前语言制品。注册地址使用 AI 所连接的 ReachAI 平台根地址 `{{reachaiUrl}}`，从业务服务确认该地址可达。首次接入先确认项目编码「{{projectCode}}」，用 `reachai_onboarding_enrollment_request` 申请并由本人在「AI 操作审核」批准，再用 `reachai_onboarding_enrollment_execute` 获取一次性令牌。注册成功后项目出现在工作台。
2. 所有密钥（Enrollment Token、AppKey / AppSecret、凭据加密密钥）只放环境变量或密钥管理器，不写进代码、配置文件、日志和浏览器。
3. ReachAI 的制品地址不是 Maven / NuGet / npm / pip / Go 模块仓库，按清单里的 `downloadUrl` 下载并校验 `integritySha256`，不要猜仓库地址。

## 步骤

1. 从 `sdkArtifacts` 选择 Go 制品，下载 `reachai-go` 源码 ZIP 并校验 SHA-256，解压到业务仓库稳定目录（如 `third_party/reachai-go`），按清单的 `go mod edit -replace` 命令设置本地 replace。
2. 在服务端配置项目编码 `{{projectCode}}`、注册地址（连接 ReachAI 的平台根地址）、业务可达地址，将环境变量 `REACHAI_ENROLLMENT_TOKEN` 传给 `Options.EnrollmentToken` 并配置应用包外的受保护持久凭据存储；明确声明能力及输入输出结构，实现身份解析和身份桥，把 SDK 的 Handler 挂到路由上。
3. 首次启动只注册和心跳；在 ReachAI 里明确触发 SDK 同步。
4. 多实例部署提供共享原子 nonce 存储。详细 API 以 SDK 包内 README 和 reachai-onboarding Skill 的 `references/go-sdk-access.md` 为准。

## 自检

- [ ] 业务系统启动日志里能看到注册成功，ReachAI 项目详情里实例在线。
- [ ] 在 ReachAI 里触发一次 SDK 同步（项目接入工作台，或让 AI 用 `reachai_ai_coding_task_verify` 做 SDK_SYNC），业务方法出现在能力目录里。
- [ ] 用一个只读业务方法试调用成功，结果和业务系统页面上一致。
- [ ] 换一个权限更低的账号，看不到 / 调不了没有权限的数据。
- [ ] 让 AI 调用 `reachai_doctor`（projectCode="{{projectCode}}"），「业务系统 SDK 实例」为 PASS。

## 常见问题

- **注册失败 401 / 403**：注册凭据不对或已用过；一次性 Enrollment Token 只能用一次，注册结果不确定时不要自动重用。
- **ReachAI 调不到业务系统**：`BaseUrl` 必须是 ReachAI 服务器能访问到的地址，经网关时转发 `/reachai/**` 并保留签名请求头。
- **能力没出现**：首次启动只注册和心跳，不会自动同步；要在 ReachAI 里明确触发 SDK 同步。
- **普通 HTTP 接口**：不是 SDK 能力的接口用 HTTP API 导入（OpenAPI / curl），不要伪装成 SDK 能力。
