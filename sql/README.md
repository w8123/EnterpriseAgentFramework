# 数据库初始化脚本

## Agent / Workflow 解耦新库

`sql/init2.sql` 是 Agent 与 Workflow 解耦后的新数据库初始化脚本。后续重构阶段如果使用全新数据库，优先执行：

```bash
mysql -uroot -p < sql/init2.sql
```

`init2.sql` 不迁移旧 `agent_definition.graph_spec_json` 或 `agent_version` 数据。它面向新库，直接创建 `ai_agent`、`ai_workflow`、`ai_workflow_version`、`ai_agent_workflow_binding` 以及页面能力 / Embed 会话所需的基础表。

旧 `sql/init.sql` 仍是当前旧模型基线，用于尚未切换到 Agent/Workflow 新模型的环境。

`sql/init.sql` 是睿池 ReachAI 的唯一 SQL 基线入口，覆盖 `ai-skills-service`、`ai-model-service`、`ai-agent-service` 当前运行所需的表结构、补列、补索引和必要种子数据。

历史上分散在各 service 的升级补丁 SQL 已合并进本脚本并清理。首次上线和测试环境重建执行本文件；已有开发/测试库需要按时间顺序执行根目录 `sql/upgrade-*.sql`。

## 执行方式

```bash
mysql -uroot -p < sql/init.sql
```

脚本按 `ai_text_service` 数据库执行，并保持幂等：

1. 建库：`CREATE DATABASE IF NOT EXISTS ai_text_service`。
2. 建表：统一使用 `CREATE TABLE IF NOT EXISTS`。
3. 补列 / 补索引：通过 `information_schema` 判空后执行。
4. 种子数据：模型实例种子使用 `INSERT IGNORE`，默认 `DISABLED`，不会覆盖已经人工配置过的模型实例；嵌入式页面助手等 Agent 种子只在 `key_slug` 不存在时插入。
5. 迭代清理：对历史 `model_name` / `embedding_model` 字段做 best-effort 回填后再清理。

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
- 已有开发/测试环境：先备份 `ai_text_service`，再按时间顺序执行根目录 `sql/upgrade-*.sql`。
- 班组档案嵌入式对话接入已有环境需执行 `sql/upgrade-20260529-team-archive-embedded-agent.sql`，插入 `team-archive-assistant` 页面动作 Agent。
- 旧 SDK / Starter 退役后的字段注释对齐需执行 `sql/upgrade-20260529-retire-legacy-sdk-comments.sql`。
- 前端 SDK 页面 / 页面动作目录接入已有环境需执行 `sql/upgrade-20260601-page-action-catalog.sql`，创建 `eaf_page_registry` 与 `eaf_page_action_registry`。
- 页面动作目录出现同一 `page_key` 重复页面时，执行 `sql/upgrade-20260602-page-registry-origin-dedupe.sql`，保留每组最近上报页面并将缺失 `origin` 归一为空字符串。
- AI 快速接入工作台步骤进度和 AI/CLI 回传需执行 `sql/upgrade-20260612-ai-access-session.sql`，创建 `eaf_ai_access_session` 与 `eaf_ai_access_step`，并补充 SDK 接入 / 页面助手接入场景、目标页面和回传元数据字段。
- 项目全局 AI 入口按当前页面自动加载能力需执行 `sql/upgrade-20260613-global-entry-page-key.sql`，为 `eaf_embed_session` 补充 `page_key` 并增加项目入口 Agent / 页面能力审计索引。
- 不再执行 `ai-agent-service/sql`、`ai-model-service/sql`、`ai-skills-service/sql` 下的历史补丁；这些目录已清理。
- 后续 schema 变更必须同时维护根 `sql/init.sql` 和一份新的 `sql/upgrade-YYYYMMDD-short-name.sql`，或正式引入 Flyway / Liquibase 后把本文件作为 baseline。
- 项目默认不为旧数据做复杂兼容迁移；如果升级脚本会清理、重建、重命名或丢弃历史字段 / 数据，必须在 SQL 注释和变更说明中明确写出。

## 建议验证

执行后至少抽样检查：

```sql
SHOW TABLES LIKE 'ai_model_instance';
DESC agent_definition;
DESC scan_project_tool;
DESC slot_extract_log;
SHOW INDEX FROM mcp_client;
```
