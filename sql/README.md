# 数据库初始化脚本

## 当前基线

`sql/initV2.sql` 是 ReachAI 当前新库 SQL 基线入口，覆盖当前物理服务拆分主路径运行所需的表结构、补列、补索引和必要种子数据。当前阶段仍使用一个 MySQL 库，不拆库；表名按 owning service / domain 前缀收口。旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。

当前主路径服务：

- `reachai-control-service`
- `reachai-runtime-service`
- `reachai-capability-service`
- `reachai-knowledge-service`
- `reachai-model-service`

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已删除；当前基线面向新库重建，不兼容旧表名，也不提供旧数据迁移脚本。

## 执行方式

全新数据库只执行当前基线：

```bash
mysql -uroot -p < sql/initV2.sql
```

从上一版 GitHub 基线 `ae9e1ce6` 升级当前开发/测试库，只执行本仓库当前唯一升级入口：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260830-platform-consolidated.sql
```

不要把 `initV2.sql` 和升级脚本连续执行到同一个库，也不要把设计文档中记录的历史
`upgrade-*.sql` 文件名当作当前入口。早于 `ae9e1ce6` 的数据库应从对应 Git tag 获取当时的迁移链，
或备份后按当前 `initV2.sql` 重建；`upgrade-20260830-platform-consolidated.sql` 不是全历史迁移包。

任何真实数据库执行都必须先确认 `SELECT DATABASE()`、版本、字符集和备份状态，并获得目标环境授权。
本次脚本整理没有连接或修改数据库。

## 覆盖范围

- Agent 聚合：`runtime_agent`、`runtime_agent_config_version`、固定 Workflow 发布版本的 `runtime_agent_workflow_tool`。
- Workflow 编排：`runtime_workflow`、`runtime_workflow_version`、`runtime_workflow_resource_binding`。
- 注册中心、项目实例、能力快照、字段级 diff、review/apply。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Capability 资产、交互式能力挂起恢复。
- API 市场公开目录、来源与验证证据、不可变版本/Operation，以及不含任何调用凭据的项目接入意图。
- RunOps 根运行事实 `runtime_run`、通用子事件 `runtime_trace_span`、Tool 调用、Tool ACL、Guard、DomainClassifier。
- Runtime Automation：不可变定义版本、CRON/ONCE 持久时钟、幂等 occurrence、尝试与审计事件、命令 outbox、跨副本并发槽；Agent/Workflow 执行固定已发布版本并进入 RunOps。
- MCP Hub、A2A Hub、Gateway、市场资产、嵌入式对话。MCP Hub 包含对外发布单元、草稿发布条目（引用能力或已发布 Workflow）、不可变发布修订（tools/list 事实源）、按项目/环境/租户挂接单一发布单元的 Client 凭证与双向调用审计（direction OUTBOUND/INBOUND）；原文正文默认不保留，启用后使用 MEDIUMTEXT 承载经脱敏且限长的保留期数据。旧 Phase P2 暴露白名单与平铺 Client 模型已按 clean-slate 退场；已有库升级见当前合并脚本 Section 14。A2A Hub 包含不可变本地发布/远端修订、Principal/Trust/Credential、Context/Task/Message/Artifact/Event、出站 Task 接受快照与分布式查询租约、无原文 transport audit、conformance 与 durable outbox。
- 标准 Agent Skill 包治理：`control_agent_skill`、不可变版本与人工审核；包字节由 Control 的 `AgentSkillArtifactStore` 保存，不回退为旧 Skill 业务资产或 Capability 类型。
- 业务页面工作台：`control_project_page`、页面资源/动作/分析结果，以及版本化 AI Coding 任务、问题、事件和报告。
- AI Coding 凭据策略：`control_ai_coding_project_policy`，未配置项目使用平台默认 72 小时。
- 模型中心 V2：`model_template` 发布目录、`model_instance` 可执行实例，以及官方来源、每日租约同步、不可变快照和候选变更证据；全新库可不含实例种子。
- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- Context Governance 相关表。
- Runtime 企业会话状态目录、完整会话事件账本、租户保留策略和无正文生命周期审计：`runtime_conversation_session`、`runtime_conversation_event`、`runtime_session_retention_policy`、`runtime_session_retention_audit`；AgentScope 状态正文仍由 JSON（开发）或 Redis（生产）保存。
- Runtime 上下文工程短期工件：`runtime_tool_result_artifact` 只保存作用域摘要与 AES-GCM 密文，过期或清空会话后擦除，不作为个人长期记忆。
- EvalOps：服务端目标快照、不可变数据集/评分器版本、基线与候选实验、逐项 Score，以及 MySQL 5.7 兼容的可恢复任务租约。旧 `runtime_agent_eval_*` 只保留兼容入口。
- Managed Executor：Runtime-owned execution、严格顺序事件、对象存储 Artifact 元数据与 durable outbox；Worker 只持有单 execution 短期 token，不获得共享服务 Secret 或数据库账号。
- 个人长期记忆使用 Control canonical context 表；`control_context_memory_outbox` 将变更可靠投影到 Knowledge-owned `knowledge_personal_memory_index` 和幂等事件账本。Knowledge 可异步生成版本化 float32 embedding，并在 owner 配额边界内执行精确向量/混合排序；该投影仍不是事实源。
- 企业擦除使用 Control-owned `control_memory_erasure_request` / `control_memory_erasure_domain` 持久协调九个责任域。五个自动域完成后擦除 raw Runtime user，四个人工域必须提交外部证据；Legal Hold 和 `RETAINED_LEGAL` 保持显式，不能把部分成功报告为全部删除。

## 升级规则

任何 schema、索引、种子数据或字段语义变化，都必须：

1. 同步修改 `sql/initV2.sql`，保证全新环境直接可用。
2. 已有开发/测试库确需升级时，新增一份当次 `sql/upgrade-YYYYMMDD-short-name.sql`。
3. 在脚本注释和文档中写清前置版本、破坏性影响、执行顺序和回读门禁。
4. 只有在升级已进入 GitHub 历史、最终状态已并入基线且不再作为当前发布入口后，才能删除旧脚本。

根目录 `sql/` 当前只保留：

- `initV2.sql`：全新数据库基线。
- `upgrade-20260830-platform-consolidated.sql`：从 GitHub 基线 `ae9e1ce6` 升级当前版本的唯一增量脚本。
- `README.md`：执行和清理规则。

## 当前合并升级

`upgrade-20260830-platform-consolidated.sql` 按以下固定顺序合并了尚未提交 GitHub 的 14 份脚本：

| Section | 范围 | 关键约束 |
| --- | --- | --- |
| 01 | A2A Hub clean-slate replacement | 破坏性；旧 endpoint/call-log/task 结构不迁移 |
| 02 | 标准 Agent Skill 目录 | 先于 Skill 市场 |
| 03 | API 市场 | 不保存第三方调用凭据 |
| 04 | Agent Skill 市场 | 依赖 Section 02 |
| 05 | Eval 服务端目标快照 | 先于 EvalOps |
| 06 | EvalOps 实验与租约任务 | 依赖 Section 05 |
| 07 | Managed Executor 控制面 | 默认 fail closed，不自动启用 Worker |
| 08 | Runtime Automation | 默认关闭，不自动启动调度 |
| 09 | Runtime checkpoint V1 | 历史断点保持 LEGACY |
| 10 | Model Catalog 同步 | 自动同步默认关闭 |
| 11 | 平台授权底座 P1 | 先于 P2/P3 |
| 12 | Runtime 管理面授权 P2 | 依赖 Section 11 |
| 13 | 企业账号角色管理 P3 | 依赖 Section 11/12 |
| 14 | MCP Hub clean-slate replacement | 破坏性；旧 visibility/client/call-log 数据不迁移 |

合并文件在每一段头部保留来源文件名和原始内容 SHA-256，便于审计合并是否丢段。MySQL DDL 会自动提交，
整份脚本不具备全有或全无回滚；Section 01 和 14 执行前必须单独确认备份与旧数据放弃边界。

## 执行前检查

```sql
SELECT DATABASE(), VERSION(), @@character_set_database, @@character_set_connection;

SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'control_a2a_endpoint',
    'control_a2a_call_log',
    'control_mcp_visibility',
    'control_mcp_client',
    'control_mcp_call_log')
ORDER BY table_name;
```

只有目标库明确为 `reach_ai`、数据库版本为 MySQL 5.7/8、连接与库字符集为 `utf8mb4`，并且已确认
A2A/MCP 旧数据处置后，才允许执行。不要在本地命令、日志、文档或 Git 中写入数据库密码。

## 执行后回读

至少验证：

```sql
SHOW TABLES LIKE 'control_a2a_%';
SHOW TABLES LIKE 'control_mcp_%';
SHOW CREATE TABLE runtime_eval_target_snapshot;
SHOW CREATE TABLE runtime_eval_experiment;
SHOW CREATE TABLE runtime_managed_execution;
SHOW CREATE TABLE runtime_automation;
SHOW CREATE TABLE model_catalog_sync_run;
SHOW COLUMNS FROM runtime_interaction_session LIKE 'checkpoint_schema_version';
SHOW COLUMNS FROM control_platform_role LIKE 'role_kind';

SELECT permission_code
FROM control_platform_permission
WHERE permission_code LIKE 'a2a-hub:%'
   OR permission_code LIKE 'mcp-hub:%'
   OR permission_code LIKE 'automation:%'
   OR permission_code LIKE 'agent:%'
   OR permission_code LIKE 'workflow:%'
   OR permission_code LIKE 'runops:%'
ORDER BY permission_code;
```

还必须确认旧 `control_a2a_endpoint`、`control_a2a_call_log`、`control_mcp_visibility` 和
`control_mcp_client.tool_whitelist_json` 已按预期退役，并回读中文种子数据，连续 `?` 视为失败。

## 源码静态验证

```bash
node scripts/check-backend-boundary-naming.mjs
node scripts/check-consolidated-upgrade-sql.mjs
node scripts/check-model-center-v2-sql.mjs
node scripts/check-mcp-hub-sql.mjs
git diff --check
```

静态检查、SQL 解析和测试通过都不能替代真实目标库执行、第二遍幂等验证及结构回读。
