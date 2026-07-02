# 数据库初始化脚本

## 唯一基线

`sql/init.sql` 是 ReachAI 的唯一 SQL 基线入口，覆盖当前物理服务拆分主路径运行所需的表结构、补列、补索引和必要种子数据。历史 `upgrade-*.sql` 已核对并并入当前基线，不再作为长期保留的脚本清单。

当前主路径服务：

- `reachai-control-service`
- `reachai-runtime-service`
- `reachai-capability-service`
- `reachai-knowledge-service`
- `reachai-model-service`

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已删除；其中遗留表仍保留在统一基线中，由新的 owning service 逐步接管或退场。

## 执行方式

```bash
mysql -uroot -p < sql/init.sql
```

脚本按 `ai_text_service` 数据库执行，并保持幂等：

1. 建库：`CREATE DATABASE IF NOT EXISTS ai_text_service`。
2. 建表：统一使用 `CREATE TABLE IF NOT EXISTS`。
3. 补列 / 补索引：通过 `information_schema` 判空后执行。
4. 种子数据：模型实例种子使用 `INSERT IGNORE`；Agent/Workflow 种子只在稳定键不存在时插入。

## 覆盖范围

- Agent 入口：`ai_agent`。
- Workflow 编排：`ai_workflow`、`ai_workflow_version`、`ai_agent_workflow_binding`。
- 注册中心、项目实例、能力快照、字段级 diff、review/apply。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Skill 兼容存储、Capability 资产、交互式能力挂起恢复。
- Trace、RunOps、Tool ACL、Guard、SlotExtractor、DomainClassifier。
- MCP、A2A、Gateway、市场资产、嵌入式对话。
- 模型实例中心：`ai_model_instance`。
- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- Context Governance 相关表。

## 升级规则

任何 schema、索引、种子数据或字段语义变化，都必须先：

1. 修改 `sql/init.sql`，保证全新环境直接可用。
2. 如果已有开发/测试库需要升级，新增当次 `sql/upgrade-YYYYMMDD-short-name.sql`，保证存量环境可升级。
3. 更新本文件或相关文档中的执行说明。

历史升级脚本清理后，根目录 `sql/` 默认只保留 `init.sql` 和本说明。后续真实数据库变更仍需新增当次 upgrade 脚本；该脚本在确认合并进下一版基线后，可以按同样规则清理。

不再执行或新增任何历史 service-level SQL 目录。不要在各服务目录下恢复独立迁移入口；当前唯一入口仍是根目录 `sql/`。

## 破坏性变更

项目默认不为旧数据做复杂兼容迁移。如果升级脚本会清理、重建、重命名或丢弃历史字段/数据，必须在 SQL 注释和最终变更说明中写清影响。

## 建议验证

执行后至少抽样检查：

```sql
SHOW TABLES LIKE 'ai_agent';
SHOW TABLES LIKE 'ai_workflow';
DESC ai_agent;
DESC ai_workflow;
DESC scan_project_tool;
SHOW INDEX FROM mcp_client;
```

文档/规则改动时可运行：

```powershell
node scripts/check-backend-boundary-naming.mjs
git diff --check
```
