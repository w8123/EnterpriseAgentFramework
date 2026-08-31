# Agent Memory 高安全部署参考（可选）

> 本文不属于 ReachAI 本地启动和开源开发的前置步骤。默认开发流程只需现有 MySQL 配置和 `sql/initV2.sql`；证书链、CA、truststore、`VERIFY_IDENTITY` 以及下述发布证据，仅供有相应安全要求的部署方自行采用。

本文把 [个人 Agent 记忆架构](../architecture/personal-agent-memory.md) 转成可审计的环境操作。它覆盖个人长期记忆、Runtime 单次会话状态、Knowledge 检索投影和 Business Index 企业入口，不把“源码测试通过”当成“生产已可用”。

## 当前结论

- 核心实现、JDK 8 SDK/Starter、管理端、企业入口、会话保留、Legal Hold、并发擦除、在线语义投影、真实 embedding canary 和合成 shadow benchmark 已完成源码验证。
- 20260813-20260815 的个人记忆历史迁移在对应远程环境仍为 `NOT RUN`；这些脚本的最终结构已进入 GitHub 基线并从当前树清理。若目标库早于 `ae9e1ce6`，必须从对应 Git tag 获取历史迁移链或按当前 `initV2.sql` 重建，不能用当前合并升级补跑缺失的历史阶段。
- 2026-08-16 对当前环境变量目标再次完成无凭据网络/TLS preflight：目标为非 loopback、TCP 可达且 MySQL 支持 TLS 1.2，但 JDBC `sslMode` 未显式配置；服务证书没有 DNS SAN，私有根也不在当前受信任存储中，因此 `VERIFY_IDENTITY` 无法通过。探测没有发送用户名、密码或 SQL；在 DBA 签发匹配当前 DNS SAN 的证书并提供受控 CA/JVM truststore 前，不读取 schema、更不执行迁移。
- 本手册和脚本不会自动连接数据库或写远程环境。远程迁移、双用户负载和擦除/恢复演练必须绑定批准的 change、目标环境和回滚负责人后显式执行。

## 证据等级

| 等级 | 含义 | 能否发布生产 |
| --- | --- | --- |
| `SOURCE_VERIFIED` | 编译、单元/模块测试、静态契约和本地隔离 canary 通过 | 否 |
| `ENVIRONMENT_VERIFIED` | 目标环境迁移、健康检查、正负向 E2E、隔离和删除均有证据 | 仍需生产准入评审 |
| `PRODUCTION_PROMOTION_ELIGIBLE` | 本文全部门禁通过，证据清单经失败关闭检查器判定通过 | 可以进入变更审批，不等于自动发布 |

## 1. 变更授权与停止条件

开始前记录：目标环境、数据库实例、服务版本、change ID、操作人、回滚人、维护窗口、备份策略 owner、业务数据 owner 和 Legal Hold 审批人。

出现以下任一情况立即停止，不继续“带病验收”：

- 数据库连接目标不能确认是批准的远程实例，或配置回退到 localhost；
- 迁移文件 SHA-256 与当前仓库不一致；
- 任何密钥、Bearer Token、用户正文或业务正文出现在日志/报告；
- Redis nonce、内部 HMAC、项目凭证重放或跨项目写入没有失败关闭；
- 删除后 canonical、投影、会话状态或恢复后的备份副本仍可返回被删内容；
- Legal Hold 被普通 clear、retention 或管理员 erase 绕过；
- 双用户演练出现一条跨 owner 命中。

## 2. 迁移前检查

1. 从服务实际部署配置解析 MySQL/Redis/内部 URL，保存脱敏后的 host、库名和配置来源。不要在命令行或证据文件保存密码。
2. 先证明目标数据库已经达到 GitHub 基线 `ae9e1ce6`；无法证明时停止，改用对应 Git tag 的历史迁移链或重建。
3. 对当前唯一升级脚本 `sql/upgrade-20260830-platform-consolidated.sql` 计算 SHA-256，并把值写入当次证据清单。该脚本包含 A2A/MCP clean-slate 段，必须额外确认旧数据处置边界。
4. 可使用仓库的 `scripts/apply-memory-migrations.mjs` 或具备同等门禁的组织远程迁移 runner；不要把 SQL 通过 Windows 默认编码管道传给原生 MySQL 客户端。当前 runner 只计划这一份合并升级，只允许 `reach_ai` 目标，先做无凭据传输检查，随后才把环境变量凭据交给 Connector/J。
5. runner 对整份脚本执行后回查目标表、列、索引、权限绑定和安全聚合计数，并立即执行第二遍验证幂等性；任一语句、回查或第二遍失败都会停止。DDL 会自动提交，不能把 runner 结果描述成可整体事务回滚。
6. 迁移证据必须包含 change ID、开始/结束时间、脚本 checksum、两遍执行结果和回查结果；仓库 runner 使用 `wx` 创建指定的绝对证据路径，拒绝覆盖旧证据，且不记录 host、URL、用户名、密码、SQL 正文或异常消息。

先运行 dry-run 和无凭据 preflight：

```powershell
node scripts/apply-memory-migrations.mjs
node scripts/apply-memory-migrations.mjs --preflight
```

只有第二条返回 `transport.ready=true` 后，才在批准的维护窗口注入数据库凭据、change ID、备份/重置路径确认、worker 关闭确认和精确执行确认：

```powershell
node scripts/apply-memory-migrations.mjs --execute --confirm=reach_ai --allow-remote --evidence=C:\approved-change\memory-migration-evidence.json
```

私有 CA 同时要求 `REACHAI_MYSQL_CA_FILE=<PEM>` 供无凭据 Node 探针使用，以及 `REACHAI_MYSQL_JAVA_TRUSTSTORE=<JKS-or-PKCS12>`、`REACHAI_MYSQL_JAVA_TRUSTSTORE_PASSWORD` 供 Connector/J 使用；只配置其中一侧会失败关闭。证据目录必须预先存在，文件必须不存在。

当前证书问题交给 DBA 时，验收条件必须完整写清：leaf 证书的 DNS SAN 精确覆盖 `AI_MYSQL_URL` 使用的 DNS 名，证书包含 `serverAuth` 用途且在有效期内，MySQL 端发送完整中间链，受控根/中间 CA 以 PEM 和 JVM truststore 两种消费形式交付并带版本/指纹审批记录。不能通过改用裸 IP、关闭 hostname verification、`trustServerCertificate=true`、导入现场抓取的未审批证书或把 metadata-only 诊断握手当作信任建立来绕过。

## 3. 配置与启动门禁

- Control、Runtime、Capability、Knowledge 使用同一 active `REACHAI_INTERNAL_SERVICE_SECRET`，验签端只在轮换窗口保留有限旧密钥。
- `REACHAI_PERSONAL_MEMORY_INDEX_IDENTITY_SECRET` 独立于调用密钥；轮换前先准备双读或全量投影重建。
- 先确认 `REACHAI_PERSONAL_MEMORY_EMBEDDING_MODEL_INSTANCE_ID` 指向目标 Model Center 中已验证的 embedding 实例，再在 Knowledge canary 设置 `REACHAI_PERSONAL_MEMORY_KNOWLEDGE_SEARCH_MODE=HYBRID`。模型、revision 或维度变化必须创建新的实例 ID，禁止在同一 ID 下原地换 embedding 模型；切换实例 ID 前必须排空旧 Knowledge worker，不能让两个目标模型同时写同一投影表。默认 `LEXICAL` 不调用 Model Gateway；`VECTOR` 不提供词面降级，不作为首轮生产灰度模式。
- 当前 embedding 实例是 Knowledge 部署级配置。若同一部署中的 tenant 有不同数据驻留/provider 合同，停止启用全局语义模式，先做物理部署隔离或 tenant-scoped 模型策略评审。
- 生产只能声明 `DIRECT_TLS` 或已经由平台团队证明的 `MTLS_MESH`；必须做一次明文探测并证明被拒绝。
- Runtime state store 使用 Redis，共享 nonce store 可用；Redis 不可用时内部记忆入口必须返回失败而非绕过验签。
- MySQL、Redis、临时文件卷、快照和备份启用组织批准的静态加密与密钥轮换。
- 远程 MySQL 必须在 JDBC URL 显式使用 `sslMode=VERIFY_IDENTITY`，并通过 JVM truststore 或 JDBC `trustCertificateKeyStoreUrl` 信任受控 CA。运行 `node scripts/check-mysql-transport-readiness.mjs` 做无凭据握手；若是私有 CA，仅给检查器设置 `REACHAI_MYSQL_CA_FILE=<PEM>` 或 `--ca=<PEM>`，服务进程仍须独立配置 JVM/JDBC truststore。严格握手失败且服务声明 TLS 能力时，检查器会再做一次不认证的 metadata-only 握手，只输出证书有效期、DNS SAN 和主机匹配布尔值，并在认证前关闭；该诊断永远不能把 `ready` 置为 true。脚本不会查询或输出主机、URL、证书名称和 SAN 内容。只有 `ready=true` 才能执行 schema preflight。`VERIFY_CA`、`PREFERRED`、无 SAN/主机名不匹配或临时接受服务端证书都不能作为企业记忆库准入证据。
- 初始保持：

  ```text
  REACHAI_PERSONAL_MEMORY_KNOWLEDGE_SEARCH_MODE=HYBRID
  REACHAI_PERSONAL_MEMORY_EMBEDDING_MODEL_INSTANCE_ID=<approved-model-instance-id>
  REACHAI_PERSONAL_MEMORY_KNOWLEDGE_QUERY_MODE=SHADOW
  RUNTIME_SESSION_RETENTION_ENABLED=false
  REACHAI_MEMORY_ERASURE_WORKER_ENABLED=false
  ```

  canonical 写入和 outbox 投递保持开启；不要为回滚检索而丢失投影事件。

每个服务都要分别记录：制品 checksum、PID/容器、启动时间、监听地址、直接 health、Control 聚合 health 和实际加载的配置模式。旧进程的 HTTP 200 不能作为新制品证据。

## 4. 目标环境功能矩阵

至少执行并保存请求元数据、状态码、对象 ID 哈希和数据库回查；证据中不保存正文。

1. 个人记忆：显式新增、幂等重放、更新、查询、导出、单条删除、强确认清空全部、TTL 到期和 outbox tombstone；批量清空必须验证 namespace 并发锁、binding/evidence/候选清理、历史审计内容派生字段擦除、每条 canonical 的更高版本 tombstone，以及响应明确列出的未覆盖数据域。另验证 embedding 的 PENDING→READY、模型切换重建、provider 失败重试/死信、旧 source version 不能完成、删除后向量字段原子清空。
2. 双用户隔离：A/B 互相不能读取、查询、修改、删除、导出或审核对方数据。
3. 会话：跨 Runtime 副本续聊、并发 turn 冲突、clear、retention purge、崩溃租约恢复。
4. Legal Hold：设置后 clear/retention/admin erase 全部被拒绝；释放后按批准原因码擦除。
5. Business Index 控制台：平台 session、读写 RBAC、multipart 上限、HMAC 篡改/重放。
6. 项目入口：项目凭证正向、错误 secret、过期、nonce 重放、body 篡改、跨 project/index 写入和 delete body 入签。
7. 业务权威回源：正向用户得到实时 resolution；负向用户、依赖故障和源版本变化均不回退使用索引正文。
8. SSE：长连接、取消、重连和会话恢复不因记忆注入或 retention 改变语义。

## 5. Shadow 准入

使用经过数据 owner 批准的脱敏真实分布样本，至少 100 次查询。在目标 Control 上执行：

```powershell
$env:REACHAI_CONTROL_URL = 'https://approved-control-host'
node scripts/check-personal-memory-shadow-readiness.mjs
```

默认门槛：canonical coverage 与 projected precision 均不低于 `0.8`，invalid ID 与查询错误率均不高于 `0.01`。脚本在指标缺失时失败关闭。合成 benchmark 或本机 TEI 结果只能证明开发可行性，不能替代这一步。

语义投影证据只记录聚合数，不导出 owner hash、memory ID 或正文。可由批准的只读账号在目标库执行以下模板，并把脱敏结果绑定到 change evidence：

```sql
SET @target_model_instance_id = '<approved-versioned-model-instance-id>';

SELECT COUNT(*) AS active_total,
       SUM(embedding_status = 'READY'
           AND embedding_model_instance_id = @target_model_instance_id
           AND embedding_source_version = source_version
           AND embedding_vector IS NOT NULL) AS ready_total,
       SUM(embedding_status = 'DEAD') AS dead_total
FROM knowledge_personal_memory_index
WHERE status = 'ACTIVE'
  AND (expires_at IS NULL OR expires_at > NOW());

SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,
           COALESCE(embedding_next_attempt_at, updated_at), NOW())), 0) AS oldest_pending_seconds
FROM knowledge_personal_memory_index
WHERE status = 'ACTIVE'
  AND embedding_status IN ('DISABLED', 'PENDING', 'PROCESSING', 'RETRY');

SELECT COUNT(*) AS unsafely_retained_deleted_vectors
FROM knowledge_personal_memory_index
WHERE status <> 'ACTIVE'
  AND (embedding_vector IS NOT NULL
       OR embedding_model_instance_id IS NOT NULL
       OR embedding_claim_token IS NOT NULL
       OR embedding_claim_until IS NOT NULL);
```

`readyRatio = ready_total / active_total`（`active_total=0` 时按演练是否实际创建并投影 canary 判定，不能把空库当作通过）；`unsafely_retained_deleted_vectors` 必须为 0。在线指标 `reachai.personal_memory.embedding.projection.snapshot_fresh` 必须为 1，`unsafe_deleted_vectors` 必须为 0，并同时检查 `ready_ratio`、`dead`、`oldest_pending_seconds` 和 `last_success_epoch_seconds`；刷新失败会保留上次计数但把快照标记为不新鲜，不能用陈旧计数通过准入。所有语义投影指标都禁止 tenant、user、owner、memory ID 或正文标签。模型切换演练还必须证明旧 worker 已排空，新的实例 ID 最终覆盖全部 READY 行且没有来回重建。

Knowledge 启动后可直接执行聚合准入检查；URL 必须保留 Knowledge 的 `/ai/` context path：

```powershell
$env:REACHAI_KNOWLEDGE_MANAGEMENT_URL='https://<knowledge-host>/ai/'
node scripts/check-personal-memory-semantic-readiness.mjs
```

脚本不读取记忆正文或身份标签，默认要求 active canary 至少 1 条、`readyRatio >= 0.99`、死信和删除残留为 0、最老积压不超过 300 秒、聚合快照不超过 90 秒；任一指标缺失时失败关闭。

只有连续观测窗口通过、Control outbox 快照新鲜、死信为零、最老未发布事件不超过 300 秒、删除投影积压为零且告警已接入后，才允许把 `SHADOW` 改为 `ACTIVE`。切回 `OFF` 只停用投影排序，canonical owner/授权/删除语义保持不变。

## 6. 双用户负载与隔离演练

脚本默认只输出计划，不发请求：

```powershell
node scripts/load-personal-memory.mjs
```

对远程环境执行必须使用两个短期、彼此独立的平台 session Bearer Token，并显式确认写入。脚本只生成合成 canary，验证自有召回、跨 owner 零泄漏、删除后零召回，并在 `finally` 再做一次幂等清理：

```powershell
$env:REACHAI_MEMORY_LOAD_BASE_URL = 'https://approved-control-host'
$env:REACHAI_MEMORY_LOAD_ALLOW_REMOTE = 'YES'
$env:REACHAI_MEMORY_LOAD_TOKEN_A = '<short-lived-token-a>'
$env:REACHAI_MEMORY_LOAD_TOKEN_B = '<short-lived-token-b>'
$env:REACHAI_MEMORY_LOAD_TENANT_A = 'approved-tenant'
$env:REACHAI_MEMORY_LOAD_TENANT_B = 'approved-tenant'
$env:REACHAI_MEMORY_LOAD_SAMPLES_PER_ACTOR = '25'
$env:REACHAI_MEMORY_LOAD_CONCURRENCY = '4'
$env:REACHAI_MEMORY_LOAD_EXECUTE = 'I_UNDERSTAND_THIS_WRITES_TEST_DATA'
node scripts/load-personal-memory.mjs
```

执行后立即清除当前 PowerShell 会话中的 Token 环境变量。若脚本报告 cleanup failure，按 `runId` 和安全的 `clientRequestId` 前缀定向查找并清理，不能用全表删除。

生产准入默认至少 200 个请求、两个 actor、错误率不高于 `0.01`、查询 p95 不高于 `750 ms`，且 cross-owner leak、deleted-item leak、cleanup failure 必须全部为 0。更严格阈值由容量评审写入证据文件，不在运行时临时放宽。

## 7. 跨域擦除与备份恢复

单个 Runtime session erase 不等于数据主体请求完成。每次擦除需要逐 owner 完成并出具证明：

ReachAI 已提供持久编排入口 `POST /api/context/memory-erasure-requests`，只允许具有全局 `context:memory:erasure:manage` 的实时平台会话调用。创建时必须提供 tenant、Runtime user、原因码、工单引用、幂等键和精确确认；raw Runtime user 只出现在 HTTPS body 与自动执行所需的短暂数据库列中，不进入 URL、响应或管理审计，五个自动域完成后立即置空。`control_memory_erasure_request` 保存请求租约/退避和聚合状态，`control_memory_erasure_domain` 保存九域机器状态、聚合计数和外部证据引用，不保存正文或对象 ID。

自动执行严格按 Control canonical → correlation-scoped DELETE outbox 终态 → Knowledge owner aggregate proof 顺序闭环，同时批量擦除 Runtime owner 的 session/state/ledger。outbox 只要存在 `DEAD`/未知状态、Knowledge 仍有 ACTIVE/删除向量残留、Runtime 仍有剩余会话或任何域清单缺失，就不能进入自动完成。Legal Hold 得到 `BLOCKED_LEGAL_HOLD`，不会被重试逻辑释放。自动域完成后请求进入 `ACTION_REQUIRED`；RunOps、Business Index、业务源和备份四域必须分别提交 `ERASED`、`NOT_APPLICABLE` 或 `RETAINED_LEGAL` 加不可变证据引用。九域全部完成才进入 `COMPLETED`；任一合法保留得到 `COMPLETED_WITH_RETENTION`。

worker 默认 `REACHAI_MEMORY_ERASURE_WORKER_ENABLED=false`。只有本迁移回查、Control/Knowledge/Runtime 内部 HMAC 正负向、outbox 投递、Legal Hold 和恢复演练均通过后，才在 Control canary 显式启用。管理端入口为 `/settings/memory-erasure`；页面不替代 change 审批或责任方证据系统。

### 7.1 九域编排自动验收器

`scripts/verify-memory-erasure-e2e.mjs` 将创建请求、以相同幂等键重放、等待自动域、校验完整九域清单、提交四个人工域证据，并再次重放一条相同证据验证不可变幂等语义。输出只包含 request ID、owner HMAC、聚合计数和证据引用，不包含平台 Token 或 raw Runtime user。脚本只能针对以 `e2e-memory-erasure-` 开头且带唯一后缀的合成 owner；远程目标还必须同时使用 HTTPS 和显式 `ALLOW_REMOTE=YES`。

先复制模板到变更系统管理的临时目录并替换全部 `REPLACE_*` 引用；脚本会拒绝模板占位符。每个引用必须指向已经由对应 data owner 批准的不可变证据，不能为了让状态变绿而填造 `NOT_APPLICABLE`：

```powershell
Copy-Item scripts/fixtures/memory-erasure-manual-evidence.example.json `
  C:\approved-change\memory-erasure-evidence.json

$env:REACHAI_MEMORY_ERASURE_BASE_URL = $env:REACHAI_APPROVED_CONTROL_BASE_URL
$env:REACHAI_MEMORY_ERASURE_ALLOW_REMOTE = 'YES'
node scripts/verify-memory-erasure-e2e.mjs
```

上面没有执行确认，只输出 dry-run 计划且不会发请求。迁移回查、内部链路和 canary worker 均已通过审批后，才为已预置个人记忆和 Runtime session 的合成 owner 设置短期平台管理员 session，并给出两次精确确认：

```powershell
$env:REACHAI_MEMORY_ERASURE_PLATFORM_SESSION_TOKEN = $env:REACHAI_APPROVED_PLATFORM_SESSION_TOKEN
$env:REACHAI_MEMORY_ERASURE_TENANT_ID = 'approved-tenant'
$env:REACHAI_MEMORY_ERASURE_RUNTIME_USER_ID = 'e2e-memory-erasure-unique-run-id'
$env:REACHAI_MEMORY_ERASURE_CLIENT_REQUEST_ID = 'memory-erasure-e2e-unique-run-id'
$env:REACHAI_MEMORY_ERASURE_REFERENCE_ID = 'change:approved-change-id'
$env:REACHAI_MEMORY_ERASURE_EXPECTED_STATUS = 'COMPLETED_WITH_RETENTION'
$env:REACHAI_MEMORY_ERASURE_EXECUTE = `
  'I_UNDERSTAND_THIS_PERMANENTLY_ERASES_A_SYNTHETIC_AGENT_MEMORY_OWNER'
$env:REACHAI_MEMORY_ERASURE_ATTEST_EXECUTE = `
  'I_HAVE_COLLECTED_AND_APPROVED_ALL_MANUAL_ERASURE_EVIDENCE'

node scripts/verify-memory-erasure-e2e.mjs `
  --manual-evidence=C:\approved-change\memory-erasure-evidence.json

Remove-Item Env:REACHAI_MEMORY_ERASURE_PLATFORM_SESSION_TOKEN
Remove-Item Env:REACHAI_MEMORY_ERASURE_EXECUTE
Remove-Item Env:REACHAI_MEMORY_ERASURE_ATTEST_EXECUTE
```

正常数据面验收要求五个自动域的 `affectedCount` 合计大于 0；零数据目标默认失败，不能作为删除有效性证据。只有单独的控制面 smoke 才可设置精确 `REACHAI_MEMORY_ERASURE_ALLOW_EMPTY=I_APPROVE_AN_EMPTY_ERASURE_CONTROL_PLANE_SMOKE`，其结果会明确保留 `dataPlaneExercised=false`。Legal Hold 使用另一个预置合成 owner 独立执行，设置 `REACHAI_MEMORY_ERASURE_EXPECTED_STATUS=BLOCKED_LEGAL_HOLD` 且不提交人工证据；只有 Runtime 域真实停在 `BLOCKED_LEGAL_HOLD` 才通过。

| 域 | owner | 必须证明 |
| --- | --- | --- |
| `CONTROL_PERSONAL_MEMORY` | Control | canonical 隐私字段、binding/evidence 和内容派生审计元数据已擦除，只剩无正文 tombstone |
| `CONTROL_CANDIDATE_OUTBOX` | Control | owner 候选已物理删除，outbox 旧 payload 已擦除，最高版本 delete 已发布 |
| `KNOWLEDGE_PERSONAL_PROJECTION` | Knowledge | owner-scoped 投影 tombstone 生效，旧 upsert 不可复活 |
| `RUNTIME_SESSION_STATE` | Runtime | AgentScope/Redis state 与 Tool artifact 密文删除 |
| `RUNTIME_CONVERSATION_LEDGER` | Runtime | retention/批准 erase 后事件和 session 行物理删除 |
| `RUNOPS_TRACE_INTERACTION` | 对应 owner service | 按组织保留/脱敏策略处理，不能假设随 session 自动删除 |
| `BUSINESS_INDEX` | Knowledge | 索引记录删除并停止召回 |
| `BUSINESS_SOURCE_SYSTEM` | 业务系统 | 由业务 data owner 擦除或出具合法保留证明；ReachAI 不能代替源系统决策 |
| `BACKUP_EXPIRY_AND_RESTORE` | 基础设施/DBA | 备份按批准期限到期，恢复后在开放流量前追平到擦除 cutoff 并重跑零召回核验 |

不可变备份通常不能逐条即时改写，因此不能声称“在线 DELETE 已从所有备份物理消失”。合规闭环是：批准的最长期限、静态加密、访问隔离、到期销毁证明，以及恢复时通过 binlog/PITR 追平擦除事务或重放独立删除证明。恢复副本在 reconciliation 通过前不得承载流量。

恢复演练至少记录：备份创建/到期策略、恢复点、擦除 cutoff、PITR 结束位置、RPO/RTO、九个域的复查结果、旧 outbox 不复活和 Knowledge 重建结果。

## 8. 生产证据清单

复制 `scripts/fixtures/personal-memory-production-evidence.example.json` 到变更系统管理的临时工作目录，填写实际证据。不要把 Token、secret、正文或带凭证 URL 写入 JSON。把九域验收器的完整 JSON 输出原样放入 `gates.erasureOrchestration.result`，并让 `evidenceRef` 指向不可变原件；不能只手工填写九个域的结论。

检查器会把迁移 checksum 与当前仓库文件重新计算比对，并要求迁移、Control outbox、语义投影、shadow、负载、服务间传输、MySQL `VERIFY_IDENTITY`、静态加密、密钥轮换、备份恢复、九域擦除、审计、告警、SSE 和故障演练全部有近期证据。九域结果还必须来自远程目标、真实影响至少一条自动域数据、创建/证据重放均幂等、owner 只以 HMAC 出现且九域全部完成；明确标记 `dataPlaneExercised=false` 的空目标 smoke 永远不能授权生产晋级。outbox 要求快照新鲜、死信为零、最老未发布事件不超过 300 秒；语义投影要求 `HYBRID/VECTOR`、`snapshotFresh = true`、`readyRatio >= 0.99`、`deadCount = 0`、`unsafeDeletedVectorCount = 0`、最老 PENDING 不超过 300 秒，并通过模型切换、provider 故障 canonical fallback 和删除向量擦除演练：

```powershell
node scripts/check-personal-memory-production-readiness.mjs --evidence=C:\approved-change\memory-evidence.json
```

只有输出 `productionPromotionEligible: true` 才能提交生产变更审批。该结果是机器检查的准入证据，不会自动迁移、发布或切换 `ACTIVE`。

## 9. 回滚与故障处置

- 检索质量或 Knowledge 故障：先把 query mode 切回 `OFF`；保留 canonical 写入和 outbox，避免恢复后缺事件。
- embedding provider 故障：保持 Control 为 `SHADOW/OFF`，先修复 Model Gateway；`DEAD` 行不会无限重试。若更换模型，使用新的 Model Center 实例 ID 自动触发重建；若仍使用同一实例，只能在批准的维护操作中按精确范围把 ACTIVE 投影重置为 `PENDING`，不得删除 canonical 记忆或无 tenant/owner 边界地批量改表。
- personal-memory outbox 的 `DEAD` 仅能在确认 canonical 仍为 ACTIVE、根因已修复且 payload 仍是合法事件后定向重置；删除/到期事务产生的 `SUPERSEDED` 已被更高版本 tombstone 取代，禁止重新投递。
- retention 异常：设置 `RUNTIME_SESSION_RETENTION_ENABLED=false`，保留已取得的 claim 和审计；定位后依赖租约恢复，不手工把状态批量改回 ACTIVE。
- 项目入口异常：在公共网关定向阻断 `/api/knowledge-ingress/**`，保留控制台只读路径；不要把请求临时改成匿名直连 Knowledge。
- 内部鉴权异常：回滚 active secret 前先确保所有验签端仍接受目标密钥；禁止通过关闭 HMAC/nonce 继续服务。
- 数据泄漏、跨 owner 命中或删除复活：立即关闭相关入口和投影 ACTIVE，保存不含正文的证据，进入安全事件流程。

任何回滚都不能解除 Legal Hold、恢复已擦除正文，或绕过业务源系统授权。
