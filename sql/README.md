# 数据库初始化脚本

## 当前基线

`sql/initV2.sql` 是 ReachAI 当前新库 SQL 基线入口，覆盖当前物理服务拆分主路径运行所需的表结构、补列、补索引和必要种子数据。V2 仍使用一个 MySQL 库，不拆库；表名按 owning service / domain 前缀收口。旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。

当前主路径服务：

- `reachai-control-service`
- `reachai-runtime-service`
- `reachai-capability-service`
- `reachai-knowledge-service`
- `reachai-model-service`

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已删除；V2 面向新库重建，不兼容旧表名，也不提供旧数据迁移脚本。

## 执行方式

```bash
mysql -uroot -p < sql/initV2.sql
```

脚本按 `reach_ai` 数据库执行，并保持幂等：

1. 建库：`CREATE DATABASE IF NOT EXISTS reach_ai`。
2. 建表：统一使用 `CREATE TABLE IF NOT EXISTS`。
3. 补列 / 补索引：通过 `information_schema` 判空后执行。
4. 种子数据：`model_template` 平台模板在 `initV2.sql` 使用 `INSERT IGNORE`（全新空表）；已有库升级见 `upgrade-20260717-agent-supervisor-runops-model-center-v2.sql` 的 upsert。`model_instance` 不再提供目录型实例种子。Agent/Workflow 种子只在稳定键不存在时插入。

## 覆盖范围

- Agent 聚合：`runtime_agent`、`runtime_agent_config_version`、固定 Workflow 发布版本的 `runtime_agent_workflow_tool`。
- Workflow 编排：`runtime_workflow`、`runtime_workflow_version`。
- 注册中心、项目实例、能力快照、字段级 diff、review/apply。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Capability 资产、交互式能力挂起恢复。
- RunOps 根运行事实 `runtime_run`、通用子事件 `runtime_trace_span`、Tool 调用、Tool ACL、Guard、SlotExtractor、DomainClassifier。
- MCP、A2A、Gateway、市场资产、嵌入式对话。
- 模型中心 V2：`model_template`（平台目录模板）与 `model_instance`（可执行实例；全新库可不含实例种子）。
- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- Context Governance 相关表。

## 升级规则

任何 schema、索引、种子数据或字段语义变化，都必须先：

1. 修改 `sql/initV2.sql`，保证全新环境直接可用。
2. 如果已有开发/测试库需要升级，再新增当次 `sql/upgrade-YYYYMMDD-short-name.sql`；当前 V2 新库重建场景不要求提供旧数据迁移。
3. 更新本文件或相关文档中的执行说明。

根目录 `sql/` 保留 `initV2.sql`、本说明和当前仍需应用的 `upgrade-*.sql`。后续真实数据库变更仍需新增当次 upgrade 脚本；该脚本在确认已合入基线且开发/测试库不再需要单独执行后，可以按同样规则清理。

不再执行或新增任何历史 service-level SQL 目录。不要在各服务目录下恢复独立迁移入口；当前唯一入口仍是根目录 `sql/`。

## 破坏性变更

项目默认不为旧数据做复杂兼容迁移。V2 按新库重建处理；如果后续升级脚本会清理、重建、重命名或丢弃历史字段/数据，必须在 SQL 注释和最终变更说明中写清影响。

## 建议验证

执行后至少抽样检查：

```sql
SHOW TABLES LIKE 'runtime_agent';
SHOW TABLES LIKE 'runtime_workflow';
DESC runtime_agent;
DESC runtime_workflow;
DESC runtime_run;
DESC runtime_trace_span;
DESC runtime_agent_workflow_tool;
DESC capability_scan_project_tool;
SHOW INDEX FROM control_mcp_client;
```

文档/规则改动时可运行：

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-model-center-v2-sql.mjs
git diff --check
```

## Upgrade: 20260717 Agent Supervisor / RunOps / Model Center V2（破坏性）

已有开发或测试库只执行下面这一份整合脚本。它替代 20260714 Agent/RunOps 与 20260716 Model Center V2 两份独立脚本，并按依赖顺序先升级 Agent/RunOps，再升级 Model Center V2。

### 前置检查

1. 确认目标库是可丢弃的开发/测试库，不是共享生产库。
2. 备份整个 `reach_ai`：`mysqldump -uroot -p reach_ai > reach_ai_backup_YYYYMMDD.sql`。
3. 确认 `model_instance` 处于哪一类状态：
   - 旧 V1：仍有 `endpoint_type` / `credential_json` / `workspace_id`
   - 最终 V2：`connection_config_json` 存在、无 `endpoint_type`，核心 7 列齐全，非核心必需 12 列（含 `remark`）齐全，`project_scope_key` 为 STORED generated `IFNULL(project_code,'')`，唯一索引完整（共 19 列）
   - 上一版不完整 V2：已有 `connection_config_json`、无 `endpoint_type`，但生成列/索引/字段不完整（常见：普通 `project_scope_key` + 仍 valid 的唯一索引）
4. 若存在名称在 `name + IFNULL(project_code,'')` 下冲突，先手工消重再执行。

### 执行

```bash
mysql -uroot -p < sql/upgrade-20260717-agent-supervisor-runops-model-center-v2.sql
```

### Agent Supervisor / RunOps 行为

1. 增加版本化 Agent Supervisor 配置和 Workflow-as-Tool 表，并删除旧的 Agent 内联配置列，不迁移旧数据。
2. 永久删除退役的静态 `runtime_agent_workflow_binding` 表及其数据。
3. 统一 Workflow Tool 风险语义、交互 Agent 身份、Supervisor 审批语义和 Agent Eval 表说明。
4. 将 Workflow Tool 固定到 ACTIVE Workflow 版本，删除没有可执行发布版本的记录，并重建 `runtime_run` 与通用 `runtime_trace_span`。
5. 删除退役的 `capability_project_instance.governance_policy_json`，保留 `capability_project_instance` 用于 SDK 心跳和 onboarding。

### Model Center V2 行为

- 对固定 31 个平台模板 id（`tpl-*`）执行 `INSERT ... ON DUPLICATE KEY UPDATE`，刷新目录字段；**保留已有 `enabled` 与 `created_at`**。用户自建非 `tpl-*` 模板不受影响。VALUES 与 `initV2.sql` 一致。
- 旧 V1：冲突预检后原子 `RENAME` 迁移；过程成功后自动删除临时旧表，不保留表级备份。
- 最终 V2 判定：核心列 = 7 **且** 非核心必需列 ≥ 12（含 `remark`）**且** 正确生成列 **且** 正确唯一索引。核心列缺失不会被误判为 final。
- 不完整 V2：核心列不全则在任何 DDL 前 `SIGNAL`（不猜测补齐）。冲突预检后按需 `ADD COLUMN`：`protocol`、`project_code`、`default_options_json`、`params_schema_json`、`status`、`last_test_*`、`remark`（`VARCHAR(512) DEFAULT NULL`）。生成列/索引修复顺序：若 `project_scope_key` 不是目标生成列 **或** 唯一索引不正确 → 先 `DROP INDEX uk_model_instance_name_scope`（即便当前索引形状 valid）→ 再 `DROP COLUMN` / `ADD` 正确生成列 → 最后按需重建唯一索引。禁止在同名索引仍引用该列时删列。
- 未知结构（有表但无 `connection_config_json` 且非 V1）：`SIGNAL`。
- 已是最终 V2：跳过实例迁移/修复，只做模板 upsert。
- 生成列检测使用 `EXTRA` + 规范化 `GENERATION_EXPRESSION`（不用 `IS_GENERATED`）。
- 脚本成功结束时自动删除旧脚本可能遗留的 `model_instance_v1_bak_20260716`。
- 静态防回归：`node scripts/check-model-center-v2-sql.mjs`。

### 验证

```sql
SHOW CREATE TABLE model_template;
SHOW CREATE TABLE model_instance;
SELECT COUNT(*) FROM model_template;
SELECT id, name, status, LEFT(connection_config_json, 16) FROM model_instance LIMIT 20;
```

脚本不保留表级回滚副本。如需回滚，恢复执行前创建的整库备份。

RunOps replay 使用来源运行记录的历史 Agent 配置和固定 Workflow 版本，不会静默解析当前 ACTIVE 版本。

该升级整体是破坏性的：不保留旧 Agent 内联配置、静态 Agent-Workflow 绑定、历史开发 Run/Span 数据、没有 ACTIVE Workflow 版本的 Tool 记录，也不保留旧模型表副本。执行前必须备份整个 `reach_ai`。MySQL DDL 会自动提交，脚本不提供全有或全无回滚。

如果已经执行过被替代的 20260713/20260714/20260716 脚本，不要仅为清理旧备份表而重复执行本脚本；除非明确接受在可丢弃开发/测试库上再次进行破坏性重建。

全新环境直接使用当前 `sql/initV2.sql`。

## Upgrade: 20260719 Runtime internal auth nonce

已有开发/测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260719-runtime-internal-auth-nonce.sql
```

影响：

1. 新增 `runtime_internal_auth_nonce`：Control→Runtime HMAC 内部认证 nonce 防重放表（多实例共享）。
2. Control/Runtime 需配置同一 `REACHAI_INTERNAL_SERVICE_SECRET`；缺配置时内部 trusted execute fail-closed。

验证：

```sql
DESC runtime_internal_auth_nonce;
SHOW INDEX FROM runtime_internal_auth_nonce;
```

## Upgrade: 20260718 Workflow INTERACTION resume

已有开发/测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260718-workflow-interaction-resume.sql
```

影响：

1. `runtime_run` / `runtime_trace_span` 状态注释增加 `WAITING_USER`（用户交互等待）；`WAITING_APPROVAL` 仍专用于 Supervisor 策略确认/真审批。
2. `runtime_interaction_session` 升级为 GraphSpec-native Workflow 交互会话：增加 `source_type`、`workflow_id`、`workflow_version_id`、`graph_spec_snapshot_json`、`revision`、`idempotency_key`、所有权字段与 continuation。
3. `composition_qualified_name` 改为可空；历史 composition 行保留，`source_type` 回填为 `COMPOSITION`。新 Workflow 暂停必须持久化 snapshot，恢复不得读取最新 Workflow。

验证：

```sql
DESC runtime_interaction_session;
SHOW INDEX FROM runtime_interaction_session;
SHOW FULL COLUMNS FROM runtime_run LIKE 'status';
```

## Upgrade: 20260709 AI Coding access default

Run `sql/upgrade-20260709-ai-coding-access-default.sql` on existing development or test databases that already have `capability_scan_project` rows. It adds missing AI Coding access columns and backfills generated `aic_...` keys with `ai_coding_access_enabled = 1` only for rows that previously had no key.
