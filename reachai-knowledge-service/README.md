# ReachAI Knowledge Service

`reachai-knowledge-service` 负责知识库、文件、解析与工件、检索、业务索引和个人记忆检索投影。只读写自有表；跨服务通过显式内部 API 协作，同库不改变所有权。

- 源码：`src/main/java/`，配置：`src/main/resources/application.yml`。
- 公共请求、身份和调用方向见[架构](../docs/architecture.md)与[公共路由](../docs/contracts/public-routes.md)。
- 精确表归属见[所有权清单](../docs/contracts/tables.md)，服务间调用见[内部 API](../docs/contracts/internal-apis.md)。

外部知识库（`knowledge_base.source_type = EXTERNAL`）：内容留在 RAGFlow、腾讯 ima 或 Dify 里，检索时由 `external/` 下的连接器实时调用，结果与本地库一起排序；API Key 用 AES-GCM 加密保存（密钥默认由 `REACHAI_INTERNAL_SERVICE_SECRET` 派生，可用 `REACHAI_KNOWLEDGE_EXTERNAL_KEY_BASE64` 单独指定）。默认允许访问内网地址（自建 RAGFlow / Dify），`reachai.knowledge.external.allow-private-network=false` 可关闭；链路本地和云元数据地址始终拒绝。新增一种外部系统只需再实现一个 `ExternalKnowledgeProvider`。

在仓库根目录运行相关模块测试：

```powershell
mvn -pl reachai-knowledge-service -am test
```

本地构建/启动见[开发](../docs/development.md)，正式环境配置与验收见[运维](../docs/operations.md)。模块源码、配置和实际运行结果决定当前行为，本页不复制另一份端口、字段或部署状态表。
