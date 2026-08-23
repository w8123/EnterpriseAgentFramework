# 后端物理服务与启动验证

## 当前结论

后端已经从准备拆分推进到第一阶段物理服务落地。当前主路径拓扑是：

| 主路径服务 | 默认端口 | 第一阶段职责 |
| --- | --- | --- |
| `reachai-model-service` | `18601` | Model Gateway：模型中心 V2、Chat、Embedding、Rerank |
| `reachai-knowledge-service` | `18602`；context-path `/ai` | Knowledge / Retrieval；知识库、文件、chunk、RAG、业务索引和向量检索 |
| `reachai-capability-service` | `18605` | SDK 注册、项目实例、能力快照、diff/review/apply、扫描目录和能力资产 |
| `reachai-runtime-service` | `18604` | Agent 入口、Workflow、GraphSpec 运行语义、调试、回放和人工交互 |
| `reachai-control-service` | `18603` | Public API/BFF、身份、ACL、Guard、Gateway、MCP、A2A、Embed、RunOps/Trace 管理面 |

第一阶段保持同一个 MySQL 库，不拆库；公开 `/api/**`、`/embed/**` 和 SDK 注册入口由 `reachai-control-service` 收口。旧 `ai-agent-service` module 已删除，不再作为平台主后端、默认后端模块、本地启动项、IDEA 后端项目或部署单元存在。

## 已具备的拆分基础

- `reachai-control-service`、`reachai-runtime-service`、`reachai-capability-service`、`reachai-knowledge-service`、`reachai-model-service` 已作为默认 Maven reactor 和部署单元存在。
- `reachai-control-service` 保持前端和业务系统可见的 public route 形状，并按路由归属委托到 Runtime 或 Capability。
- Runtime retired-route backlog 已清空；Agent execution、Workflow Studio debug、Debug sessions、Human approvals、RunOps replay、Workflow AI Coding、runtime tool/composition execution 和 interaction resume 已迁入 Runtime-owned implementation。
- Capability retired proxy 已删除；Tool/Composition/Scan/Semantic/Capability mining/Tool retrieval 等公开能力路由已由 Capability-owned implementation 承接。
- Control generic fallback、Platform retired proxy、Runtime legacy proxy gateway、默认 `ai-agent-service` K8s 部署和旧 agent Dockerfile 已退场。
- `LEGACY_AGENT_SERVICE_DISABLED` 不再是当前路由策略；再次出现应视为边界回退。
- `docs/architecture/physical-split-route-ownership.md` 记录公开路由所有权、当前实现状态和同库阶段的临时读取边界。
- `docs/architecture/internal-api-contracts.md` 记录服务间 internal API 的 owner、consumer、用途和前端禁用边界。

## 第一阶段仍需保持的兼容边界

- 不拆 MySQL 物理库，但必须按服务声明表所有权。
- 不重命名 SQL 表或 `skill_*` 兼容敏感存储名。
- 不改变前端 public route、SDK 注册 URL、HTTP method 或 public JSON contract。
- 不让前端直接访问 `reachai-runtime-service` 或 `reachai-capability-service`。
- 默认 Maven/IDEA 导入、默认 K8s、默认本地启动都不再包含旧 `ai-agent-service`。
- `reachai-model-service`、`reachai-knowledge-service` 的目录名和 Maven artifactId 已收口到当前物理服务名。

## 后续拆库前准备事项

1. 用 owner-service API 替代已记录的同库 cross-service reads，为未来拆库做准备。
2. 继续收紧 Control 到 Runtime/Capability 的 service API 边界，避免为了编译或查询直接穿透到对方实现或表。
3. 保持 README、部署和本地启动说明只包含五个当前物理服务。
4. 对保留 `compat` 命名的控制器逐项确认：它们只能表示 public route compatibility surface，不能重新引入旧 agent fallback。
5. 按业务价值逐步把兼容命名收敛为更清晰的 Control/Runtime/Capability facade 命名，但不改变 public path 和 JSON contract。

## 本地启动顺序

仓库根目录 `.run/` 已提供 IDEA 共享 Run Configurations，可使用 `00 ReachAI Five Services` 一键启动五个服务，也可以按启动顺序单独启动：

1. `01 ReachAI Model Service`
2. `02 ReachAI Knowledge Service`
3. `03 ReachAI Capability Service`
4. `04 ReachAI Runtime Service`
5. `05 ReachAI Control Service`

这些配置只绑定 Spring Boot 主类和 Maven module，不包含本机数据库密码或 token。IDEA 重新加载 Maven 项目后，可以按编号启动，然后运行 live smoke。

源码默认启用 LOCAL 平台登录；首次启动 Control 且数据库没有全局管理员时，会创建本地开发账号 `admin / admin123`，登录页会自动填充。生产部署必须设置 `REACHAI_LOCAL_AUTH_ENABLED=false`、`REACHAI_BOOTSTRAP_ADMIN_ENABLED=false`，或者在首次启动前用环境变量替换默认 bootstrap 凭据。

1. `reachai-model-service`：`18601`
2. `reachai-knowledge-service`：`18602`，context-path `/ai`
3. `reachai-capability-service`：`18605`
4. `reachai-runtime-service`：`18604`
5. `reachai-control-service`：`18603`

新服务应在 IDEA 中作为 Spring Boot 后端项目识别并单独启动。开发前端时，`/api` 只指向 `reachai-control-service:18603`，`/ai` 指向 `reachai-knowledge-service:18602/ai`，`/model` 指向 `reachai-model-service:18601`。

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `AI_MYSQL_HOST`、`AI_MYSQL_PORT`、`AI_MYSQL_DATABASE`、`AI_MYSQL_URL`、`AI_MYSQL_USER`、`AI_MYSQL_PASSWORD` | 共享 MySQL 连接。仓库默认端口为 `3306`；个人机器需要其它端口时，用本地环境变量覆盖，不要写入仓库。 |
| `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` | Redis |
| `MILVUS_HOST`、`MILVUS_PORT` | Milvus |
| `MODEL_SERVICE_URL` | 默认 `http://localhost:18601` |
| `KNOWLEDGE_SERVICE_URL` | 默认 `http://localhost:18602` |
| `CAPABILITY_SERVICE_URL` | 默认 `http://localhost:18605` |
| `RUNTIME_SERVICE_URL` | 默认 `http://localhost:18604` |
| `REACHAI_LOCAL_AUTH_ENABLED` | 开源本地默认 `true`；生产必须显式设为 `false`，除非有意保留 LOCAL 登录。 |
| `REACHAI_BOOTSTRAP_ADMIN_ENABLED` | 本地默认 `true`；仅在没有 ACTIVE 全局管理员时创建一次账号，生产必须显式关闭。 |
| `REACHAI_BOOTSTRAP_ADMIN_USERNAME`、`REACHAI_BOOTSTRAP_ADMIN_PASSWORD` | 覆盖本地默认管理员；自定义密码至少 12 位，不得提交到仓库。 |
| `REACHAI_INTERNAL_TRANSPORT_MODE` | 开发默认 `DEVELOPMENT_PLAINTEXT`。prod/production 必须显式使用 `DIRECT_TLS`（全部内部和动态 Capability URL 为 HTTPS）或 `MTLS_MESH`（由部署侧 mTLS mesh 保护 sidecar hop）。 |

`MTLS_MESH` 只是应用侧的显式部署声明，不会自动建立 mTLS。生产验收仍须核对证书、mesh policy、NetworkPolicy/安全组和链路抓包；HMAC 不能替代传输加密。

## 验证入口

每批拆分至少运行：

```powershell
node scripts/check-backend-domain-dependencies.test.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-backend-boundary-naming.mjs
node scripts/check-internal-api-contracts.test.mjs
node scripts/check-internal-api-contracts.mjs
node scripts/check-physical-service-smoke.test.mjs
node scripts/check-physical-service-route-contracts.test.mjs
node scripts/check-physical-service-route-contracts.mjs
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
git diff --check
```

五个服务已经通过 IDEA 或命令行启动后，再运行一次 live smoke：

```powershell
node scripts/check-local-service-artifact-freshness.test.mjs
node scripts/check-local-service-artifact-freshness.mjs --check-running
$env:REACHAI_PLATFORM_SESSION_TOKEN = '<登录 ReachAI 管理端后取得的平台会话令牌>'
node scripts/check-physical-service-smoke.mjs --wait-ms 120000 --interval-ms 3000
Remove-Item Env:REACHAI_PLATFORM_SESSION_TOKEN
```

第一条真实检查会拒绝 `src/main` 晚于可部署 JAR 的模块；Windows 下的 `--check-running`
还会确认 18601-18605 的监听进程使用预期 JAR，并且是在该 JAR 构建完成之后启动。不能只凭
`UP` 判断当前源码已部署；artifact freshness、监听进程、接口 smoke 和业务 E2E 是四层不同证据。
冒烟脚本对五个服务的直接健康接口做无凭证检查；Control 的聚合接口
`/api/internal-services/health` 属于管理端路由，因此必须通过 `REACHAI_PLATFORM_SESSION_TOKEN`
显式提供当前平台会话。脚本不会输出该令牌。

旧 `ai-agent-service` module 已删除，不再提供历史 fallback 编译入口。
