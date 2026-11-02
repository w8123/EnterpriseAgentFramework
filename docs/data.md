# 数据与模型

## 知识库

Knowledge 拥有知识库、文件、段落、解析工件与检索投影；Runtime 消费结构化证据并生成回答。知识库详情集中提供文件、检索测试、命中分析和设置。

| 设置 | 语义 |
| --- | --- |
| ALL_CALLERS | 调用使用该知识库的 Agent 的用户可检索，无需逐文件授权 |
| FILE_GRANTS | 保存逐文件授权；由于现有授权中的用户编号没有身份域，运行时、RAG 和语义查重暂不使用这些授权，不能检索受保护文件 |
| 节点检索覆盖 | 节点显式值优先，否则使用知识库设置，再使用服务默认值 |
| REQUIRED 证据策略 | 无证据是明确业务结果，回答应说明资料没有依据，不能让模型猜测 |

知识库配置更新只写本命令负责的字段，短事务核对行 ID、业务 code 和不可复用的物理集合身份，避免并发操作回写旧快照。运行时的用户编号仍用于审计，但不作为逐文件授权依据；在可信身份域和授权迁移完成前，运行时仅能检索 ALL_CALLERS 知识库。

设置页通过 `settings-context` 读取整份设置及修订，再用 `settings-if-unchanged` 一次保存基本信息、模型引用、检索范围与切分／检索配置。Control 核对实际资源的读取与写入范围，修改模型引用另需能读取对应启用模型及正确模型类型；修改内容不包含管理范围、状态或物理身份。Knowledge 在行锁内核对修订后执行单次字段更新，冲突时保留既有内容。修订同时绑定配置、归属、行 ID 和物理集合，删除重建或移动资源后不能复用旧修订。保存不会自动重解析文件。旧版 `PUT /ai/knowledge/base` 与 `PUT /ai/knowledge/kb/{kbCode}/config` 在 Control 和 Knowledge 均返回 HTTP 409、`KNOWLEDGE_SETTINGS_REVISION_REQUIRED` 及共同入口路径，不再写入或替调用方获取新修订；旧客户端必须先读取当前设置再明确提交修改。

管理端和平台 AI 的知识库目录、文件、段落、统计、命中分析共用 Control 的读取边界。Knowledge 通过签名目录接口，在同一可重复读事务内提供资源归属与内容；Control 根据实际 `SHARED` / `WORKSPACE` / `PROJECT` 范围检查当前账号授权，目录先过滤再分页或返回。文件读取以真实所属知识库为准，不能由请求中的项目或用户字段替换。依赖状态需要全局知识读取权限。该链路要求同时升级 Control 和 Knowledge 并配置服务间签名，旧 owner 或不完整的归属响应会拒绝读取，不回退通用代理。它是平台管理读取，不代表业务用户的逐文件检索授权。

## 解析、索引与回收

文件先校验类型和大小，再经选定 Provider 解析。Docling 的失败、部分输出或无可索引文本不能静默切换成另一套解析器。段落保留来源定位，重新解析完成后才替换原文件。

- 外部写入前独立登记执行、物理集合和准确向量清单，发布事务复核任务、租约、文件代次与截止时间。
- 重新向量化使用新主键，发布新引用和登记旧主键回收在同一事务中完成；不能按 fileId 猜测历史向量。
- 集合、文件和工件退场保留持久化回收意图。回收只操作准确物理身份，仍被发布内容引用时退避。
- 未知远端写入持续复查；一次删除为空或经过一段等待时间均不能证明迟到写入已结束。
- S3 工件按准确 key 清理未完成分片、版本正文和删除标记，不扫描全桶，不修改对象保留策略。存储身份变化前先处理未完成回收。

具体实现从 [KnowledgeBaseLifecycleService](../reachai-knowledge-service/src/main/java/com/enterprise/ai/service/impl/KnowledgeBaseLifecycleService.java)和 [DocumentIndexExecutionStore](../reachai-knowledge-service/src/main/java/com/enterprise/ai/pipeline/document/job/DocumentIndexExecutionStore.java)进入。

## 个人记忆

Control 的个人记忆、分组与 outbox 是长期事实源；Knowledge 只提供可删除和重建的检索投影。召回后仍回读 canonical 内容并确认当前所有者、可用状态和删除结果。

平台用户使用 `/api/me/memories/**`，业务用户使用 `/api/embed/memories/**`，两者分别继承平台会话和 Embed 身份。擦除与用户映射使用各自受权接口；删除与审计、投影清理必须保持一致。跨域擦除由 Control 编排各 owner 执行，Legal Hold 与保留策略不能被缓存或检索路径绕过。

## 业务索引

业务系统通过 SDK 同步可检索引用。索引文本只帮助找到对象，不代表对象仍存在或当前用户有权读取；Runtime 消费前使用当前签名身份调用 resolver 回源。

引用携带项目、资源身份和版本，resolver 检查租户、用户和业务权限。结果未知、失效或拒绝时不以历史索引内容兜底。契约在 [ai-runtime-contract](../ai-runtime-contract/)与 SDK 的 `bizindex` 包中维护。

## 模型

Model Gateway 以 `model_template` 和 `model_instance` 为核心：前者是发布目录和创建模板，后者是可调用配置。厂商账号保存受保护凭据，实例使用稳定 ID；名称、模板或目录同步不能替代运行身份。

官方来源同步形成快照和候选，验证后再更新发布目录；抓取失败保留可解释状态，不能用不完整数据替换有效目录。目录、账号、实例和运行探测分别表达状态。

Chat、Embedding、Rerank 统一经过 Model Gateway。Workflow 固定模型实例引用，禁用或缺失实例时明确失败；API Key 只在服务端使用，读接口返回掩码，掩码不能覆盖真实密钥。接口、配置与 Provider 实现以 [Model 服务](../reachai-model-service/README.md)为准。
