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
```

五服务编译：

```powershell
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
```

五服务都通过 IDEA 或命令行启动后，运行 live smoke：

```powershell
node scripts/check-physical-service-smoke.mjs --wait-ms 120000 --interval-ms 3000
```

live smoke 检查：

- `reachai-control-service` 的 `/actuator/health`
- `reachai-runtime-service` 的 `/internal/runtime/health`
- `reachai-capability-service` 的 `/internal/capability/health`
- `reachai-knowledge-service` 的 `/ai/actuator/health`
- `reachai-model-service` 的 `/actuator/health`
- Control 的 `/api/internal-services/health` 是否能聚合 Runtime 和 Capability

## 单模块后端验证

```powershell
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-runtime-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-capability-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-knowledge-service -am test
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-model-service -am test
```

如果只需要编译，把 `test` 换成 `compile`，或加 `-DskipTests compile`。

旧 `ai-agent-service` module 已删除，不再提供 legacy fallback 编译入口。

## 前端验证

```powershell
cd ai-admin-front
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

## SQL 验证

SQL 改动至少检查：

```powershell
rg -n "目标表|目标字段|目标索引" sql/init.sql
Get-ChildItem sql -Filter "upgrade-*.sql"
git diff -- sql/init.sql sql/README.md
```

有 MySQL 环境且当前任务新增了 upgrade SQL 时，执行对应 upgrade SQL，并确认新环境可以只依赖 `sql/init.sql`。

## 文档与规则验证

```powershell
git diff --check
node scripts/check-backend-boundary-naming.mjs
node scripts/check-frontend-public-api-routes.mjs
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
