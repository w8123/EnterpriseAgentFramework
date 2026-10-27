# 数据与模型

## 知识库

Knowledge 拥有知识库、文件、段落、解析工件与检索投影；Runtime 消费结构化证据并生成回答。知识库详情集中提供文件、检索测试、命中分析和设置。

| 设置 | 语义 |
| --- | --- |
| ALL_CALLERS | 调用使用该知识库的 Agent 的用户可检索，无需逐文件授权 |
| FILE_GRANTS | 按可信用户的逐文件授权检索，缺少身份不返回受保护内容 |
| 节点检索覆盖 | 节点显式值优先，否则使用知识库设置，再使用服务默认值 |
| REQUIRED 证据策略 | 无证据是明确业务结果，回答应说明资料没有依据，不能让模型猜测 |

知识库配置更新只写本命令负责的字段，短事务核对行 ID、业务 code 和不可复用的物理集合身份，避免并发操作回写旧快照。检索用户来自可信运行身份，不接受请求参数覆盖。

## 解析、索引与回收

文件先校验类型和大小，再经选定 Provider 解析。Docling 的失败、部分输出或无可索引文本不能静默切换成另一套解析器。段落保留来源定位，重新解析完成后才替换原文件。

- 外部写入前独立登记执行、物理集合和准确向量清单，发布事务复核任务、租约、文件代次与截止时间。
- 重新向量化使用新主键，发布新引用和登记旧主键回收在同一事务中完成；不能按 fileId 猜测历史向量。
- 集合、文件和工件退场保留持久化回收意图。回收只操作准确物理身份，仍被发布内容引用时退避。
- 未知远端写入持续复查；一次删除为空或经过一段等待时间均不能证明迟到写入已结束。
- S3 工件按准确 key 清理未完成分片、版本正文和删除标记，不扫描全桶，不修改对象保留策略。存储身份变化前先处理未完成回收。

具体实现从 [KnowledgeBaseLifecycleService](../reachai-knowledge-service/src/main/java/com/enterprise/ai/service/impl/KnowledgeBaseLifecycleService.java)和 [DocumentIndexExecutionStore](../reachai-knowledge-service/src/main/java/com/enterprise/ai/pipeline/document/job/DocumentIndexExecutionStore.java)进入。

## 个人记忆

Control 的个人记忆、候选与 outbox 是长期事实源；Knowledge 只提供可删除和重建的检索投影。召回后仍回读 canonical 内容并确认当前所有者、可用状态和删除结果。

平台用户使用 `/api/me/memories/**`，业务用户使用 `/api/embed/memories/**`，两者分别继承平台会话和 Embed 身份。擦除与用户映射使用各自受权接口；删除与审计、投影清理必须保持一致。跨域擦除由 Control 编排各 owner 执行，Legal Hold 与保留策略不能被缓存或检索路径绕过。

## 业务索引

业务系统通过 SDK 同步可检索引用。索引文本只帮助找到对象，不代表对象仍存在或当前用户有权读取；Runtime 消费前使用当前签名身份调用 resolver 回源。

引用携带项目、资源身份和版本，resolver 检查租户、用户和业务权限。结果未知、失效或拒绝时不以历史索引内容兜底。契约在 [ai-runtime-contract](../ai-runtime-contract/)与 SDK 的 `bizindex` 包中维护。

## 模型

Model Gateway 以 `model_template` 和 `model_instance` 为核心：前者是发布目录和创建模板，后者是可调用配置。厂商账号保存受保护凭据，实例使用稳定 ID；名称、模板或目录同步不能替代运行身份。

官方来源同步形成快照和候选，验证后再更新发布目录；抓取失败保留可解释状态，不能用不完整数据替换有效目录。目录、账号、实例和运行探测分别表达状态。

Chat、Embedding、Rerank 统一经过 Model Gateway。Workflow 固定模型实例引用，禁用或缺失实例时明确失败；API Key 只在服务端使用，读接口返回掩码，掩码不能覆盖真实密钥。接口、配置与 Provider 实现以 [Model 服务](../reachai-model-service/README.md)为准。
