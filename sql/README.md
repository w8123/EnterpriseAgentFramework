# 数据库初始化脚本

`sql/init.sql` 是 Enterprise Agent Framework 的唯一 SQL 基线入口，覆盖 `ai-skills-service`、`ai-model-service`、`ai-agent-service` 当前运行所需的表结构、兼容补列、兼容补索引和必要种子数据。

历史上分散在各 service 的升级补丁 SQL 已合并进本脚本并清理。首次上线、测试环境重建、老库对齐基线，都只执行本文件。

## 执行方式

```bash
mysql -uroot -p < sql/init.sql
```

脚本按 `ai_text_service` 数据库执行，并保持幂等：

1. 建库：`CREATE DATABASE IF NOT EXISTS ai_text_service`。
2. 建表：统一使用 `CREATE TABLE IF NOT EXISTS`。
3. 补列 / 补索引：通过 `information_schema` 判空后执行。
4. 种子数据：模型实例种子使用 `INSERT IGNORE`，默认 `DISABLED`，不会覆盖已经人工配置过的模型实例。
5. 兼容清理：对历史 `model_name` / `embedding_model` 字段做 best-effort 回填后再清理。

## 当前覆盖范围

- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Skill 定义、Skill 草稿、Skill 评估、交互式 Skill 挂起恢复。
- Agent Studio 定义、版本、发布事件、运行时配置、GraphSpec、评测 MVP、Trace。
- Tool 调用日志、Tool ACL、Tool 语义检索设置、护栏日志。
- SlotExtractor、DomainClassifier、MCP、A2A、注册中心、市场资产、工作流凭证。
- 模型实例中心 `ai_model_instance` 及常用模型实例种子。

## 部署规则

- 全新环境：直接执行 `sql/init.sql`。
- 老环境：先备份 `ai_text_service`，再执行 `sql/init.sql` 对齐缺失表、列、索引和兼容字段。
- 不再执行 `ai-agent-service/sql`、`ai-model-service/sql`、`ai-skills-service/sql` 下的历史补丁；这些目录已清理。
- 后续 schema 变更必须直接维护根 `sql/init.sql`，或正式引入 Flyway / Liquibase 后把本文件作为 baseline。

## 建议验证

执行后至少抽样检查：

```sql
SHOW TABLES LIKE 'ai_model_instance';
DESC agent_definition;
DESC scan_project_tool;
DESC slot_extract_log;
SHOW INDEX FROM mcp_client;
```
