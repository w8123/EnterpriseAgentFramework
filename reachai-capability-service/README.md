# ReachAI Capability Service

`reachai-capability-service` 是 ReachAI Capability Catalog 部署单元，默认端口 `18605`。它拥有业务系统注册、能力快照与评审、扫描目录、能力资产、语义/API 图谱、运行时调用定义和外部 API 市场目录。

## 职责边界

本服务拥有：

- 项目 Enrollment、项目凭据验证、实例注册/心跳和 SDK 能力同步；
- Capability 快照、字段级 diff、review/apply/ignore 与回滚所需状态；
- 扫描项目、模块、接口资产、语义文档和 API 图谱快照；
- Capability Kernel、组合、领域归属，以及 `capability_tool_definition` 运行时调用投影；
- 调用候选检索管理和 Runtime Tool/Capability internal execution；
- API 市场的来源、提供方、条目、不可变版本、Operation、验证证据和项目接入意图。

本服务不保存第三方 API 调用凭据，不执行 Workflow，不拥有 Agent/Trace，也不拥有 Knowledge/Model 数据。历史扫描器底层实现仍可通过 Knowledge internal API 复用，但扫描目录和资产落库归 Capability。

## 公共与内部接口

管理端统一通过 Control 访问下列 public contract：

| 路径族 | 用途 |
| --- | --- |
| `/api/registry/projects/**` | 项目、实例、能力快照、diff/review 与 Enrollment 兼容契约 |
| `/api/scan-projects/**`、`/api/scan-modules/**` | 扫描项目与接口目录 |
| `/api/semantic-docs/**`、`/api/api-graph/**` | 语义文档与 API 图谱 |
| `/api/tools/**`、`/api/capabilities/**` | 能力目录、Kernel 与兼容的运行时调用投影 |
| `/api/tool-retrieval/**` | 调用候选召回与索引管理 |
| `/api/domains/**` | 领域定义与资产归属 |
| `/api/api-market/**` | 外部 API 发现、详情、统计和项目接入 |
| `/internal/capability/**` | Control/Runtime 的 HMAC internal contract |

`compatibility` 只表示公共路径形状仍受支持，不表示回退到旧后端。

## 下游依赖

| 变量 | 默认本地目标 | 用途 |
| --- | --- | --- |
| `RUNTIME_SERVICE_URL` | `http://localhost:18604` | Runtime-owned 发布/执行相关验证 |
| `KNOWLEDGE_SERVICE_URL` | `http://localhost:18602` | OpenAPI/Controller 扫描等 Knowledge 实现 |
| `MODEL_SERVICE_URL` | `http://localhost:18601` | 语义生成或模型相关能力 |

Runtime 通过 Capability internal API 获取/执行能力；不得直接读取 Capability 表。

## 主要代码区域

| Package | 责任 |
| --- | --- |
| `capability.registry` | Enrollment、注册、心跳、同步和项目凭据 |
| `capability.catalog.tool` | 兼容的运行时调用投影 |
| `capability.catalog.scan` | 扫描项目/模块/接口目录 |
| `capability.catalog.semantic` | 语义文档 |
| `capability.catalog.graph` | API 图谱快照 |
| `capability.catalog.retrieval` | 调用候选检索 |
| `agent.capability.catalog` | Capability Kernel 与 Domain |
| `capability.externalapi` | API 市场目录和项目接入 |
| `capability.internal` | Runtime/Control internal contract |

## 配置

配置事实源是 `src/main/resources/application.yml`：

- `SERVER_PORT`（默认 `18605`）；
- `AI_MYSQL_*`；
- `RUNTIME_SERVICE_URL`、`KNOWLEDGE_SERVICE_URL`、`MODEL_SERVICE_URL`；
- `REACHAI_INTERNAL_SERVICE_SECRET`、`REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS`、`REACHAI_INTERNAL_TRANSPORT_MODE`。

默认本地模式会提供公开的内部调用开发值，便于开箱体验；显式配置优先，生产 profile 与 Kubernetes 环境必须从 Secret 注入。

Registry/项目请求的身份来自签名、Enrollment 或 Control 已验证主体；浏览器字段不能提升为可信项目身份。

## 构建、启动与验证

```powershell
mvn -pl reachai-capability-service -am -DskipTests compile
mvn -pl reachai-capability-service -am test
Set-Location reachai-capability-service
mvn spring-boot:run
```

健康检查：`GET http://localhost:18605/actuator/health`。

```powershell
node scripts/check-service-table-ownership.mjs
node scripts/check-internal-api-contracts.mjs
node scripts/check-physical-service-route-contracts.mjs
```

API 市场和其他新增 schema 必须同时进入 `sql/initV2.sql`、对应 `upgrade-*.sql` 和 ownership 矩阵；SQL 存在不等于目标库已执行。

## 进一步阅读

- [项目注册与能力资产](../docs/02-项目注册与能力资产.md)
- [API 市场产品与架构契约](../docs/architecture/api-market.md)
- [Public Route Contracts](../docs/architecture/public-route-contracts.md)
- [Service Table Ownership](../docs/architecture/service-table-ownership.md)
