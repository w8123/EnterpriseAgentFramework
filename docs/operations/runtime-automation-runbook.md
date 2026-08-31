# Runtime Automation 生产运行手册

## 1. 发布前置条件

必须全部满足后才能启用：

1. Control 与 Runtime 已部署相同的 `REACHAI_INTERNAL_SERVICE_SECRET`，内部传输模式符合环境要求。
2. MySQL 时间和 Runtime JVM 时间已由 NTP 同步；应用以 UTC 持久化 Automation/db-scheduler 时间。
3. 若从 GitHub 基线 `ae9e1ce6` 升级，已备份目标库并完整执行 `sql/upgrade-20260830-platform-consolidated.sql`（Runtime Automation 位于 Section 08）；不得只截取单段执行。
4. Control 默认角色授权已由安全 owner 审核，项目级 role binding 已绑定正确 projectCode。
5. 至少有一个可执行的已发布 Agent 配置版本或 Workflow 版本。
6. 下游有副作用的 Capability 已验证 occurrence/trace id 幂等策略。
7. Automation 开关仍为 `false`，先完成结构和只读检查。

## 2. 数据库检查

```sql
SHOW CREATE TABLE runtime_automation;
SHOW CREATE TABLE runtime_automation_version;
SHOW CREATE TABLE runtime_automation_occurrence;
SHOW CREATE TABLE runtime_automation_execution_slot;
SHOW CREATE TABLE runtime_scheduler_task;

SELECT permission_code
FROM control_platform_permission
WHERE permission_code IN ('automation:read', 'automation:write', 'automation:operate')
ORDER BY permission_code;
```

迁移脚本应在同一目标库重复执行一次，第二次不得失败或产生重复权限/角色关系。

## 3. 配置

```powershell
$env:REACHAI_AUTOMATION_ENABLED='true'
$env:REACHAI_AUTOMATION_CLOCK_THREADS='2'
$env:REACHAI_AUTOMATION_CLOCK_POLLING_INTERVAL_MS='1000'
$env:REACHAI_AUTOMATION_WORKER_CONCURRENCY='4'
$env:REACHAI_AUTOMATION_WORKER_LEASE_SECONDS='600'
$env:REACHAI_AUTOMATION_WORKER_HEARTBEAT_MS='30000'
```

约束：

- heartbeat 必须明显小于 lease；建议不超过 lease 的三分之一。
- worker concurrency 要同时受模型、Capability、数据库连接池和下游限流容量约束。
- 所有 Runtime 副本必须使用相同表、开关和时钟配置；滚动发布期间允许短暂新旧副本并存，
  但只有包含 Automation 代码且完成迁移的副本才能启用。
- 不在环境变量或计划输入中放业务凭据。

## 4. 灰度顺序

1. 在所有 Runtime 副本保持开关关闭的情况下部署代码。
2. 调用 `GET /api/automations/readiness`，预期 `enabled=false` 且返回启用提示。
3. 只启用一个 Runtime 副本，确认 scheduler 启动且无缺表/反序列化错误。
4. 创建一个未来 2-5 分钟的 ONCE smoke Automation，目标使用无副作用 Workflow。
5. 验证定义、engine command、scheduler row、occurrence、attempt、RunOps Trace 一一关联。
6. 增加第二个 Runtime 副本，执行双实例重复触发与故障恢复演练。
7. 观察一个完整业务高峰窗口后，再扩到全部副本和真实项目。

## 5. 核心验收

### 5.1 正常路径

- 创建 DRAFT 不产生 scheduler row。
- 激活后产生一个与 automation key 对应的 scheduler row。
- 到点只产生一个 `SCHEDULE` occurrence；成功后有一个 attempt 和 RunOps trace。
- pause 后 scheduler row 被取消，待执行 occurrence 变为 CANCELLED。
- 更新创建新 immutable version；旧版本仍可查询但不再被计划触发。

### 5.2 双实例与故障恢复

1. 两个 Runtime 副本同时启用，创建每分钟 CRON，连续观察至少 5 个名义时刻；每个时刻
   `occurrence_key` 只能出现一行。
2. 在一个 worker 进入 RUNNING 后强制终止该副本；租约到期后另一副本应领取下一 attempt。
3. 在 engine command 写入后、scheduler 同步前终止副本；另一副本应恢复 command。
4. 断开临时下游，确认 retry/backoff；恢复后成功，达到 max attempts 后必须 DEAD。
5. 制造超出 grace 的停机，分别验证 SKIP、FIRE_ONCE、CATCH_UP 上限。

### 5.3 安全与治理

- 没有平台会话返回 401；缺少对应 permission 返回 403。
- project-scoped 用户不能空 projectCode 列举全局，也不能读取或操作其他项目 key。
- 篡改 internal body、tenant、user、nonce 或签名失败关闭。
- 输入中出现 token/password/secret/credential/apiKey 类字段时创建失败。
- 目标要求人工审批时 occurrence 失败并留下 Trace，不得自动确认。
- 草稿或错误项目的 Agent/Workflow version 不可创建、恢复或手工运行。

## 6. 观测查询

```sql
-- 待执行积压与最老等待时间
SELECT status, COUNT(*) AS total, MIN(available_at) AS oldest_available_at
FROM runtime_automation_occurrence
WHERE status IN ('PENDING', 'RETRY', 'LEASED', 'RUNNING')
GROUP BY status;

-- 定义到时钟的同步积压
SELECT status, COUNT(*) AS total, MIN(created_at) AS oldest_created_at
FROM runtime_automation_engine_command
WHERE status <> 'COMPLETED'
GROUP BY status;

-- 终态和失败码分布
SELECT status, COALESCE(last_error_code, '-') AS error_code, COUNT(*) AS total
FROM runtime_automation_occurrence
WHERE created_at >= UTC_TIMESTAMP() - INTERVAL 24 HOUR
GROUP BY status, COALESCE(last_error_code, '-')
ORDER BY total DESC;

-- 已过期但未恢复的租约
SELECT id, automation_id, status, leased_until
FROM runtime_automation_occurrence
WHERE status IN ('LEASED', 'RUNNING') AND leased_until < UTC_TIMESTAMP(6);
```

不得把 `input_snapshot_json`、`principal_snapshot_json`、完整错误正文或 scheduler
`task_data` 接入普通日志/指标标签。

## 7. 事故处理

### 7.1 停止新触发

优先在 Control 对受影响定义执行 pause。大面积事故可将所有 Runtime 副本的
`REACHAI_AUTOMATION_ENABLED` 设为 `false` 并滚动重启；这会停止时钟和 worker，
不会删除定义、occurrence、attempt 或 RunOps 事实。

### 7.2 积压恢复

先确认下游容量，再逐步恢复 worker concurrency。不要直接把所有 RETRY 改为 PENDING，
也不要批量清空 scheduler 表。misfire 与 maxCatchUp 应限制恢复洪峰。

### 7.3 DEAD 处理

按 error code 和目标版本调查。修复后使用公开 retry 操作创建新的 occurrence，保留原
DEAD 事实；禁止覆盖 attempt 或复用原 occurrence id。

### 7.4 时钟漂移

发现 JVM/数据库/NTP 漂移时立即停止新触发，校正时间后按定义的 misfire 策略恢复。
不要手工改写名义 `scheduled_at` 来掩盖漂移。

## 8. 回滚与备份

- 应用回滚前先关闭 Automation 开关；表结构保持向前兼容，不删除数据。
- 回滚不删除 `runtime_scheduler_task`，避免恢复新版时丢失计划；旧应用不会访问它。
- Automation 定义、版本、occurrence、attempt、event、engine command、slot 和 scheduler
  表应与 Runtime/RunOps 使用同一备份恢复点。
- 恢复演练必须确认唯一 occurrence key、current version、scheduler row 和 RunOps trace
  仍能关联。

## 9. 生产准入结论格式

只有数据库迁移、RBAC、单实例、双实例、故障恢复、安全、RunOps 和真实无副作用业务
E2E 全部通过，才可标记 `PRODUCTION_READY`。任何未运行项必须明确记录为 `PENDING` 或
`NOT_RUN`，不能由编译或页面截图代替。
