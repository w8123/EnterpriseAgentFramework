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
4. 种子数据：模型实例种子使用 `INSERT IGNORE`；Agent/Workflow 种子只在稳定键不存在时插入。

## 覆盖范围

- Agent 入口：`runtime_agent`。
- Workflow 编排：`runtime_workflow`、`runtime_workflow_version`、`runtime_agent_workflow_binding`。
- 注册中心、项目实例、能力快照、字段级 diff、review/apply。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Capability 资产、交互式能力挂起恢复。
- Trace、RunOps、Tool ACL、Guard、SlotExtractor、DomainClassifier。
- MCP、A2A、Gateway、市场资产、嵌入式对话。
- 模型实例中心：`model_instance`。
- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- Context Governance 相关表。

## 升级规则

任何 schema、索引、种子数据或字段语义变化，都必须先：

1. 修改 `sql/initV2.sql`，保证全新环境直接可用。
2. 如果已有开发/测试库需要升级，再新增当次 `sql/upgrade-YYYYMMDD-short-name.sql`；当前 V2 新库重建场景不要求提供旧数据迁移。
3. 更新本文件或相关文档中的执行说明。

根目录 `sql/` 当前保留 `initV2.sql` 和本说明。后续真实数据库变更仍需新增当次 upgrade 脚本；该脚本在确认合并进下一版基线后，可以按同样规则清理。

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
DESC capability_scan_project_tool;
SHOW INDEX FROM control_mcp_client;
```

文档/规则改动时可运行：

```powershell
node scripts/check-backend-boundary-naming.mjs
git diff --check
```
