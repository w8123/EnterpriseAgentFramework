# 数据库初始化脚本

## 当前基线

2026-10-17 平台角色收口（[人员角色与权限重设计方案](../docs/plans/人员角色与权限重设计方案.md) R1）：内置角色改为平台管理员 `PLATFORM_ADMIN`、开发人员 `DEVELOPER`、运营人员 `OPERATOR`、审计员 `AUDITOR`。`AGENT_DESIGNER` 的账号绑定迁到 `OPERATOR`，`PROJECT_OWNER` 迁到 `OPERATOR` + `DEVELOPER`（保留原授权范围）后删除这两个角色；原 `OPERATOR`（运维操作员）就地改为运营人员，权限扩大。新增 `knowledge:*`、`model:*`、`tool-acl:*`、`embed:*` 权限码，知识库、模型、调用权限和嵌入配置不再由 `platform:read/write` 放行；删除无代码使用的 `workspace:*` 和 `context:runtime-user:review`。四个内置角色权限整体重置，自定义角色若需要知识库、模型等功能须重新勾选。不改表结构。新库基线已包含；已有开发/测试库备份四张平台角色表后执行 [升级脚本](./upgrade-20261017-platform-roles-redesign.sql)，可重复执行，需在部署新版 Control 之前执行，执行后用户重新登录生效。

2026-10-17 业务索引回源改名（[记忆模块重设计方案](../docs/plans/记忆模块重设计方案.md) M4）：`knowledge_business_index.agent_memory_enabled` 改名为 `agent_reference_enabled`，索引 `idx_biz_agent_memory` 改名为 `idx_biz_agent_reference`，数据保留。新库基线已包含；已有开发/测试库执行 [升级脚本](./upgrade-20261017-business-index-reference-naming.sql)，可重复执行，需在部署新版 Knowledge 之前执行。

2026-10-17 个人记忆独立化（[记忆模块重设计方案](../docs/plans/记忆模块重设计方案.md) M2）：新建 `control_personal_memory`、`control_personal_memory_candidate`、`control_personal_memory_audit`、`control_personal_memory_outbox`，删除 `control_context_*` 七张上下文治理旧表，旧个人记忆数据直接丢弃并清空 Knowledge 搜索投影；`control_context_runtime_user_mapping` 改名为 `control_runtime_user_mapping` 并保留数据。新库基线已包含；已有开发/测试库执行 [升级脚本](./upgrade-20261017-personal-memory-tables.sql)，可重复执行，需在部署新版 Control 之前执行。

2026-10-17 Agent 记忆策略（[记忆模块重设计方案](../docs/plans/记忆模块重设计方案.md) M1）：`runtime_agent_config_version` 新增 `session_memory_enabled`、`session_memory_window`、`personal_memory_read`、`personal_memory_write` 四列，随 Agent 配置版本发布。已有版本按默认值补齐：会话记忆行为不变，个人长期记忆的召回和提取关闭，需要的 Agent 在编辑页开启后重新发布。新库基线已包含；已有开发/测试库执行 [升级脚本](./upgrade-20261017-agent-memory-policy.sql)，只加列，可重复执行，需在部署新版 Runtime 之前执行。

2026-10-16 HTTP API 项目默认连接（D27）：新增 Runtime 独占表 `runtime_http_api_project_connection`（`project_id + environment` 唯一，带 `revision` 乐观并发），本项目所有 HTTP API 自动继承；`runtime_http_api_connection` 改为单 API 覆盖并新增 `override_active`（移除覆盖时置 0、保留行以延续修订号）。已有开发/测试库先备份 `runtime_http_api_connection`、暂停 Runtime 连接写入，再执行 [升级脚本](./upgrade-20261016-http-api-project-connection.sql)（MySQL 5.7/8，可重复执行），回读 `information_schema` 确认新表、唯一键和新列后部署配套服务。既有每 API 连接全部按覆盖继续生效，不回填项目默认；生效修订改为 `revision*2(+1 覆盖)`，升级前未完成的试调用与已发布 Workflow 中固定的连接修订需刷新或重新发布。本批只交付脚本，尚未执行到数据库。

2026-10-16 模型中心「厂商账号」：新建表 `model_provider_account`（同一厂商的地址和 API Key 只存一次），`model_instance` 新增可空列 `provider_account_id`。已有实例该列为 NULL，继续用自己的连接配置。新库基线已包含；已有开发/测试库执行 [升级脚本](./upgrade-20261016-model-provider-account.sql)，可重复执行，需在部署新版 Model Service 之前执行。

2026-10-16 目录分析模型可在界面选择：`model_catalog_setting` 新增可空列 `analyzer_model_instance_id`，界面保存的值优先，为空时回退环境变量 `MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID`。新库基线已包含该列；已有开发/测试库执行 [升级脚本](./upgrade-20261016-model-catalog-analyzer-setting.sql)，只加列，可重复执行，需在部署新版 Model Service 之前执行。

2026-10-16 补齐平台管理员调用权限：`control_tool_acl` 原种子只给角色码 `admin` 放行，而平台登录会话的管理员角色码是 `PLATFORM_ADMIN`，导致全新环境的平台管理员试调用任何业务方法都返回 403 `CONSOLE_CAPABILITY_ACL_DENIED`。新库基线已补 `PLATFORM_ADMIN / ALL / * / ALLOW`；已有开发/测试库执行 [升级脚本](./upgrade-20261016-platform-admin-tool-acl.sql)，只新增这一条规则，可重复执行，不改其他规则。

2026-10-14 领域治理退场：新库不再创建 `capability_domain_def`、`capability_domain_assignment` 或扫描项目的 `default_domain_code`。已有开发/测试库使用 [领域治理清理脚本](./upgrade-20261014-retire-domain-governance.sql)，兼容 MySQL 5.7/8，可重复执行。脚本直接丢弃领域定义、领域归属以及项目默认领域，不迁移成业务分类；业务能力目录的 `capability_scan_module`、`capability_catalog_group_member`、`capability_catalog_classification_state` 和既有分类数据保留。

执行顺序：先核对目标库、版本和字符集，备份两张领域表及 `capability_scan_project` 的结构/数据，记录业务分类相关表的行数与摘要；停止配套旧 Capability/Control 服务，执行上述清理脚本，再部署本批 Capability、Control 和前端。MySQL DDL 隐式提交，不能用事务回滚；恢复须使用执行前备份。执行后回读脚本的两个计数均为 0，核对保留分类表的行数与摘要保持，再恢复服务。本批仅交付脚本，尚未执行到数据库；全新环境只执行 `initV2.sql`，不要同时执行本升级。

2026-10-13 业务分类持久归属：新增 `capability_catalog_classification_state`，由 Capability 独占，记录人工选择（含清空）和分类修订。已有开发/测试库使用 [升级脚本](./upgrade-20261013-business-classification-ownership.sql)：先备份分类、成员及新状态表（若存在），停止 Capability 写入，执行脚本，回读状态唯一键、中文分类和成员归属后更新服务。脚本保留可确认的历史来源分类和人工追加成员的并集；无唯一精确 HTTP 来源位置匹配的旧资产不猜测关联，由下一次同步或手工归类补充。可重跑，已有状态（包括人工空集合）不覆盖；不改资产契约/调用授权。用户随后授权执行开发库升级：已于 2026-10-13 10:56（北京时间）在 `39.106.238.224:33106/reach_ai`（MySQL 5.7.40-log）完成原脚本及幂等重跑；六张相关表先作 DPAPI 加密备份并核验解密一致，数据事务用完整主键范围锁暂停相关写入。成员由 1 条增至 2 条，新增状态 2 条，原有行均保留，中文及唯一键回读通过，Capability 仍为 UP。随后按用户要求重新打包并重启本机五服务及前端，健康与运行包新鲜度通过；真实界面写入与再次同步尚待验收。详见[数据库执行记录](../output/tasks/business-classification-20261013/数据库升级记录.md)和[重启记录](../output/tasks/restart-20261013-business-classification/重启验证记录.md)。

2026-10-13 移除无效调用候选索引重建：新库不再创建 `capability_tool_retrieval_setting`。已有开发/测试库可在部署配套 Capability 代码并备份该表后执行 [清理脚本](./upgrade-20261013-retire-tool-retrieval-rebuild.sql)，只删除重建时的模型选择设置；不修改能力资产、调用投影、知识库或向量数据。执行后回读 `information_schema.tables` 确认目标表不存在，重复执行安全。本批只交付脚本，尚未执行到数据库。

2026-10-09 新增 [知识库集合历史归属索引修正](./upgrade-20261009-knowledge-collection-owner-history.sql)：把 `knowledge_collection_lifecycle.knowledge_base_id` 的历史唯一索引改为普通索引。MySQL 5.7 空表重启或开发库重建后可能重用已删除的自增 ID，已回收集合的日志不能阻止新知识库创建；集合主键、知识库物理集合唯一键和发布/回收时的绑定核对保持。执行前备份 `knowledge_base` 与 `knowledge_collection_lifecycle` 的结构/数据并暂停知识库创建/删除；先执行 20260908 集合生命周期升级，再执行本脚本，核对两表行摘要不变和新索引 `NON_UNIQUE=1`。不删除历史日志或提升自增起点掩盖问题，新库基线已对应修正。当前开发库执行结果另见本次联调记录，不以此说明视为已执行。

2026-10-08 新增 [业务能力附属资源整理](./upgrade-20261008-business-capability-enrichment.sql)：语义文档改为项目、分组、业务方法、HTTP API 或来源观察的 typed 引用，所有主体唯一键列必填；文档保存契约和接纳状态依据，使用非空 `DATETIME(6)` 编辑版本，候选说明不会因同指纹接纳被误标为当前。图谱节点使用稳定结构键，名称/分组不参与身份；新增 Capability 独占的人工分组关系。未上线开发库脚本明确重建旧语义文档和图谱三表，旧文档、手工边与布局会清空，不推断或迁移旧 Tool 身份；保留方法/API owner、接纳修订、来源、项目与模块、凭据、Workflow 和审计。新系统内的名称/分组变化保留已有人工关系和布局，契约变化使参数提示失效。用户随后明确授权执行远程开发库升级；已完成本次执行，其他环境执行前仍须核对目标、备份并停止相关写入，禁止对已升级库重复执行。

2026-10-08 BMAPI-8 开发环境执行记录：目标为 `39.106.238.224:33106/reach_ai`（MySQL 5.7.40-log）。本机五服务与 Vite 停写后，宝塔完整备份 `/www/backup/database/mysql/reach_ai/reach_ai_2026-10-08_13-32-19_mysql_data_HSmVS.sql.zip` 的压缩完整性、184 张原表与结束标记校验通过；按执行文件 SHA256 `a6f991d3770b2f109a82ab55ed521e8c9f57dcf61455100cc79be3c5333a81d6` 单次执行，exit0。四张重建表执行前均零行，升级后 185 张表、五张目标表的字段/索引/中文回读通过；180 张保留表全部原列、10328 行和结构摘要保持。临时项目凭据已清理，备份与私有证明保留。随后 Capability/Control 使用新包，五服务与前端重启且健康/运行包新鲜度通过。脚本头部后来只将历史“未授权”注释改为通用执行约束，DDL 未改变；以上指纹指实际执行字节。详见[升级与重启验证](../output/tasks/business-method-api/BMAPI-8/远程开发库升级与重启验证.md)。

附属图谱的展示名称使用 `TEXT NOT NULL`，完整保留合法的 256 字符分组名称与较长 HTTP 路径；名称仍不参与稳定节点身份。

2026-10-05 新增 [旧独立资产退场](./upgrade-20261005-retire-independent-capability-assets.sql)：在业务方法资产升级后删除 `capability_module`、`capability_tool_asset`、`capability_composition_definition`、`capability_interaction_definition`，并删除 Runtime 交互会话的未使用组合身份列。未分类或非法分类的旧来源观察和调用投影直接清理，不猜测资产类型，由当前 SDK/扫描来源重新同步；两张投影表不再提供分类缺省值。方法/API 分别拥有源契约，编排由 Workflow 承担，交互恢复只使用已保存快照。新库基线已移除这些定义和种子；已有开发库执行须核验目标、可恢复备份并停止相关写入。本会话已在核验并备份的远程开发库执行；实际影响及回读见下方执行记录。不修改历史调用/审批状态，不重发未知请求。

2026-10-04 新增 [业务方法独立资产升级](./upgrade-20261004-business-method-assets.sql)：Capability 独占的资产和不可变接纳修订引用可信 SDK 来源快照，分别保存业务契约、完整调用契约与传输指纹。SDK 凭据新增 revision，轮换或有效策略变化递增，相同重报保持；Runtime 调用审计新增 expected_execution_revision，固定方法接纳、传输和凭据修订，API仍使用自己的连接/凭据列。旧审计值不回填执行证明，也不重发未知调用。脚本不从旧 Tool/未分类记录回填；新 SDK 来源按正常同步形成资产。现有开发库执行前须核对目标、备份并停止 Capability 与 Runtime 写入，执行后回读两表、修订列和唯一索引。本会话已执行本份及后续旧模型退场专项升级；未从旧投影回填方法，当前来源按正常SDK同步建立owner。

`sql/initV2.sql` 是 ReachAI 当前新库 SQL 基线入口，覆盖当前物理服务拆分主路径运行所需的表结构、补列、补索引和必要种子数据。当前阶段仍使用一个 MySQL 库，不拆库；表名按 owning service / domain 前缀收口。旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。

当前主路径服务：

- `reachai-control-service`
- `reachai-runtime-service`
- `reachai-capability-service`
- `reachai-knowledge-service`
- `reachai-model-service`

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已删除；当前基线面向新库重建，不兼容旧表名，也不提供旧数据迁移脚本。

2026-09-24 新增 [API Workflow 发布 pin 升级](./upgrade-20260924-workflow-http-api-pin.sql)：在 Runtime 自有表保存已发布 API owner 契约、来源集合及连接/凭据修订，不保存 origin、凭据引用或秘密；历史版本不回填，须重新发布。已有开发/测试库应先完成 2026-09-23 的 HTTP API Console 升级，备份并停止 Runtime 写入后执行，再按脚本回读表和索引。本批未在开发库执行。

2026-09-15 新增 [来源资产类型投影升级](./upgrade-20260915-capability-asset-type.sql)：为 Capability 的扫描目录和调用投影增加 `asset_type` 及 `(project_id, asset_type)` 索引。显式业务方法与 MVC API 分别由新 SDK 同步为 `BUSINESS_METHOD` 和 `HTTP_API`；旧 SDK 缺少该 metadata 时只投影为 `UNCLASSIFIED`，不回填或改写历史 metadata、来源指纹或已接受契约。该脚本尚未在当前开发库执行；执行前须备份并停止 Capability 写入，执行后按脚本回读两张表的类型分布。

2026-09-16 新增 [控制台业务方法试调用升级](./upgrade-20260916-console-capability-invocation.sql)：新增 Runtime 独占的 `runtime_console_capability_invocation`，以 UUID 在出站前持久化认领、保留脱敏限长结果 24 小时，并将出站后无确认的情况收口为 `UNKNOWN`，绝不自动重发。脚本同时登记 `capability:invoke` 权限，但不会给任何既有角色自动授予该权限；部署后须由平台管理员按项目范围显式配置权限和 Tool ACL。此轮未执行开发库升级。

2026-09-20 新增 [HTTP API 资产与来源关系基础升级](./upgrade-20260920-http-api-asset-foundation.sql)：新增 Capability 独占的 `capability_http_api_asset` 与 `capability_http_api_source_binding`，保存规范化 scope、HTTP 操作 identity、无秘密的来源契约与来源生命周期。面向引用的稳定 scope 只使用 `projectCode + environment`；`projectId` 只保留为库内隔离键，不进入 hash 或 qualified name。脚本不回填历史扫描/注册记录，不接入 Starter 或 Scanner 写链，不接受契约、不生成 Tool 投影、不执行 HTTP，也不保存 base URL、credentialRef 或调用凭据。已有开发/测试库执行前须备份并停止 Capability 写入；执行后仅按脚本回读两表、唯一索引与状态分布。本轮未执行开发库升级。

## 本轮开发库执行记录

2026-10-05 BMAPI-6 已在核验后的远程开发库依次执行上述两份专项升级，均exit0。186表完整DPAPI备份可恢复回读通过；升级后184表与当前initV2一致，新字段/索引/中文回读通过，182张保留表的原列数据摘要保持。四张旧表的6行种子和非法分类9条调用投影、592条来源观察按脚本清理，不推断或回填资产。未执行全量initV2、未新建本地库、未修改无关角色授权。见[详细执行与保护证据](../output/tasks/business-method-api/BMAPI-6/远程开发库升级验证.md)。下方更早日期的变更说明记录当时边界，不能代替本次实际执行记录。

2026-09-11 已执行 [Supervisor 确认续跑期限说明升级](./upgrade-20260911-supervisor-approval-resume-deadline.sql)：复用现有 `resume_deadline_at` 和索引，只把字段注释扩展到 Supervisor，不修改业务数据。执行时 Runtime 已停止，目标交互表为空，完整表定义已备份；两遍执行、30 项检查及中文回读通过，四张交互／事件／运行／轨迹表的全部字段摘要一致，累计 **22 份升级已执行**，见 [执行日志](../output/tasks/architecture-audit-20260905/supervisor-resume-deadline-development-migration.log)。其他环境须先执行 Workflow 恢复期限升级，核对无期限的旧确认续跑并人工收尾。本脚本不推断旧尝试结果；新认领超时保存“结果未知、需核对”的终态，禁止自动重试。

2026-09-10 新增 [注册项目编码唯一性升级](./upgrade-20260910-registry-project-identity.sql)：`capability_scan_project.project_code` 的非 NULL 值在同库中唯一，名称不同也不能重用编码；尚无编码的 NULL 行保留。已有重复编码时脚本失败，不自动合并项目或删除数据。先备份并停止 Capability 写入，再执行脚本及部署配套错误处理。当前开发库备份已校验，脚本执行两遍，唯一索引与中文回读验证通过，三张项目／凭据／令牌表的行数和字段摘要保持一致，累计 **21 份升级已执行**。详见 [本批数据库记录](../output/tasks/architecture-audit-20260905/registry-project-identity-database-notes.md)。

2026-09-09 新增 [文件与授权记录身份升级](./upgrade-20260909-knowledge-record-generation.sql)：文件及授权每次插入获得不可变记录身份，检索快照和重解析/重新向量化目标同时核对主键与该身份。先保持 Knowledge 停写并备份，再执行脚本并部署配套代码。现有文件/授权只补齐空身份，历史任务目标保持未知，需重新提交；直接 SQL 插入文件或授权必须显式生成 `REPLACE(UUID(),'-','')`。本开发库已备份后执行两遍，132 项检查及中文回读通过，累计 20 份升级已执行，详见 [执行记录](../output/tasks/architecture-audit-20260905/knowledge-record-generation-database-notes.md)。

2026-09-08 检索授权整理只读核对了文件/授权记录主键、文件业务 ID 唯一索引和用户/文件授权唯一索引，现有结构满足本批查询。开发库权限 0 行，无失效目标或非法权限类型；本批无新增 SQL 或业务数据改写，累计已执行升级仍为 19 份。详见 [核对日志](../output/tasks/architecture-audit-20260905/knowledge-retrieval-authorization-database-audit.log)。

2026-09-08 问题引用整理复核了 `knowledge_question` 的知识库/片段索引、可空片段引用及文本长度，基线与开发库一致。本批只读核对问题为 0 行、失效知识库和片段为 0，无新增 SQL 或业务数据改写，累计已执行迁移仍为 19 份。详见 [核对日志](../output/tasks/architecture-audit-20260905/knowledge-question-lifecycle-database-audit.log)。

2026-09-08 标签生命周期整理复核了 `knowledge_tag` 的目标引用和父标签索引，基线及开发库已具备。本批只读核对标签为 0 行、失效目标和父标签为 0，无新增表/列/索引或业务数据改写，累计已执行迁移仍为 19 份。详见 [核对日志](../output/tasks/architecture-audit-20260905/knowledge-tag-lifecycle-database-audit.log)。

新增 [文档工件生命周期升级](./upgrade-20260908-knowledge-artifact-lifecycle.sql)：原件和解析产物先登记后端与不可变对象身份，再在事务外写入；引用发布和退场意图分别与业务元数据同事务提交。新增生命周期表及文件/任务引用索引，不推断历史写入确认，不改写既有业务数据。2026-09-08 已验证备份并确认本地 Knowledge 停写，脚本按固定哈希执行两遍，183 项检查和中文回读通过；三张有数据的专用表验证后已清理。累计 19 份增量已执行，详见 [本批数据库记录](../output/tasks/architecture-audit-20260905/knowledge-artifact-lifecycle-database-notes.md)。配套服务尚未部署。

新增 [知识库集合生命周期升级](./upgrade-20260908-knowledge-collection-lifecycle.sql)：新增 Knowledge 独占的集合创建、发布与回收记录。新建集合先登记独立物理身份，再在事务外创建；创建确认后原子发布知识库元数据。已有知识库只回填保存的物理映射，不推断远端创建确认或未知维度。2026-09-08 已备份并确认本地 Knowledge 停写，脚本按固定哈希执行两遍，158 项检查及中文回读通过；有数据的两张专用表验证回填、未知维度、状态保留和唯一约束后清理。累计 18 份增量已执行，详见 [本批数据库记录](../output/tasks/architecture-audit-20260905/knowledge-collection-lifecycle-database-notes.md)。配套服务尚未部署，集合后台回收的运行状态需另行验收。

新增 [文件退场与原文件快照升级](./upgrade-20260908-knowledge-file-retirement.sql)：新增重解析原文件内部 ID 快照、按状态派生的活跃文件占用及对应唯一索引，允许保留已完成/已取消的同 fileId 历史任务；补齐文件回收、替换与权限查询索引。历史任务缺失原文件快照时不能继续替换。2026-09-08 已完成备份和开发库执行，目标脚本重复执行两遍，127 项检查通过；三张有数据的专用表验证历史保留、状态派生占用、跨知识库唯一性和中文回读后清理。累计 17 份增量已执行，详见 [本批数据库记录](../output/tasks/architecture-audit-20260905/knowledge-file-retirement-database-notes.md)。

新增 [统一索引写入生命周期升级](./upgrade-20260908-knowledge-write-lifecycle.sql)：为同步导入和重新向量化保存独立执行身份、发布截止时间和原目标指纹，并记录被替换向量的准确主键。历史执行保留 JOB 类型；不推断历史目标或未知写入的完成状态。新增片段物理向量引用索引，回收前保留仍被其他片段引用的向量。2026-09-08 已按既有授权备份并执行开发库升级：有数据的专用表验证通过，目标脚本重复执行两遍，87 项检查及中文回读通过，累计 16 份增量已执行。首次临时表重写误改索引名的验证失败已修正，失败记录和专用表清理证据保留。详见[开发库记录](../output/tasks/architecture-audit-20260905/knowledge-write-lifecycle-database-notes.md)。本批尚未部署完整服务，恢复 Knowledge 时应使用配套代码。

新增 `upgrade-20260907-capability-sync-identity.sql`：执行前备份并停止 Capability 写入，建立同步请求回执、回填已有内容哈希的快照标识，并将同步日志唯一键收口为 `(project_id, sync_id)`；不会恢复从未保存的历史去重标识。2026-09-07 已在当前开发库执行两遍，23 项检查和隔离表回填验证通过，当批累计已执行 11 份增量。详见[同步身份验收](../output/tasks/architecture-audit-20260905/sync-identity-notes.md)。

新增 [知识索引执行清单升级](./upgrade-20260908-knowledge-index-execution.sql)：新增 Knowledge 独占的执行身份和回收进度表，不推断历史向量身份。2026-09-08 已备份并在当前开发库执行两遍，40 项检查通过；累计已执行本轮 13 份增量。先执行脚本，再部署配套 Knowledge 代码，详见[本批数据库记录](../output/tasks/architecture-audit-20260905/knowledge-execution-database-notes.md)。

新增 [知识库物理集合身份升级](./upgrade-20260908-knowledge-collection-identity.sql)：已有库记录当前 code 对应的集合，新建库使用独立且不可复用的物理集合名。2026-09-08 已备份、确认本地 Knowledge 停写后执行两遍，52 项检查通过，包含有数据的专用表回填和幂等验证；累计执行 14 份增量，见[数据库记录](../output/tasks/architecture-audit-20260905/knowledge-collection-database-notes.md)。不会移动或删除已有向量；新字段必填，恢复服务时必须使用配套代码，旧版本创建语句不能继续使用。

新增 [导入任务物理集合快照升级](./upgrade-20260908-knowledge-job-collection-snapshot.sql)：先执行物理集合身份升级，再保存新任务提交时的目标集合。历史任务不从当前知识库推断回填，缺失快照时拒绝重试、正式入库和索引发布；需重新提交。2026-09-08 已备份、确认本地 Knowledge 停写后执行两遍，44 项检查通过，包含有数据的专用表验证与中文回读；累计执行 15 份增量，见[数据库记录](../output/tasks/architecture-audit-20260905/knowledge-job-target-database-notes.md)。恢复服务时使用配套代码。

此前 [调试创建身份升级](./upgrade-20260907-debug-creation-identity.sql)：备份后在 Runtime 停写期间新增可空 `creation_request_hash`，旧会话不推断回填创建身份。2026-09-07 已在当前开发库执行两遍，19 项检查通过，独立回读确认字段与中文说明。配套部署 Control、Runtime 和管理端，详见[本批数据库记录](../output/tasks/architecture-audit-20260905/debug-creation-database-notes.md)。

2026-09-07，本轮架构整理涉及的 10 份 `upgrade-20260905-*`、`upgrade-20260906-*`、`upgrade-20260907-*` 脚本已在当前 `reach_ai` 开发库执行两遍，结构、回填、UTF-8 和幂等验证通过，执行前全库备份已验证完整性。详见[执行记录](../output/tasks/architecture-audit-20260905/development-database-upgrade-notes.md)。该结果仅适用于本次确认的开发库；其他环境仍按下列依赖顺序执行，不要重复初始化现有库。

## 执行方式

### 2026-10-09 Open-Meteo 不可变官方版本

[upgrade-20261009-open-meteo-official-version.sql](./upgrade-20261009-open-meteo-official-version.sql) 只在已存在且PUBLISHED/NONE认证的open-meteo目录下增加一个固定官方commit的标量参数版本及Operation，与新库基线一致，无DDL。旧v1/Operation、已有项目绑定、provider和凭据不更新；重复执行不覆盖已有不可变版本。官方规范、字节摘要、明确参数子集及限制见[事实源文档](../docs/integrations/api-market/open-meteo-forecast-scalar-20261009.md)。已有库先回读本条公开目录的引用，再由Capability owner执行本增量并回读中文与新旧版本；不得重跑整份initV2覆盖旧种子。项目仍须显式选择新版本，独立接纳HTTP owner、配置连接与调用权限，再以真实GET receipt完成验证。

### BMAPI-3D 市场受控 HTTP API 绑定（未执行）

[upgrade-20261001-api-market-http-api-binding.sql](./upgrade-20261001-api-market-http-api-binding.sql) 与 `initV2.sql` 同步：Capability 自有项目接入增加 `selection_revision`，所选 Operation 关系增加可空 `api_asset_id` 及索引，目录 Operation 增加可空 `response_content_type` 和 `response_status`。稳定逻辑身份使用可信 `api-market:<entryKey>` 范围；版本、地址、凭据及库内 ID 不进入 identity。内部 scope 字节保持原样。

已有库应在备份并停止 Capability 写入后，先有 HTTP API asset/来源/接纳基线，再执行本脚本并配套部署 Capability、Control、Runtime 和前端。旧接入不回填成功 owner、接纳、连接、ACL、Console proof 或发布 pin；须显式重选。旧目录缺明确响应媒体/成功状态、参数位置或支持的认证/schema 时阻断，不从历史示例猜测。MySQL 5.7/8 使用 information_schema 判空，执行后回读四列与关系索引并验证二遍幂等；本批未执行开发库升级，H2 测试不替代 MySQL 验收。

Runtime 不新增验证表或响应副本，直接从既有 Console ledger 与 Run snapshot 派生当前 binding proof。原始 HTTP_REQUEST+marketRef 保留工作副本，但最终发布/执行拒绝并提示改选项目 API；普通 HTTP_REQUEST 不在本脚本改造。

Runtime 调试会话使用现有 `runtime_executable_debug_session.result_json` 保存带 `completionSchema` 的执行完成回执，状态投影成功后恢复为结果摘要。2026-09-07 的调整只改变 JSON 写入约定和基线字段说明，表、列、索引均不变，已有开发／测试库无需执行 DDL。旧结果摘要不会被当作未投影回执，也不回填历史会话。

Workflow 引用索引随新版 Runtime 部署，已有开发/测试库需先执行 `sql/upgrade-20260905-workflow-reference-index.sql`。该表只保存可重建的派生数据；Runtime 启动时分批锁定并索引现有草稿、发布版本。未完成索引或图无法解析时影响查询显示 `PARTIAL`，不能解释为零引用。后续草稿保存、发布、删除与索引更新同事务，新库已包含对应结构。

全新数据库只执行当前基线：

```bash
mysql -uroot -p < sql/initV2.sql
```

从上一版 GitHub 基线 `ae9e1ce6` 升级开发/测试库，先执行合并升级入口：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260830-platform-consolidated.sql
```

已经执行上述合并升级的库，部署能力变化自动治理时执行：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" reach_ai < sql/upgrade-20260905-capability-change-governance.sql
```

该增量保留目录与审计历史，增加来源观察和去重字段。升级后从业务系统重新同步 SDK 能力；历史快照不冒充当前来源。含 TOOL 节点的历史 Workflow，以及直接暴露能力的历史 MCP 发布，需重新校验发布以固定契约。详见 [能力变化自动治理](../docs/architecture/capability-change-governance.md)。

继续部署来源归属整理时，已有库再执行 [capability-source-ownership 升级](upgrade-20260905-capability-source-ownership.sql)。它为扫描行和运行时投影补充服务端维护的 `source_qualified_name`，旧扫描编辑、推送、撤销与通用目录修改不能再改变 SDK 能力。升级后重新可信同步；回填来源标识本身不代表来源已经可用。

部署 Workflow 发布规则整理时，已有库执行 [workflow-release-events 升级](upgrade-20260905-workflow-release-events.sql)，增加不可变发布/回滚事件表。Control、Runtime 和前端须配套部署：发布和回滚要求草稿修订，公开入口经 Control 签名传递平台身份，回滚只切换活动版本并保留工作草稿。历史事件不补造；不完整的旧发布快照需重新发布。

Workflow 任务草稿的投递凭据通过 [upgrade-20260906-workflow-draft-submissions.sql](./upgrade-20260906-workflow-draft-submissions.sql) 新增。
已有开发/测试库应先执行该脚本，再部署对应 Runtime；全新环境已包含在 `initV2.sql` 中。
脚本仅建表，不修改历史草稿，也不推断旧任务的请求指纹。旧草稿命名冲突需通过新任务或 Studio 明确处理。
本轮开发库已执行并回读；Control → Runtime 投递链路仍需独立验收。

Trace 子事件持久化整理还需要 [Trace 状态列扩展](./upgrade-20260906-trace-span-status-width.sql)。
旧 `runtime_trace_span.status` 的 `VARCHAR(16)` 无法保存 17 字符的 `BUSINESS_TERMINAL`，导致业务终态的 Span 写入失败。
已有开发/测试库应先运行该脚本，将不足 32 字符的 VARCHAR 列扩展到 32，再部署 Runtime；脚本保留数据并回读列定义，不缩短更宽的列。
新库的 `initV2.sql` 已同步。H2 基线回归验证业务终态可写入，本轮开发库 MySQL 升级与回读已完成，应用并发锁影响仍需验证。

Supervisor Guard 审计整理需执行 [Guard 决策列扩展](./upgrade-20260906-guard-decision-width.sql)，再部署对应 Runtime。
`REQUIRE_CONFIRMATION` 和 `EVAL_SIDE_EFFECT_BLOCKED` 分别长 20 和 24 字符，原 `VARCHAR(16)` 会导致审批或评测拒绝日志写入失败。
升级仅扩展不足 32 的 VARCHAR 决策列，保留数据且不缩短更宽的列；新库基线已经包含新定义。执行后回读列定义，确认长度至少 32。
H2 已复现旧列宽造成的写入失败；本轮开发库 MySQL 升级已完成；DDL 并发影响未做负载验收。

Workflow 恢复期限需要 [恢复期限升级](./upgrade-20260906-workflow-resume-deadline.sql)。先停止旧 Runtime，执行脚本，再部署新版，避免无期限保护的旧实例继续处理同一会话。
脚本增加 `resume_deadline_at` 与 `(status, resume_deadline_at, id)` 索引，并将旧 WORKFLOW／COMPOSITION／DEBUG 的 `RESUMING` 行回填为上次更新时间加 900 秒；保留原更新时间，不修改其他来源或已有期限。
运行配置 `RUNTIME_INTERACTION_RESUME_TIMEOUT_SECONDS` 默认为 900，必须为正数，仅影响新认领。若已有尝试需要不同窗口，应在执行前调整升级脚本的回填秒数。
到期后只保存 `RUNTIME_INTERACTION_RESUME_TIMEOUT`、`outcome=UNKNOWN` 收据并关闭仍等待的 Trace／RunOps，不会重跑外部操作；需要核对业务系统中的实际结果。
新库基线已包含列与索引；此脚本已在本轮开发库执行，列、索引顺序及缺失期限计数检查通过。

调试会话执行期限需要 [调试执行期限升级](./upgrade-20260907-debug-session-execution-deadline.sql)。已有库先停止旧 Runtime，执行脚本，再部署新版，不能混跑。
新增 `execution_deadline_at` 与 `(status, execution_deadline_at, id)` 索引，旧 `RUNNING`／`RESUMING` 尝试按上次更新时间加 900 秒回填，保留原更新时间。
`RUNTIME_DEBUG_SESSION_EXECUTION_TIMEOUT_SECONDS` 默认为 900 且必须为正数，每次执行和恢复认领独立计时；`expires_at` 保留既有会话生命周期语义。
无完成回执的活动尝试到期后记为 `EXPIRED`、`DEBUG_SESSION_EXECUTION_TIMEOUT`、`outcome=UNKNOWN`，清除可提交交互并保留检查点与幂等身份。缺失期限也会关闭，使用 `DEBUG_SESSION_EXECUTION_DEADLINE_MISSING`，避免无界执行状态。
已提交的完成回执优先恢复；迟到结果不能覆盖超时或重新开放交互。Trace 和 RunOps 保留独立执行证据，会话超时不表示业务停止或失败，不触发重跑。
后台扫描默认每 5 秒处理最多 100 条，读取和完成提交也检查期限；关闭后台扫描不会关闭写入保护。新库基线包含列及索引，本升级已在本轮开发库执行并回读。

调试会话所有权需要 [会话所有权升级](./upgrade-20260907-debug-session-ownership.sql)。先停止旧 Runtime、增加 `owner_tenant_id` 与 `owner_user_id`，再配套部署新版 Control 和 Runtime，禁止旧新版本混跑。
新会话的所有者来自 Control 服务签名的平台会话，后续读取、恢复和取消均校验原所有者；当前控制台使用服务端确定的 `default` 租户。执行身份仍为不可信 Debug，不因此取得业务用户凭据。
升级不回填所有者，不改写原业务正文；旧无归属会话的用户接口返回 404，记录保留供内部核查。不能把旧会话归给首次访问者。浏览器也不再恢复未绑定账号的旧引用。
新库基线包含两列。此升级已在本轮开发库执行，字段、中文说明及第二次执行幂等均已验证；无需新增索引，单会话访问仍使用主键定位后校验所有者。

不要把 `initV2.sql` 和升级脚本连续执行到同一个库，也不要把设计文档中记录的历史
`upgrade-*.sql` 文件名当作当前入口。早于 `ae9e1ce6` 的数据库应从对应 Git tag 获取当时的迁移链，
或备份后按当前 `initV2.sql` 重建；`upgrade-20260830-platform-consolidated.sql` 不是全历史迁移包。

任何真实数据库执行都必须先确认 `SELECT DATABASE()`、版本、字符集和备份状态，并获得目标环境授权。
本轮开发库执行情况见上方记录，其他环境需独立确认。

## 覆盖范围

- Agent 聚合：`runtime_agent`、`runtime_agent_config_version`、固定 Workflow 发布版本的 `runtime_agent_workflow_tool`。
- Workflow 编排：`runtime_workflow`、`runtime_workflow_version`、`runtime_workflow_resource_binding`。
- 注册中心、项目实例、能力快照、字段级 diff、review/apply。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Capability 资产、交互式能力挂起恢复。
- API 市场公开目录、来源与验证证据、不可变版本/Operation，以及不含任何调用凭据的项目接入意图。
- RunOps 根运行事实 `runtime_run`、通用子事件 `runtime_trace_span`、Tool 调用、Tool ACL、Guard。
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
- 个人长期记忆使用 Control 自有 `control_personal_memory*` 表；`control_personal_memory_outbox` 将变更可靠投影到 Knowledge-owned `knowledge_personal_memory_index` 和幂等事件账本。Knowledge 可异步生成版本化 float32 embedding，并在 owner 配额边界内执行精确向量/混合排序；该投影仍不是事实源。
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

### BMAPI-3B1 HTTP API 目录与试调用（未执行）

本批新库结构已进入 `initV2.sql`。已有开发/测试库在确认备份、停止旧 Capability/Runtime 写入后，
先执行 `upgrade-20260923-http-api-inventory-acceptance.sql`，再执行
`upgrade-20260923-http-api-console.sql`，然后部署对应服务。前者不把历史 HTTP API
来源回填为“已确认”，必须重新扫描或同步；后者只增加 Runtime 的项目连接表、Console
尝试目标类型/修订列及按项目/账号查询目录历史状态的索引，不回填连接、不重发历史调用，
也不自动授予试调用权限。
两份脚本均以 `information_schema` 回读目标结构；本批未在开发库执行升级，真实库还需
由部署人员核对库名、MySQL 版本、备份及回读结果。

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
