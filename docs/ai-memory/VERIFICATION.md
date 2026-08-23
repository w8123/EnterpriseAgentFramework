# ReachAI Verification

这些命令是 AI 编程工具完成修改后的常用验证入口。根据任务范围选择最小但真实的验证集合。

## 路线A后端拆分

边界、命名、路由和依赖 guard：

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-frontend-public-api-routes.test.mjs
node scripts/check-frontend-public-api-routes.mjs
node scripts/check-physical-service-smoke.test.mjs
node scripts/check-physical-service-route-contracts.test.mjs
node scripts/check-physical-service-route-contracts.mjs
node scripts/check-backend-domain-dependencies.test.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-service-table-ownership.test.mjs
node scripts/check-service-table-ownership.mjs
node scripts/check-internal-api-contracts.test.mjs
node scripts/check-internal-api-contracts.mjs
node --test scripts/check-legacy-skill-contract.test.mjs
node scripts/check-legacy-skill-contract.mjs
```

`check-service-table-ownership.mjs` 覆盖同库阶段的表边界治理：优先检查 `sql/initV2.sql` 中每张 `CREATE TABLE` 表必须登记 owner；后端 `@TableName`、MyBatis 注解 SQL、MyBatis XML SQL 和 JdbcTemplate SQL 不得跨服务直接访问非 owner 表，除非在 `docs/architecture/service-table-ownership.md` 的 `Additional direct access` 中登记临时例外。

五服务编译：

编译五个 JDK 17 后端服务前，确认 `JAVA_HOME` 指向 JDK 17。Windows 本机如果默认还是 JDK 8，直接跑 Maven 可能报 `无效目标发行版: 17`；可先切到 `C:\Program Files\Java\jdk-17` 或使用 IDE/Maven runner 的 JDK 17 配置。

```powershell
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
```

五服务都通过 IDEA 或命令行启动后，运行 live smoke：

```powershell
node scripts/check-local-service-artifact-freshness.test.mjs
node scripts/check-local-service-artifact-freshness.mjs --check-running
$env:REACHAI_PLATFORM_SESSION_TOKEN = '<登录 ReachAI 管理端后取得的平台会话令牌>'
node scripts/check-physical-service-smoke.mjs --wait-ms 120000 --interval-ms 3000
Remove-Item Env:REACHAI_PLATFORM_SESSION_TOKEN
```

本地 artifact freshness 检查先确认每个 `src/main` 都不晚于对应可部署 JAR；Windows 下加
`--check-running` 后还会核对 18601-18605 监听进程的命令行确实指向该 JAR，且进程启动时间
不早于 JAR 构建时间。它用于阻止“健康接口为 UP，但运行的仍是旧包”的假绿。该检查通过仍
不替代下面的接口 smoke 和真实业务 E2E。

live smoke 继续检查：

- `reachai-control-service` 的 `/actuator/health`
- `reachai-runtime-service` 的 `/internal/runtime/health`
- `reachai-capability-service` 的 `/internal/capability/health`
- `reachai-knowledge-service` 的 `/ai/actuator/health`
- `reachai-model-service` 的 `/actuator/health`
- Control 的 `/api/internal-services/health` 是否能聚合 Runtime、Capability、Model 和 Knowledge；单服务失败应返回 DOWN 而不是整接口 500

## 单模块后端验证

```powershell
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-runtime-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-capability-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-knowledge-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-model-service -am test
```

AI Coding Task Kernel 的 Windows 协议测试会真实启动两个独立 PowerShell 进程，验证 Trae 激活、DPAPI 恢复、UTF-8 中文事件、Artifact 应用、验收硬门禁、服务端 Embed E2E 依据和本地缓存清理：

真实交接或业务系统联调后，先检查工作树中没有遗留一次性交接码或任务 Token。检查器只报告文件、行号和类型，不回显命中的敏感值：

```powershell
node scripts/check-ai-coding-secret-hygiene.test.mjs
node scripts/check-ai-coding-secret-hygiene.mjs
```

```powershell
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' `
  -pl reachai-control-service `
  '-Dtest=AiCodingHandoffPromptFactoryTest,AiCodingTaskProtocolHttpTest,ProjectOnboardingTaskProviderTest,PlatformEmbedE2eEvidenceServiceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  test
```

Workflow 第一阶段节点能力（变量/转换/Knowledge/HTTP）目标测试（不可再遗漏 Draft/Credential/Supervisor）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' `
  -pl reachai-runtime-service -am `
  '-Dtest=RuntimeGraphSpecExecutorTest,RuntimeGraphSpecExecutorHandlerConformanceTest,RuntimeGraphSpecPhase1NodesTest,RuntimeGraphSpecRetryDeterminismTest,RuntimeWorkflowNodeCapabilityRegistryTest,RuntimeWorkflowReleaseValidationServiceTest,RuntimeWorkflowProposalGenerationServiceTest,RuntimeWorkflowProposalEditServiceTest,RuntimeWorkflowGraphMutationServiceTest,RuntimeWorkflowCredentialServiceTest,RuntimeWorkflowDebugServiceTest,AgentScopeSupervisorRuntimeAdapterTest,WorkflowHttpClientTest,WorkflowExecutionIdentityTest,WorkflowTraceSanitizerPersistenceTest,WorkflowVariableNamespacesTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  test
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' -pl reachai-runtime-service -am test
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' `
  -pl reachai-knowledge-service -am `
  '-Dtest=KnowledgeRetrievalCoreBehaviorTest,KnowledgeRetrievalInternalControllerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  test
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' -pl reachai-knowledge-service -am test
```

安全门槛最低证据（缺任一不得 reopen Knowledge/HTTP BETA；Browser/Live E2E 未跑仍记 PENDING；真实 MySQL 多实例 nonce 未跑记 `JDBC_MYSQL_PENDING` 并保持关闭）：

- `InternalAuthNonceStoreTest` / `JdbcInternalAuthNonceStoreTest`（maxEntries=1 立即重放失败；满容拒绝新 nonce 且旧 nonce 不可重放；过期后可复用；TTL/skew 校验；H2 并发成功数=1；双 store 共享库）
- `InternalServiceHmacTest` / `InternalServiceTransportContractTest`（BODY_SHA256 绑定最终字节；篡改 body/header → 失败；中文/空 body；真实 signer↔verifier）
- `InternalServiceAuthFilterMockMvcTest` / `InternalAgentStreamAuthMockMvcTest`（sync/SSE：无签/错签/过期/重放/篡改 body|digest|source|userId → 401 且不调用 ExecutionService）
- `TracePersistenceBoundaryTest`（Debug/finishAgent/finishWorkflow/WAITING_USER marker 不落库）
- `RuntimeRunLifecycleServiceTest` / `SupervisorExecutionTraceAuditIdentityTest`（signed userId=42 vs body attacker；无 identity 不写审计 userId；Embed claims）
- `ControlRuntimePublicControllerTest` / `PlatformEmbedPublicControllerTest` / `PlatformEmbedStreamRelayTest`
- `WorkflowHttpClientTest` / `WorkflowExecutionIdentityTest` / `WorkflowTrustedIdentityEntryTest`
- `WorkflowTraceSanitizerPersistenceTest` / Knowledge retrieval 行为测试
- Deploy：`deploy/k8s/secrets.yml.example` + Control/Runtime Deployment 注入同一 Secret；actuator `INTERNAL_AUTH_NOT_CONFIGURED`
- 前端 `useWorkflowStudioPanelValidation.test.ts` + persistence/release/AI tests

第五轮全量门槛命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl reachai-control-service,reachai-runtime-service,reachai-knowledge-service -am test
Set-Location ai-admin-front
npm run test:workflow
npm run check:workflow-layout
npm run check:studio-canvas
npm run build
git diff --check
```

前端：

```powershell
Set-Location ai-admin-front
npm run check:page-workbench
npm run test:workflow
npm run check:workflow-layout
npm run check:studio-canvas
npm run build
```

如果只需要编译，把 `test` 换成 `compile`，或加 `-DskipTests compile`。

旧 `ai-agent-service` module 已删除，不再提供 legacy fallback 编译入口。

## 前端验证

```powershell
cd ai-admin-front
npm run check:page-workbench
npm run build
```

类型风险高或改动涉及共享类型时：

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
```

前端代理事实：

- `/api/**` -> `reachai-control-service:18603`
- `/ai/**` -> `reachai-knowledge-service:18602`
- `/model/**` -> `reachai-model-service:18601`

## Agent Memory 验证

源码与开发库验证：

```powershell
node --test scripts/apply-memory-migrations.test.mjs
node scripts/apply-memory-migrations.mjs
node scripts/apply-memory-migrations.mjs --preflight
node scripts/apply-memory-migrations.mjs --execute --confirm=reach_ai
```

默认命令只计算固定迁移顺序和 checksum，不联网、不使用凭据。`--preflight` 使用现有环境变量连接数据库，只读检查目标库、MySQL 版本、字符集和基础表；`--execute` 需要显式确认，远程开发库再增加 `--allow-remote`。runner 沿用项目 JDBC URL，不要求为本地开发额外签发证书或配置 truststore。

## SQL 验证

SQL 改动至少检查：

```powershell
rg -n "目标表|目标字段|目标索引" sql/initV2.sql
Get-ChildItem sql -Filter "upgrade-*.sql"
git diff -- sql/initV2.sql sql/README.md
node scripts/check-service-table-ownership.test.mjs
node scripts/check-service-table-ownership.mjs
```

有 MySQL 环境且当前任务新增了 upgrade SQL 时，执行对应 upgrade SQL，并确认新环境可以只依赖 `sql/initV2.sql`。当前新库重建场景不要求旧数据迁移。

## 文档与规则验证

```powershell
git diff --check
node scripts/check-backend-boundary-naming.mjs
node scripts/check-frontend-public-api-routes.mjs
node scripts/check-legacy-skill-contract.mjs
node scripts/check-service-table-ownership.mjs
node scripts/check-internal-api-contracts.mjs
```

检查 Markdown 中的本地链接和关键路径时，优先用 `rg` 做静态确认：

```powershell
rg -n "ai-agent-service|ai-skills-service|ai-model-service|LEGACY_AGENT_SERVICE_DISABLED|disabled route|技能服务" README.md docs AGENTS.md
```

注意：`docs/architecture/public-route-contracts.md` 和 `legacy-retirement.md` 可能为了契约说明保留旧 alias 或 retired route；主线入口、当前规则和启动说明不得把旧服务描述为当前运行单元。

## 最终说明

最终回复必须明确：

- 改了哪些文件。
- 跑了哪些验证命令。
- 哪些验证未跑及原因。
- 是否涉及 SQL、前端代理、启动端口或公共 API 契约。
