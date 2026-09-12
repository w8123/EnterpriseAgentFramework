# 知识库生命周期与配置写入

S3 未完成分片的回收由 [MinioDocumentArtifactStore](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/pipeline/document/artifact/MinioDocumentArtifactStore.java) 按完整对象键查询，并仅中止键完全匹配的上传。[清理响应契约](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/pipeline/document/artifact/S3MultipartUploads.java) 只读取存储桶、对象键、上传 ID 和分页信息：这些身份及完整性字段缺失时拒绝回收，MinIO 返回空的拥有者或存储类别不会阻断清理。请求仍使用现有 SDK 的验签、区域解析、HTTP 错误和超时处理，保留每页 100 条、最多 8 页及未确认写入持续回收的边界。真实响应复现和验证范围见 [分片清单修复记录](../../output/tasks/architecture-audit-20260905/knowledge-multipart-metadata-notes.md)。

知识库的创建、删除、目录编辑和配置更新归属 [KnowledgeBaseLifecycleService](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/service/impl/KnowledgeBaseLifecycleService.java)。[KnowledgeServiceImpl](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/service/impl/KnowledgeServiceImpl.java) 作为应用入口转交这些命令，不再直接依赖 KnowledgeBaseRepository 或维护第二份配置写入逻辑。

目录编辑只写请求提供的名称、描述、模型、归属及检索选项；配置更新只写提供的切分与检索选项。两个入口共用检索选项的赋值规则和既有模型校验，省略的字段保持原值，false 和 0 仍是有效设置。可空字段沿用 MyBatis 的非 null 更新语义，此整理没有把省略字段解释为清空命令。

每次更新在短事务中读取原知识库，写入时同时匹配行 ID、业务编码和不可复用的物理集合身份。只提交本命令的字段，不把读取快照整行写回。并发修改未涉及的字段得以保留；目标已删除或已变为另一代知识库时返回冲突。相同字段的合法并发修改仍按数据库提交顺序生效，此规则不提供编辑版本号或人工合并功能。

创建继续通过集合生命周期存储登记外部创建和补偿依据，向量服务调用在元数据事务外；删除继续按知识库、文件和集合既有顺序退休资源。导入、重新向量化、检索、标签、问题与运营查询仍各自使用已经建立的负责人组件。该边界不改变同库阶段的表所有权，也没有新增 schema。

反例、数据库验证和运行环境证据见 [知识库命令整理记录](../../output/tasks/architecture-audit-20260905/knowledge-base-commands-notes.md)。

文件名称补充、文件列表、片段列表和标签筛选由 [KnowledgeContentQuery](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/service/impl/KnowledgeContentQuery.java) 负责。片段排序、关键词匹配、启用筛选、标签数值 ID 解释和 1–500 条限制沿用原规则；查询不触发索引写入。人工标题／正文／启停修改由 [KnowledgeChunkEditingService](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/service/impl/KnowledgeChunkEditingService.java) 负责，继续在事务中只更新指定字段，并匹配原片段的知识库与文件归属。空修改保留原行，空白正文拒绝；启停沿用 0 为禁用、其他值为启用。

导入和重新向量化仍归索引写入组件，人工编辑不得把读取时的向量引用、统计或其他无关字段整行写回。门面保留事务入口并转交命令；查询与编辑组件共用现有片段视图映射，没有新增表或另一套索引生命周期。方法体保留证据、回归和部署读取见 [内容管理整理记录](../../output/tasks/architecture-audit-20260905/knowledge-content-management-notes.md)。

控制台和项目凭据的签名上传先捕获原始请求字节并校验 HMAC／nonce，再交给 MVC。Servlet 的 `getParts()` 不会自动使用包装后的 `getInputStream()`；因此 [请求回放](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/internalauth/KnowledgeReplayableBodyRequest.java) 必须同时接管 multipart 文件和字段读取。[multipart 适配器](../../reachai-knowledge-service/src/main/java/com/enterprise/ai/internalauth/KnowledgeReplayableMultipart.java) 使用当前 Tomcat 解析器读取已经验签的捕获流，沿用 Spring 的文件大小、请求总量和落盘阈值配置；请求结束时清理本次解析临时文件。不重新读取已经消费的容器请求流，也不为上传绕过签名。此适配器明确依赖当前 Tomcat 部署，切换 Servlet 容器时应替换适配器并重跑 [真实 HTTP 回归](../../reachai-knowledge-service/src/test/java/com/enterprise/ai/internalauth/KnowledgeSignedMultipartHttpTest.java)。
