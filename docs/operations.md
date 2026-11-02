# 运维

安装、配域名、备份恢复、升级和常见问题的操作步骤见 [部署与运维手册](部署与运维手册.md)；本页是更细的环境、验收和准入条件。

## 环境与数据库

先明确目标环境、制品、配置、数据库与数据归属。新库可以执行 [sql/initV2.sql](../sql/initV2.sql)，也可以让服务首次启动时自动建表（连接到空库时按内置的 initV2.sql 初始化，`REACHAI_BOOTSTRAP_SCHEMA_INIT=false` 关闭）；已有库按 [SQL 说明](../sql/README.md)选择适用升级；不在已有库重跑初始化。结构变更需核对备份、DDL 隐式提交和恢复路径，执行后回读表、列、索引、种子与中文。

配置写在 `reachai.env`（模板见仓库根目录 [reachai.env.example](../reachai.env.example)）或同名环境变量。非 Kubernetes 环境首次在空库上启动时，会在 `REACHAI_HOME`（默认 `reachai.env` 旁边的 `.reachai`，否则 `~/.reachai`）生成 `secrets.properties`，五个服务必须读同一个目录，并且要和数据库一起备份；Kubernetes 不会自动生成，必须从 Secret 注入。禁用正式环境不需要的本地登录与 bootstrap。内部签名、模型凭据、工作流凭据、存储身份和会话策略分别管理；不把开发专用值投入生产。

平台 AI 业务方法和 HTTP API 写入审核另需为 Control 配置 `REACHAI_PLATFORM_AI_APPROVAL_KEY_ID` 和 `REACHAI_PLATFORM_AI_APPROVAL_KEY_BASE64`；后者是安全生成的 32 字节密钥的 Base64，须通过密钥管理注入并妥善备份，不自动生成或写入仓库。多实例必须使用同一密钥与标识；更换或丢失密钥会使尚未消费的审批无法解密，须重新申请。未配置时写入审批不可用。已有库先按 SQL 说明准备连接与审批表；上线后用真实账号核验审核、吊销、过期、来源或凭据漂移、并发消费及结果未知的查询。

## 部署顺序

1. 构建与验证对应制品，记录版本和配置；命令见[开发](development.md)。
2. 准备 MySQL 及按需开启的依赖（Milvus 用于知识库向量检索，Docling 用于文档解析，Redis 用于 Runtime 生产多实例会话状态，对象存储用于多实例知识库制品），完成本环境适用升级。
3. 启动 Model、Knowledge、Capability、Runtime、Control，核对进程归属、端口、制品新鲜度与健康。
4. 发布前端，代理管理端 `/api/**`、受保护 `/ai/**` 和 `/model/**` 到 Control，不暴露内部服务。
5. 用真实角色、业务系统和外部客户端执行验收，再决定具体能力是否开启。

```powershell
node scripts/check-local-service-artifact-freshness.mjs
node scripts/check-physical-service-smoke.mjs
```

## 业务验收

| 范围 | 必验条件 |
| --- | --- |
| 登录与权限 | Cookie 恢复、多标签页、欢迎门禁、CSRF、退出撤销、不可用恢复、无角色落地、项目隔离和只读按钮；接口仍拒绝越权 |
| SDK 与业务能力 | 项目签名、心跳、来源同步、接纳、只读试调用、写确认、UNKNOWN 原记录查询、变更后的拒绝与显式重发布 |
| Workflow / Agent | 当前修订、草稿调试、发布 pin、运行回查、暂停恢复、确认/拒绝/取消、无证据回答和真实模型链路 |
| Embed / 员工工具 | 白名单、用户/租户隔离、页面就绪、个人连接、写审批、真实业务操作人及同一 Trace |
| MCP / A2A | 实际协议客户端、固定发布、作用域拒绝、吊销、暂停、信任、取消与审计；不能只测管理接口 |
| 评测 | 同一目标版本、数据集版本、评分、发布门禁及 Trace；夹具与真实执行分别说明 |

`BUILD SUCCESS`、HTTP 200 和 health UP 不能单独证明以上业务结果。需要逐 token 输出的入口检查浏览器 Network 的实际增量，不只看 Content-Type。

## 专题准入

| 能力 | 开启前必须完成 |
| --- | --- |
| 标准 Skill | 真实外部包、范围权限、审核发布、Agent 精确绑定、下载摘要、撤销和缓存篡改；内置包同版本同字节 |
| 个人记忆 | canonical 与投影一致性、双用户隔离、分层分组、shadow 对照、负载、删除、跨域擦除与备份恢复；保留策略 / Legal Hold 生效 |
| Knowledge | MySQL / Milvus 联合验证、迟到写入回收、存储 IAM、版本删除、持久卷、重启与整机故障；准确物理身份，不扫描全桶 |
| 自动化 | 固定已发布目标、时区/DST、双实例租约、防重复触发、取消、恢复、有限重试及 RunOps 关联 |
| Managed Executor | 固定镜像、Kubernetes 资源、网络与凭据隔离、跨租户攻击、容量、Artifact 上限、超时、回收和事故恢复 |

仓库提供 `scripts/check-agent-skill-production-readiness.mjs`、`check-personal-memory-*-readiness.mjs` 与对应测试辅助核对；其输出仅证明实际检查范围。真实服务、用户身份和外部依赖仍需本环境验证。

## 自动化、评测与托管执行开关

Runtime 的 `REACHAI_AUTOMATION_ENABLED`、`REACHAI_EVAL_WORKER_ENABLED`、`REACHAI_MANAGED_EXECUTOR_ENABLED` 与 `REACHAI_MANAGED_EXECUTOR_AUTO_ROUTE_ENABLED` 各自控制能力，默认值与其他上限以服务 `application.yml` 为准。开启自动路由前先完成影子评测；配置版本不能绕过全局开关。

可复用评测数据位于 [evals/](evals/)。Managed Executor 影子评测不得产生真实执行、Job、审批或 Artifact；正例召回、误路由和调用次数按待开放场景设置明确门禁。

## 故障与恢复

- 先停止新增高风险执行或关闭相应开关，再定位失败对象；不批量删表或清理无法证明归属的资源。
- 自动化 occurrence、交互恢复和审批执行按幂等身份找回，不能靠重发写操作查询结果。
- 保留已发布版本和不可变审计，回滚显式切换可用版本；数据库与外部副作用单独处理。
- 回收器持续保存未知外部写入、精确对象/向量身份和重试进度；存储迁移不使旧回收记录失效。
- Artifact 与公共错误按安全摘要展示，避免日志、事件或备份泄漏凭据与业务内容。

本页是操作与验收条件，不记录某次部署结果；当前支持与未开放范围见[当前边界](status.md)。
