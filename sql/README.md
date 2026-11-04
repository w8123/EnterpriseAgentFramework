# 数据库

`initV2.sql` 是当前新库 schema、索引和必要种子的唯一基线。五服务共用 MySQL，每张表只有一个 [owner](../docs/contracts/tables.md)。

## 新环境

服务连接到空库时会自动按内置的 `initV2.sql` 建表（多个服务同时启动时用数据库锁保证只执行一次），一般不需要手动执行。需要手动初始化时，确认数据库为空后，在支持文件重定向的 Bash 或 CMD 中执行：

```bash
mysql --default-character-set=utf8mb4 -h localhost -u root -p < sql/initV2.sql
```

脚本创建并使用 `reach_ai`。PowerShell 不通过默认编码管道转发 SQL；凭据交互输入或由受保护配置提供，不写进命令历史。

## 已有环境

先比较实际 schema、种子与当前基线，再选择适用脚本。不要按文件名批量执行，不在已有库重跑初始化；执行条件、依赖和数据影响以各 SQL 文件头部及实际结构为准。

- [upgrade-20261019-consolidated.sql](upgrade-20261019-consolidated.sql)
- [upgrade-20261019-development-schema-reconcile.sql](upgrade-20261019-development-schema-reconcile.sql)
- [upgrade-20261023-runtime-run-started-index.sql](upgrade-20261023-runtime-run-started-index.sql)
- [upgrade-20261024-ai-tool-connections.sql](upgrade-20261024-ai-tool-connections.sql)
- [upgrade-20261024-employee-access-project-id.sql](upgrade-20261024-employee-access-project-id.sql)
- [upgrade-20261024-platform-manager-role.sql](upgrade-20261024-platform-manager-role.sql)
- [upgrade-20261025-employee-write-approval.sql](upgrade-20261025-employee-write-approval.sql)
- [upgrade-20261025-remove-embed-renderer-catalog.sql](upgrade-20261025-remove-embed-renderer-catalog.sql)
- [upgrade-20261028-knowledge-internal-auth-nonce.sql](upgrade-20261028-knowledge-internal-auth-nonce.sql)
- [upgrade-20261028-platform-ai-connections.sql](upgrade-20261028-platform-ai-connections.sql)
- [upgrade-20261028-remove-source-code-scan.sql](upgrade-20261028-remove-source-code-scan.sql)
- [upgrade-20261029-platform-ai-approvals.sql](upgrade-20261029-platform-ai-approvals.sql)
- [upgrade-20261029-merge-operator-into-manager.sql](upgrade-20261029-merge-operator-into-manager.sql)
- [upgrade-20261030-personal-memory-groups.sql](upgrade-20261030-personal-memory-groups.sql)
- [upgrade-20261101-project-request-nonce.sql](upgrade-20261101-project-request-nonce.sql)
- [upgrade-20261102-embed-business-session-revocation.sql](upgrade-20261102-embed-business-session-revocation.sql)
- [upgrade-20261102-embed-session-login-binding.sql](upgrade-20261102-embed-session-login-binding.sql)
- [upgrade-20261102-personal-memory-owner-domain.sql](upgrade-20261102-personal-memory-owner-domain.sql)
- [upgrade-20261102-personal-memory-identity-links.sql](upgrade-20261102-personal-memory-identity-links.sql)
- [upgrade-20261102-skill-version-display-name.sql](upgrade-20261102-skill-version-display-name.sql)
- [upgrade-20261104-model-internal-auth-nonce.sql](upgrade-20261104-model-internal-auth-nonce.sql)：模型服务签名鉴权上线前新增防重放表，并同时升级三个调用服务。
- [upgrade-20261104-project-business-page-url.sql](upgrade-20261104-project-business-page-url.sql)：为项目增加可选的业务系统页面地址。

MySQL DDL 可能隐式提交。执行前核对目标与恢复路径；执行后回读表、列、索引、必要种子及中文，确认业务配置保留。升级完成不自动更新发布 pin、重启进程或证明业务验收通过。

## 修改规则

schema、索引、种子和字段语义变化同步 `initV2.sql`；已有环境需要升级时新增有明确影响说明的幂等 upgrade SQL，核对 MySQL 5.7/8 语法及 owning service。不要为了兼容未知旧数据建立永久双读或双写。
