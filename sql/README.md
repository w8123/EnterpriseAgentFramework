# 数据库初始化与升级

ReachAI 当前五个服务共用 `reach_ai` MySQL 库，支持 MySQL 5.7 / 8，字符集使用 `utf8mb4`。表归属见[服务表所有权](../docs/architecture/service-table-ownership.md)：`reachai-control-service`、`reachai-runtime-service`、`reachai-capability-service`、`reachai-knowledge-service`、`reachai-model-service` 各自维护所属表。

## 选择执行入口

| 使用场景 | 执行文件 |
| --- | --- |
| 首次获取项目，初始化全新数据库 | 只执行 [initV2.sql](./initV2.sql) |
| 已有库已完成 2026-10-15 及以前全部变更，补齐 10 月 16–18 日升级 | 执行 [upgrade-20261019-consolidated.sql](./upgrade-20261019-consolidated.sql) |
| 已核对旧开发库，缺口为近期升级及本文列出的旧对象退场 | 先执行近期合并升级，再执行[开发库补充收口](./upgrade-20261019-development-schema-reconcile.sql) |
| 已有库补概览页 / 运行中心的运行时间索引（2026-10-23） | 执行 [upgrade-20261023-runtime-run-started-index.sql](./upgrade-20261023-runtime-run-started-index.sql) |
| 已有库补员工个人 AI 工具连接（2026-10-24） | 执行 [upgrade-20261024-ai-tool-connections.sql](./upgrade-20261024-ai-tool-connections.sql) |
| 已执行上一行的库补员工可用发布的项目 id（2026-10-24） | 执行 [upgrade-20261024-employee-access-project-id.sql](./upgrade-20261024-employee-access-project-id.sql)，然后在「员工使用」里重新保存 |
| 已有库把审计员换成管理人员（2026-10-24） | 执行 [upgrade-20261024-platform-manager-role.sql](./upgrade-20261024-platform-manager-role.sql)；执行后不要再重跑合并升级段落 08 |
| 更老的库，或无法确认前置结构 | 先按[历史版本说明](#历史版本)处理，不能直接套用近期升级 |

当前目录保留上述 SQL 和本说明。新库初始化已包含升级后的最终表、列、索引和必要种子，不需要再执行升级脚本。

## 新库初始化

在仓库根目录执行，MySQL 客户端交互输入密码。以下重定向命令在 Bash 或 CMD 执行；Windows PowerShell 可先进入 CMD，避免使用默认编码管道传 SQL：

```bash
mysql --default-character-set=utf8mb4 -uroot -p < sql/initV2.sql
```

脚本创建并使用 `reach_ai`，覆盖五服务所需结构与必要种子。已有库通过升级入口演进，避免重跑初始化脚本重置既有种子或设置。应用连接配置与服务启动见[项目说明](../README.md)。

## 已有库近期升级

合并脚本保留 2026-10-16 至 2026-10-18 共 10 份来源的完整执行逻辑，并按日期、文件名排序。每段记录原文件名及 UTF-8 / LF 内容摘要，便于核对。

| 段落 | 变更 | 对已有数据的影响 |
| --- | --- | --- |
| 01 | HTTP API 项目默认连接与单 API 覆盖 | 既有连接仍按单 API 覆盖生效；旧试调用及已发布 Workflow 的连接修订引用需刷新或重新发布 |
| 02 | 模型目录分析模型设置 | 新增可空模型选择列，空值继续使用环境配置 |
| 03 | 模型厂商账号 | 新建账号表与实例引用；不搬迁实例原连接配置 |
| 04 | 平台管理员调用权限 | 补充 `PLATFORM_ADMIN / ALL / * / ALLOW`；既有 DENY 仍优先 |
| 05 | Agent 记忆策略 | 会话记忆保持默认开启，个人长期记忆默认关闭；按需修改并重新发布 Agent |
| 06 | 业务索引回源改名 | 列、索引改名，保留开关值 |
| 07 | 个人记忆独立化 | 新建个人记忆表，保留运行时用户映射；丢弃旧个人记忆、候选、审计、outbox 及其搜索投影 |
| 08 | 平台角色与权限 | 迁移旧角色绑定后删除旧角色；重置四个内置角色权限，自定义角色需按新权限码配置 |
| 09 | 运行中心清理 | 删除旧评测 v1 四表，补齐自动化四表、会话查询索引及业务用户显示名列 |
| 10 | 知识模块清理 | 新增检索范围与命中来源，删除失效列及标签/问题表；已有知识库默认 `ALL_CALLERS` |

执行步骤：

1. 确认目标库已完成 2026-10-15 及以前全部变更，检查 `SELECT DATABASE(), VERSION(), @@character_set_database, @@character_set_connection`。
2. 备份 `reach_ai` 的结构和数据，停止旧版相关服务及写入。段落 07–10 包含数据丢弃、权限重置和删列，先核对上表影响。
3. 在仓库根目录执行整份升级，遇错立即停止，不使用 `--force`：

   ```bash
   mysql --default-character-set=utf8mb4 -uroot -p reach_ai < sql/upgrade-20261019-consolidated.sql
   ```

4. 检查各段回读和脚本文末汇总：新增或补齐的 11 张表、11 列和 3 个索引全部返回，退役表/列/角色/权限计数为 0，四个系统角色及管理员调用规则存在。回读角色名称等中文，连续 `?` 视为失败。
5. 核对业务数据、角色绑定与调用权限，部署配套 Control / Runtime / Knowledge / Model；根据需要重新发布受连接修订影响的 Workflow，调整知识库检索范围。

MySQL DDL 隐式提交，整份升级无法通过事务整体回滚；恢复依赖执行前备份。脚本保留来源的重跑行为：个人记忆旧投影只在旧表存在时清空，内置角色权限仍会重新设置，因此不要作为日常修复反复执行。

源码静态检查和解析通过不等于目标数据库升级、数据回读或业务验收通过。

## 已核对旧开发库的补充收口

[upgrade-20261019-development-schema-reconcile.sql](./upgrade-20261019-development-schema-reconcile.sql) 用于已完成结构比对的旧开发库，必须先完成近期合并升级。它删除旧领域两表和无消费者的检索设置表、项目旧 OpenAPI 快照及默认领域列、旧调用可见性/轻量标记、会话旧记忆标记和 Agent 接入类型列；重建 Agent 项目索引，并按基线补齐两处标题非空约束。项目、能力资产、模型、凭据、Agent / Workflow 及其版本和配置行保留，空标题仅回退到原名称。

本入口不是任意旧版本的自动修复：缺少其他基础表、字段或授权前置结构时，仍需先按实际差异设计迁移。执行后回读脚本末尾三项计数应为 0，Agent 项目索引列应为 `project_code,enabled`；同时重新比对完整结构并核对配置数据与引用。

## 历史版本

2026-10-15 及以前的升级脚本已从当前目录删除。其最终结构与必要种子已进入 `initV2.sql`，被后续设计废弃的对象不再恢复。历史脚本与当时的执行记录保留在 Git 历史中，当前合并脚本只覆盖 10 月 16–18 日。

需要保留更老数据库的数据时，从匹配版本的 Git 历史取回相应迁移链，单独核对前置条件及破坏性影响；允许重建的开发/测试库可使用当前 `initV2.sql` 初始化。不要用近期升级补跑缺失的历史阶段，也不要把初始化脚本直接当作存量迁移。

## 后续维护

1. schema、索引、必要种子或字段语义变化时，同步维护 `initV2.sql`，保证新用户只需一次初始化。
2. 已有开发/测试库需要该变更时，新增当次 `upgrade-YYYYMMDD-short-name.sql`，注明前置结构、数据影响、部署顺序及回读方式。
3. 历史脚本在最终效果进入基线、当前升级窗口不再依赖后清理；近期脚本可按依赖顺序合并，同时更新执行入口、文档及校验工具。

当前保留窗口从 2026-10-16 开始；后续新增增量继续按当次变更日期记录。

## 源码验证

```bash
node scripts/check-consolidated-upgrade-sql.mjs
node --test scripts/check-consolidated-upgrade-sql.test.mjs
node scripts/check-model-center-v2-sql.mjs
node scripts/check-mcp-hub-sql.mjs
node scripts/check-service-table-ownership.mjs
node scripts/check-documentation.mjs
git diff --check
```
