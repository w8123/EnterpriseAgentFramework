# ReachAI Control Service

`reachai-control-service` 是 ReachAI 的公共 API / BFF 与平台控制面，默认端口 `18603`。它终止平台会话、业务 Embed、SDK 兼容入口和开放协议身份，并通过显式 client 调用 Runtime、Capability、Knowledge、Model owning service。

## 职责边界

本服务拥有：

- 平台用户、角色、权限、登录会话、认证源和本地开发 bootstrap；
- 项目接入工作台、业务页面工作台、Embed Session、Page Bridge 与页面动作目录；
- AI Coding Task Kernel、交接、事件、问题、Artifact 与验收编排；
- Context Governance、个人记忆 canonical 数据和跨域擦除编排；
- 标准 Agent Skill 目录、版本、评审、不可变制品与 Runtime 状态复核；
- Tool ACL、MCP、Gateway、市场聚合、A2A Hub 产品/协议与治理数据；
- 对 Runtime、Capability、Knowledge 的公共 BFF/compatibility surface。

本服务不拥有：

- Agent/Workflow/GraphSpec 执行、Trace 根数据和 EvalOps（Runtime）；
- SDK 能力快照、能力目录、运行时调用投影、扫描资产和 API 市场目录（Capability）；
- 知识库、文件、Chunk、检索与业务索引数据（Knowledge）；
- 模型模板、实例、Chat、Embedding 与 Rerank（Model）。

第一阶段共用 MySQL，但禁止通过 Mapper、MyBatis SQL 或 JdbcTemplate 直接读写其他服务的表。表归属见 [Service Table Ownership](../docs/architecture/service-table-ownership.md)。

## 入口

| 路径族 | 作用 | 实现方式 |
| --- | --- | --- |
| `/api/platform/**` | 平台登录、用户、角色、权限与认证源 | Control 本地实现 |
| `/api/ai-coding*/**`、`/api/ai-assist/**` | AI Coding 协议、项目接入和公开制品 | Control 本地实现 |
| `/api/registry/projects/{projectCode}/page-workbench/**` | 页面地图、改造任务和发布验收 | Control 本地编排，调用 Runtime/Capability |
| `/api/embed/**`、`/embed/**` | Embed Token、会话、对话与 Page Bridge | Control 本地边界，可信执行委托 Runtime |
| `/api/skills/**` | 标准 Agent Skill 目录、导入、评审、发布 | Control 本地实现 |
| `/api/a2a-hub/**`、`/.well-known/agent-card.json`、`/a2a/v1/**` | A2A Hub 管理与 A2A 1.0 协议 | Control 本地实现，执行委托 Runtime |
| `/api/mcp/**`、`/mcp/**` | MCP 互联中心：发布/Client/流水管理 + `tools/list`/`tools/call` 协议 | Control 本地修订驱动治理，`tools/call` 经 internal 委托 Runtime |
| `/gateway/**` | 公开 AI Gateway 目录与 Agent Chat | Control 入口，执行委托 Runtime |
| `/api/agents/**`、`/api/workflows/**`、`/api/runops/**` | Runtime 公共契约 | Control 平台会话边界后委托 Runtime |
| `GET /api/tools/**`、`/api/api-market/**` | Capability 公共契约 | Control 平台会话边界后委托 Capability；能力目录只读 |
| `/api/capability-review/projects/{projectCode}/**` | 能力变更治理 | Control 校验项目级 RBAC 与会话 operator，再用精确 body HMAC 委托 Capability |
| `/api/capabilities/**` | 退役兼容面 | 固定返回 `410 Gone`，不再提供 Kernel 人工 CRUD |
| `/api/knowledge/**` | 受保护的业务索引/文档导入控制台入口 | Control 做会话/RBAC/HMAC，再委托 Knowledge |

完整生命周期以 [Public Route Contracts](../docs/architecture/public-route-contracts.md) 和 [Control API 认证边界矩阵](../docs/architecture/platform-api-auth-matrix.md) 为准。路径在 Control 出现不代表业务数据归 Control。

## 服务依赖

| 变量 | 默认本地目标 | 用途 |
| --- | --- | --- |
| `RUNTIME_SERVICE_URL` | `http://localhost:18604` | Agent/Workflow/RunOps/Eval/可信执行 |
| `CAPABILITY_SERVICE_URL` | `http://localhost:18605` | Registry、Capability、扫描和 API 市场 |
| `KNOWLEDGE_SERVICE_URL` | `http://localhost:18602` | 受保护 Knowledge 管理入口 |
| `MODEL_SERVICE_URL` | `http://localhost:18601` | 模型聚合与 readiness |

服务间可信请求使用 `REACHAI_INTERNAL_SERVICE_SECRET`、nonce、时间窗和 body 摘要。生产还必须配置 `REACHAI_INTERNAL_TRANSPORT_MODE` 对应的 TLS/mTLS 传输边界。

## 主要代码区域

| Package | 责任 |
| --- | --- |
| `control.identity` | 平台身份、会话、RBAC、Provider 和 bootstrap |
| `control.aicoding` / `control.aiassist` | AI Coding Task Kernel 与接入制品 |
| `control.pageworkbench` / `control.platform` | 页面工作台、Embed 与 Page Bridge |
| `control.context` | Context、个人记忆与擦除治理 |
| `control.agentskill` | 标准 Agent Skill 目录、制品和评审 |
| `control.a2a` | A2A Hub domain/application/protocol adapters |
| `control.governance` / `control.market` | Tool ACL、MCP 与市场聚合 |
| `control.compat` / `control.client` | 明确的公共兼容入口和下游 client |

## 配置

`src/main/resources/application.yml` 是配置事实源。主要分组：

- MySQL：`AI_MYSQL_*`；Redis：`REDIS_*`；
- 平台认证：`REACHAI_AUTH_PROVIDER`、`REACHAI_LOCAL_AUTH_ENABLED`、`REACHAI_BOOTSTRAP_ADMIN_*`、`REACHAI_PLATFORM_SESSION_TTL`；
- 内部认证/传输：`REACHAI_INTERNAL_*`；
- A2A：`REACHAI_A2A_*`，生产必须提供独立内容/凭据加密 key；
- Skill 制品：`REACHAI_SKILL_*`，生产多实例必须使用持久共享存储；
- Personal Memory 与擦除 worker：`REACHAI_PERSONAL_MEMORY_*`、`REACHAI_MEMORY_ERASURE_*`。

本地默认账号以及 Embed、AI Coding、内部调用和 Personal Memory 的公开开发值只用于开箱体验，显式环境变量始终优先。`prod` / `production` profile 和 Kubernetes 不会获得这些开发值；生产必须关闭 LOCAL/bootstrap、从 Secret 注入独立密钥，并禁止在配置、日志或 README 中保存真实密钥。

## 构建、启动与验证

```powershell
mvn -pl reachai-control-service -am -DskipTests compile
mvn -pl reachai-control-service -am test
Set-Location reachai-control-service
mvn spring-boot:run
```

健康检查：`GET http://localhost:18603/actuator/health`。

边界验证：

```powershell
node scripts/check-frontend-public-api-routes.mjs
node scripts/check-internal-api-contracts.mjs
node scripts/check-physical-service-route-contracts.mjs
node scripts/check-service-table-ownership.mjs
```

编译/测试只证明源码；数据库升级、运行进程、平台登录和具体业务 E2E 必须分别验证。

## 进一步阅读

- [五服务边界与本地启动](../docs/architecture/service-boundaries.md)
- [AI Coding Task Protocol v1](../docs/architecture/ai-coding-task-protocol-v1.md)
- [业务页面工作台](../docs/architecture/business-page-workbench.md)
- [Agent Skill Center](../docs/architecture/agent-skill-center.md)
- [A2A Hub 技术架构](../docs/architecture/a2a-hub-architecture.md)
