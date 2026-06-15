# 数据库初始化脚本

## 唯一基线

`sql/init.sql` 是睿池 ReachAI 的**唯一** SQL 基线入口，覆盖 `ai-skills-service`、`ai-model-service`、`ai-agent-service` 当前运行所需的表结构、补列、补索引和必要种子数据。

### Agent / Workflow 主模型

| 表 | 职责 |
|---|---|
| `ai_agent` | **唯一 Agent 主表**。智能体实例身份、入口、人设、项目归属、权限、模型偏好、治理策略。 |
| `ai_workflow` | Workflow 编排资产。承载 `graph_spec_json`（运行语义）与 `canvas_json`（画布布局）。 |
| `ai_workflow_version` | Workflow 发布版本与灰度快照。 |
| `ai_agent_workflow_binding` | Agent 到 Workflow 的路由关系（DEFAULT / PAGE / ROUTE / ACTION / INTENT / TASK / FALLBACK）。 |

`agent_definition`、`agent_version`、`agent_release_event` 已退役，不再作为运行、管理或发布主模型。

历史上分散在各 service 的升级补丁 SQL 已合并进本脚本并清理。首次上线和测试环境重建执行本文件；已有开发/测试库需要按时间顺序执行根目录 `sql/upgrade-*.sql`。

## 执行方式

```bash
mysql -uroot -p < sql/init.sql
```

脚本按 `ai_text_service` 数据库执行，并保持幂等：

1. 建库：`CREATE DATABASE IF NOT EXISTS ai_text_service`。
2. 建表：统一使用 `CREATE TABLE IF NOT EXISTS`。
3. 补列 / 补索引：通过 `information_schema` 判空后执行。
4. 种子数据：模型实例种子使用 `INSERT IGNORE`；Agent/Workflow 种子只在 `key_slug` 不存在时插入。
5. 迭代清理：对历史 `embedding_model` 字段做 best-effort 回填后再清理。

## 当前覆盖范围

- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Skill 定义、Skill 草稿、Skill 评估、交互式 Skill 挂起恢复。
- **Agent 入口（`ai_agent`）**、**Workflow 编排（`ai_workflow`）**、版本、绑定、Trace、评测 MVP。
- Tool 调用日志、Tool ACL、Tool 语义检索设置、护栏日志。
- SlotExtractor、DomainClassifier、MCP、A2A、注册中心、市场资产、工作流凭证。
- 模型实例中心 `ai_model_instance` 及常用模型实例种子。
- Embed 会话、页面注册、页面动作目录。

## 部署规则

- 全新环境：直接执行 `sql/init.sql`。
- 已有开发/测试环境：先备份 `ai_text_service`，再按时间顺序执行根目录 `sql/upgrade-*.sql`。
- **Agent 主模型切换**：执行 `sql/upgrade-20260615-agent-mainline-cleanup.sql` 创建新表并 drop 旧 `agent_definition` 栈。**不迁移旧数据**，如需保留请先备份。
- 班组档案嵌入式对话接入已有环境需执行 `sql/upgrade-20260529-team-archive-embedded-agent.sql`（历史脚本；新环境直接用 `init.sql` 种子）。
- 旧 SDK / Starter 退役后的字段注释对齐需执行 `sql/upgrade-20260529-retire-legacy-sdk-comments.sql`。
- 前端 SDK 页面 / 页面动作目录接入已有环境需执行 `sql/upgrade-20260601-page-action-catalog.sql`。
- 页面动作目录出现同一 `page_key` 重复页面时，执行 `sql/upgrade-20260602-page-registry-origin-dedupe.sql`。
- AI 快速接入工作台步骤进度和 AI/CLI 回传需执行 `sql/upgrade-20260612-ai-access-session.sql`。
- 项目全局 AI 入口按当前页面自动加载能力需执行 `sql/upgrade-20260613-global-entry-page-key.sql`。
- 不再执行 `ai-agent-service/sql`、`ai-model-service/sql`、`ai-skills-service/sql` 下的历史补丁；这些目录已清理。
- 后续 schema 变更必须同时维护根 `sql/init.sql` 和一份新的 `sql/upgrade-YYYYMMDD-short-name.sql`。
- 项目默认不为旧数据做复杂兼容迁移；如果升级脚本会清理、重建、重命名或丢弃历史字段 / 数据，必须在 SQL 注释和变更说明中明确写出。

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
