# 身份与安全

## 凭证域

| 主体 | 身份证明 | 边界 |
| --- | --- | --- |
| 平台管理用户 | Control 登录会话；浏览器传输为 HttpOnly Cookie | 平台权限码与 GLOBAL / PROJECT 作用域 |
| 业务系统应用 | Enrollment 后的项目凭据、精确 body HMAC、时间窗和 nonce | 项目注册、同步、回调及受签名业务入口 |
| 业务终端用户 | 业务后端从登录态产生的 Embed / 调用身份 | 真实租户、外部用户与业务角色 |
| 员工 AI 工具 | 绑定员工和允许系统的个人连接 | 当前员工状态、业务角色、服务发布范围与写审批 |
| AI Coding 客户端 | 项目 AI Coding Key 或特定任务的 Task Token | 项目级 API 与任务级协议分别校验 |
| MCP / A2A 客户端 | 各自协议凭证与受治理 Principal | 固定发布版本、范围、信任与审计 |
| 内部服务 | 受签名的服务身份与执行信封 | 调用方、资源、真实操作者、body 与防重放校验 |

凭证不可跨域替用；浏览器字段、URL、模型输出和普通 metadata 均不能提升可信身份。明文凭据不进入日志、Trace、仓库、截图或任务产物。

## 平台登录

同一 Origin 的窗口与标签页共享服务端会话 Cookie，前端先调用 `/api/platform/auth/me` 恢复身份。JavaScript 不读取会话凭据，也不将它保存到 localStorage 或 sessionStorage；非只读 Cookie 请求按 [PlatformConsoleAuthInterceptor](../reachai-control-service/src/main/java/com/enterprise/ai/control/identity/PlatformConsoleAuthInterceptor.java)发送 `X-ReachAI-CSRF`。

```text
初始化 → /me
  有效会话 → 当前会话未确认欢迎提示则先确认 → 应用
  明确失效 → 登录
  网络故障 / 超时 / 服务不可用 → 不可用提示与重试
```

不可用不等于退出登录；欢迎确认不能让受保护页面提前挂载和读取。退出、停用或撤销会话由服务端立即生效，其他标签页下次检查恢复到相应状态。

## 平台角色与业务权限

| 平台角色 | 主要工作 |
| --- | --- |
| DEVELOPER | 按授权项目接入系统、维护业务方法/API、查看运行 |
| OPERATOR | 编排、调试、发布 Agent / Workflow，管理运行和员工接入 |
| MANAGER | 查看使用与治理结果，执行被授予的审批、断开连接等操作 |
| PLATFORM_ADMIN | 平台、账号、资源与全局配置 |

角色是权限模板，后端始终按权限码与作用域授权，不按角色名绕过校验。前端路由和按钮投影同一权限事实；隐藏按钮不能代替服务端检查。精确规则从 [PlatformPermissions](../reachai-control-service/src/main/java/com/enterprise/ai/control/identity/PlatformPermissions.java)与 [PlatformConsolePermissionPolicy](../reachai-control-service/src/main/java/com/enterprise/ai/control/identity/PlatformConsolePermissionPolicy.java)读取。

平台操作权限与终端用户调用权限分开。Console 使用已核实的项目测试身份，员工调用使用本人的业务身份；都不能伪造另一套角色。管理员变更保持最后一个有效全局管理员不变量，失败的角色替换整体回滚。

## 执行保护

- ACL 只有明确 ALLOW 才能执行；未知、查询失败或跳过不能当作允许，DENY 优先。
- READ、WRITE、页面动作和不可逆操作按当前入口策略处理；每次写确认绑定实际目标、输入、版本和身份。
- 非幂等操作不因超时、响应丢失或断流自动重发。查询原执行记录不能制造新副作用。
- 内部 HMAC 绑定真实发送字节，使用持久化 nonce 防重放；容量或配置不足时失败关闭。
- 外部 HTTP、下载与重定向遵守出站策略，重新核对解析后的目标；不能因域名最初合法而绕过 SSRF 边界。
- Skill、模型输出、页面上下文、Workspace 与远端 Agent 都是不可信内容，不取得数据库、凭据或执行权限。

## 托管执行隔离

Managed Executor 的 Workspace、Worker 与 Runtime 控制面分离。生产验收覆盖非 root、只读根文件系统、能力限制、seccomp、资源与 wall-time 上限、出站白名单、凭据 Broker、短期作用域、Artifact 留存及跨租户隔离。执行容器不持有平台主密钥、数据库凭据或 Kubernetes 管理权限。

mTLS、轮换、WAF 或其他基础设施按目标环境治理，不因为文档整理自动扩成新的实现任务。部署准入与恢复见[运维](operations.md)。
