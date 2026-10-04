# 平台授权与工作区底座

> 状态：P3_ENTERPRISE_IAM_SOURCE_IMPLEMENTED / DATABASE_AND_BROWSER_E2E_PENDING<br>
> 更新：2026-08-27

本文定义 ReachAI 管理端后续功能必须共同遵守的身份、角色、权限、作用域和工作区边界。它解决的是“谁以什么身份，在什么范围内，可以做什么”，不把开发者、管理者和业务用户重新拆成三套后端。

## 1. 架构结论

1. 后端继续按业务域和数据所有权拆分为 Control、Runtime、Capability、Knowledge、Model，不按人物角色拆服务。
2. 平台角色是可分配的权限模板。Controller、策略和前端路由只依赖稳定权限码，不依赖 `PLATFORM_ADMIN`、`AGENT_DESIGNER` 等角色名。
3. 建设中心与运营治理中心是同一管理端的两个工作区。工作区切换只改变产品上下文和导航投影，不能切换身份、扩大权限或绕过后端授权。
4. 业务终端用户属于业务系统身份域，不是平台角色。业务用户目录用于跨系统身份映射与治理，不自动产生平台登录账号。
5. 菜单隐藏不是安全边界。前端菜单、深链路由和后端 API 必须使用同一权限码；最终安全判定始终在后端。

## 2. 身份域

| 身份域 | 典型主体 | 登录或凭证 | 可以成为平台角色吗 |
| --- | --- | --- | --- |
| 平台人员 | 超级管理员、开发者、项目负责人、运维、审计 | Control 平台会话 | 可以 |
| 业务终端用户 | 被接入业务系统中的员工或客户 | 业务登录态换取的 Embed Token | 不可以 |
| 业务应用 | Starter、SDK、业务后端 | 项目签名或 Enrollment Token | 不可以 |
| 协议客户端 | MCP、A2A、AI Coding、Task client | 各协议独立凭证 | 不可以 |
| 内部服务 | Control、Runtime、Capability、Knowledge、Model | 内部服务 HMAC / mTLS 边界 | 不可以 |

同一个自然人可以同时出现在“平台人员”和“业务终端用户”两个身份域，但必须拥有两份独立身份记录和凭证链，不能根据用户名相同自动合并权限。

## 3. 授权模型

授权事实由以下关系组成：

```text
平台用户 -> 角色绑定(scopeType, scopeValue) -> 角色 -> 权限码
```

权限码采用稳定的 `resource:action` 形式。授权求值器的作用域语义为：

| scopeType | scopeValue | 含义 |
| --- | --- | --- |
| `GLOBAL` | `*` | 对该权限覆盖的全部平台资源有效 |
| `WORKSPACE` | workspace id | 仅覆盖指定资源工作空间及其项目 |
| `PROJECT` | project code | 仅覆盖指定项目 |

这里的资源 `WORKSPACE` 作用域与产品界面的“建设中心 / 运营治理中心”不是同一概念。前者限制数据范围，后者组织用户任务；不能把 `BUILD` 或 `OPERATE` 当成数据授权范围。

企业账号与角色管理 API 和页面只开放 `GLOBAL`、`PROJECT` 两种可分配作用域；`WORKSPACE` 求值能力用于兼容已经由资源策略解析出的授权，但在建立 canonical workspace 目录与选择器前，不开放人工新建，避免管理员填写无法验证的任意字符串。

每次受保护操作按以下顺序判定：

1. 验证平台会话，失败返回 401。
2. 验证目标权限码，失败返回 403，不能清除有效登录会话。
3. 对项目或资源操作验证 scope，拒绝越过 workspace/project 边界。
4. 执行业务不变量，例如最后一个全局管理员不能被移除。
5. 对身份、凭证、授权等高风险写操作记录审计事件。

## 4. 系统角色模板

系统角色用于首次安装和常见分工，不是硬编码的唯一角色集合。后续可以增加自定义角色而不改业务代码。

| 系统角色 | 产品含义 | 默认工作区 | 高风险身份治理 |
| --- | --- | --- | --- |
| `PLATFORM_ADMIN` | 平台超级管理员 | 建设 + 运营治理 | 全局允许；当前包含 `*`，生产应限制人数并保留审计 |
| `AGENT_DESIGNER` | Agent / Workflow 开发者 | 建设 | 不允许 |
| `PROJECT_OWNER` | 项目负责人、业务管理者 | 建设 + 运营治理 | 不允许跨项目治理业务身份 |
| `OPERATOR` | 运行与治理人员 | 运营治理 | 不允许 |
| `AUDITOR` | 只读审计人员 | 运营治理 | 不允许修改 |

“用户”不应新增成一个笼统平台角色。若指业务终端用户，应留在业务身份域；若指只读平台成员，应通过只读权限组合成平台角色。

### 企业账号与角色管理闭环

`/settings/platform-users` 已升级为“账号与权限”工作台，并与 `PlatformAccountRoleController` 形成以下源码闭环：

- 账号目录支持检索、来源与状态筛选、本地账号创建、资料编辑、停用、强密码重置和全部会话撤销；不提供物理删除，保留授权和审计引用。
- 新账号默认零权限，管理员必须显式授予角色；密码为 12–128 位，至少满足大写、小写、数字、符号中的三类。
- 停用账号会撤销全部会话；当前账号不能停用自己；最后一个 ACTIVE 全局 `PLATFORM_ADMIN` 不能被停用或移除管理员授权。
- 一个角色可以在一条编辑记录中选择多个项目，保存时展开为多条 canonical `projectCode` 授权；同一角色不能同时持有 GLOBAL 与 PROJECT 绑定。
- `control_platform_role.role_kind` 将角色区分为 `SYSTEM` 与 `CUSTOM`。五个系统角色由基线和升级 SQL 维护、界面只读；自定义角色可以维护名称、说明、状态和权限清单。
- 自定义角色不能持有 `*` 或 `platform:admin`，ACTIVE 自定义角色至少拥有一项权限；停用已有绑定的角色要求管理员确认影响。
- 账号、密码、会话、角色和授权变更都写入 `control_platform_auth_audit_event`；页面只展示脱敏结构化摘要，不渲染密码、Token、Secret、Credential 或 Provider 配置。

旧 `/api/platform/users`、`/api/platform/roles` 与用户授权读取接口暂时保留为兼容入口；新的写操作统一走 `/api/platform/account-management/**`。两者均要求全局 `platform:admin`。

## 5. 已落地的权限闭环

本阶段新增并落地以下权限：

| 权限码 | 用途 | 默认系统角色 |
| --- | --- | --- |
| `workspace:build:access` | 进入建设中心 | `PLATFORM_ADMIN`、`AGENT_DESIGNER`、`PROJECT_OWNER` |
| `workspace:operate:access` | 进入运营治理中心 | `PLATFORM_ADMIN`、`PROJECT_OWNER`、`OPERATOR`、`AUDITOR` |
| `identity:business-user:read` | 查看业务用户及外部身份绑定 | `PLATFORM_ADMIN` |
| `identity:business-user:manage` | 修改业务用户及外部身份绑定 | `PLATFORM_ADMIN` |

业务用户目录是第一条细粒度纵向切片：

- Control API 的读、写入口分别强制 `identity:business-user:read` 与 `identity:business-user:manage`；
- 前端菜单按读权限过滤；
- 受保护深链按相同权限码拦截，无权限时进入 403 体验页但保持登录；
- 页面按 `manage` 权限降级为只读，资料与身份映射写入在同一事务中记录不含邮箱、手机号和外部用户 ID 的审计元数据；
- `PLATFORM_ADMIN` 的通配权限仍可满足全部检查；
- SQL 基线和升级脚本提供相同的权限及角色模板。

`GET /api/platform/auth/me` 会返回服务端计算后的 `permissionGrants`。前端据此使用与后端一致的 `GLOBAL > WORKSPACE > PROJECT` 求值规则进行导航、深链和页面操作投影；前端结果只改善体验，不能替代后端判定。

P2 已把核心 Runtime 管理面拆为以下稳定权限：

| 领域 | 权限码 | 典型动作 |
| --- | --- | --- |
| Agent | `agent:read/write/debug/evaluate/publish` | 查看、编辑、调试、EvalOps、发布配置版本 |
| Workflow | `workflow:read/write/debug/publish` | 查看、编辑工作副本、调试、发布或回滚版本 |
| Workflow 凭证 | `workflow:credential:manage` | 查看和维护执行凭证；默认仅平台管理员 |
| Automation | `automation:read/write/operate` | 查看、编辑生命周期、运行/重试/取消 occurrence |
| RunOps | `runops:read/operate` | 查看 Trace/诊断/Guard，回放运行 |

这五条纵向切片均遵守同一闭环：

- Control BFF 先验证权限，再用资源返回的 canonical `projectCode` 校验 PROJECT grant；`projectId + projectCode` 同时存在时必须指向同一项目；
- 无项目过滤的跨项目集合、无法从旧兼容资源 ID 解析项目的接口采用 fail-closed：仅 GLOBAL grant 可访问；
- 前端菜单、路由 meta、列表查询项目、按钮和事件处理器使用相同权限码；隐藏按钮之外，处理器仍会再次拒绝；
- 发布权限与写权限分开，读取权限与运行操作权限分开；角色不因“能编辑”自动获得发布或凭证权限。

Capability、Knowledge、Model、Context、MCP 等其它业务域仍有部分粗粒度 `platform:read` / `platform:write` 授权。本阶段不把核心 Runtime 管理面闭环误报为全站资源级 RBAC 已全部完成。

### Capability 目录读取边界

`GET /api/tools` 与 `GET /api/tools/{name}` 是 Control 提供的平台会话兼容入口，由
`CapabilityCatalogConsoleController` 负责读取授权；旧 Capability 通用代理不再透传这两条路径或其写入／未知子路径。

- 无 `projectId` 的列表请求必须拥有 GLOBAL `platform:read`，任一 PROJECT grant 不能放行全目录；带 `projectId` 时，Control 先通过 Capability owner 的签名 by-id 查询确认项目 ID，再用 owner 返回的 canonical `projectCode` 检查该项目的 `platform:read`。
- 单项定义先由 Capability owner 返回真实 `name`、`projectId` 和 `projectCode`，再执行归属授权；`projectId` 与编码冲突返回 `409 CAPABILITY_PROJECT_IDENTITY_MISMATCH`，只有编码而没有 ID 返回 `409 CAPABILITY_PROJECT_IDENTITY_UNCONFIRMED`，不能把未确认归属当作平台级。
- `projectId` 与 `projectCode` 均为空的定义仅允许 GLOBAL `platform:read`；已确认存在但编码为空的项目列表／定义也只允许 GLOBAL grant，不得把缺失编码解释为任意项目授权。
- Control 到 Capability 的目录、项目解析和既有评审调用都复用精确字节 V1 HMAC；查询参数仅允许 `current`、`size`、`keyword`、`source`、`enabled`、`projectId`，并按 URI 规则编码，`enabled=false` 不得丢失。下游响应形状、身份不一致和 owner 不可用均 fail-closed，不返回目录正文。
- 该目录读取授权不等同于能力调用授权；Runtime、MCP、Tool ACL 和执行边界继续由各自 owning service 的协议与策略负责。

## 6. 工作区产品规则

工作区切换器在权限底座稳定后实现，并遵守：

1. 只有一个工作区权限时直接进入该工作区，不显示无意义切换器。
2. 同时拥有两个权限时允许切换，并仅在当前浏览器窗口保存偏好。
3. 工作区只过滤导航和首页任务，不改变平台 session、角色、scope 或后端路由。
4. 收藏或手输 URL 必须再次经过路由权限检查；后端 API 仍独立授权。
5. 某项能力同时服务建设和运营时可以在两个工作区投影，不复制后端资源和权限事实。

## 7. 后续收口顺序

1. 为 Capability、Knowledge、Model、Context、MCP 等剩余业务域建立资源级读、写、发布、运行、审批权限矩阵。
2. 逐个剩余业务域把 Controller 从粗粒度 `platform:read/write` 迁移到稳定权限常量和 scope 检查。
3. 给 Workflow 凭证和 EvalOps 旧兼容 ID 增加 owning Runtime 的安全 parent lookup，再把当前 GLOBAL-only 兼容入口降到精确 PROJECT scope。
4. 在 P3 工作台基础上继续增加权限变更 diff、批量导入导出、组织/用户组、审批流和审计归档策略。
5. 接入 OIDC/SAML/SCIM 后，将账号来源生命周期与企业 IdP 对齐；当前只有 LOCAL 账号具备真实登录和密码重置能力。
6. 最后实现建设 / 运营治理工作区切换器和各自首页，不以它代替前述授权闭环。

## 8. 验收门禁

- 未登录访问受保护入口返回 401；已登录但无权限返回 403，前端不退出登录。
- 菜单不可见、直接 URL 不可达、API 仍拒绝，三层使用同一权限码。
- GLOBAL、WORKSPACE、PROJECT 的允许和拒绝用例均有后端测试。
- `sql/initV2.sql` 与升级 SQL 的权限、角色绑定一致；执行数据库升级后必须回读验证。
- 前端权限测试、Control 目标测试、前端构建和 `git diff --check` 通过。
- 数据库执行、服务重启、真实角色登录和浏览器 E2E 未执行前，只能标记源码完成，不能标记部署完成。

相关凭证边界见 [平台身份与授权模型](../reference/平台身份与授权模型.md) 和 [Control API 认证边界矩阵](./platform-api-auth-matrix.md)。
