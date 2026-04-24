# 数据库初始化脚本

本目录的 `init.sql` 是**首次上线唯一需要执行的 SQL**，合并了 `ai-skills-service/sql` 与 `ai-agent-service/sql` 的全部历史迁移到最终态。

## 执行方式

```bash
mysql -uroot -p < sql/init.sql
```

脚本幂等——可以重复执行，用于老库"对齐基线"。每次执行都会：
1. 建库（`ai_text_service`，若不存在）；
2. 建表（`CREATE TABLE IF NOT EXISTS`，已存在的表不会被重建）；
3. 补列 / 补索引（走 `information_schema` 判空的存储过程，已有的列/索引会被跳过）；
4. `side_effect` 回填（仅覆盖 `NULL / 空 / 默认 WRITE` 的记录，**不会覆盖人工校准值**）。

## 覆盖的历史脚本清单

| 原始脚本 | 归入章节 | 说明 |
| --- | --- | --- |
| `ai-skills-service/sql/init.sql` | §一 | v1 基础表（知识库 / 文件 / chunk / 权限 / tool / scan_project） |
| `ai-skills-service/sql/upgrade_v2.sql` | §一 | v2 为 `knowledge_base` / `file_info` 加了 chunk 策略和 raw_text；合并到最终建表语句 |
| `ai-skills-service/sql/business_index_v3.sql` | §二 | v3 业务语义索引三张表 |
| `ai-skills-service/sql/tool_definition_v4.sql` | §四 | v4 实际与 v1 重复，丢弃重复 CREATE |
| `ai-skills-service/sql/scan_project_v5.sql` | §四 | v5 为 `tool_definition` 加 `project_id`；合并到最终建表语句 |
| `ai-skills-service/sql/semantic_docs_v6.sql` | §三、§四 | v6 新增 `scan_module` / `semantic_doc`，并给 `tool_definition` 加 `ai_description` / `module_id` |
| `ai-skills-service/sql/scan_project_tool_v7.sql` | §三 | v7 `scan_project_tool` 表 |
| `ai-agent-service/sql/tool_call_log_v8.sql` | §五 | v8 Agent 调用审计日志主表 |
| `ai-agent-service/sql/skill_phase2_0.sql` | §四 | Phase 2.0 给 `tool_definition` 加 `kind / spec_json / side_effect / skill_kind` + 复合索引 |
| `ai-agent-service/sql/tool_call_log_index_phase2_0_1.sql` | §五 | Phase 2.0.1 为 `tool_call_log` 补 3 个索引 |
| `ai-agent-service/sql/backfill_side_effect.sql` | §七 | Phase 2.0.1 历史 tool `side_effect` 回填 |
| `ai-agent-service/sql/skill_mining_phase2_1.sql` | §六 | Phase 2.1 `skill_draft` + `skill_eval_snapshot` |

## 部署场景

### 场景 A：全新环境
直接跑 `init.sql`，一次到位。

### 场景 B：老环境已经跑过 `ai-skills-service/sql/init.sql`
建议也跑一次 `init.sql`：
- 所有表的 `CREATE TABLE IF NOT EXISTS` 会被跳过；
- 缺的列（例如 `kind / spec_json / side_effect` 等 Phase 2 字段）会被 `add_col_if_absent` 自动补齐；
- 缺的索引同理；
- `side_effect` 回填会把历史 `NULL/空/默认 WRITE` 的记录按推断规则重算。

**执行前建议先备份 `ai_text_service` 整库**（以防一下操作产生的 UPDATE 超出预期）。

### 场景 C：已经在生产跑了一段时间、有人工调整过 `side_effect`
`side_effect` 回填 UPDATE 只覆盖 `NULL / 空 / 默认 WRITE` 三种情况，**不会覆盖人工调成 `READ_ONLY / IDEMPOTENT_WRITE / IRREVERSIBLE` 的记录**。放心跑。

## 不在本脚本内的内容

- **Milvus collection** 的建表/字段变更：由应用启动时的 `ToolEmbeddingService` 自动维护；向量回灌需手动调 `POST /api/tools/retrieval/rebuild`（详见 `docs/Phase2.0-SubAgentSkill-落地验收清单.md`）。
- 业务示例数据（Agent 定义、示例工具等）：由应用自带的 Seeder 或运营手动录入。
- Redis / 消息队列等其它存储的初始化：按各自服务 README。

## 后续增量变更

首次上线后，后续的 schema 变更建议：
1. 小于 20 行的增量改动 → 直接追加一段带存储过程的幂等 SQL 片段到本文件末尾；
2. 较大改动 → 引入 Flyway / Liquibase，把 `init.sql` 作为 `V1__baseline.sql`。
