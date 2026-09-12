# ReachAI Knowledge Service

`reachai-knowledge-service` 是 ReachAI Knowledge / Retrieval 部署单元，默认端口 `18602`，Spring context path 为 `/ai`。它拥有知识库、文件与 Chunk、文档导入、检索、RAG、业务索引和个人记忆检索投影。

## 职责边界

本服务拥有：

- `knowledge_base`、`knowledge_file_info`、`knowledge_chunk` 和文件权限；
- 文件上传、去重、解析、清洗、Chunk、Embedding 与向量写入 pipeline；
- Java Fast / Docling 文档导入任务、原件与解析工件；
- keyword/vector/hybrid 检索、Rerank、检索测试与 RAG；
- `knowledge_business_index*` 业务检索投影；
- `knowledge_personal_memory_index` 等个人记忆搜索投影；
- 历史 OpenAPI / Spring MVC Controller 扫描器的底层实现。

本服务不拥有：

- 个人长期记忆 canonical 数据、平台身份或跨域擦除编排（Control）；
- 扫描项目目录、Capability/Tool 资产或 API 市场（Capability）；
- Agent/Workflow/GraphSpec 与运行证据（Runtime）；
- 模型模板、实例或 Provider 凭据（Model）。

扫描器实现位于 Knowledge，不表示扫描资产归 Knowledge。Capability 通过 internal service contract 复用解析能力并拥有目录/落库。

## 入口边界

| 入口 | 调用方 | 说明 |
| --- | --- | --- |
| `/ai/knowledge/**`、`/ai/file/**`、`/ai/retrieval/**`、`/ai/rag/**`、`/ai/embedding/**` | 管理端/开发工具 | 当前 Knowledge 公共能力；具体认证边界以 public route 文档为准 |
| `/api/knowledge/biz-index/**` | 浏览器 → Control | 平台会话 + 资源 RBAC；Control 以 HMAC 调用本服务 internal console API |
| `/api/knowledge/import-jobs/**` | 浏览器 → Control | 文档导入受保护控制台入口；Control 负责会话/RBAC，本服务负责 owner fence 与任务 |
| `/api/knowledge-ingress/projects/{projectCode}/biz-index/**` | 业务系统 → Control | `REACHAI_PROJECT_REQUEST_V1` body-bound 项目签名，经 Capability 验证后进入本服务 |
| `/internal/knowledge/retrieval/query` | Runtime | 返回结构化 hits/outcome/脱敏 diagnostics，不生成 LLM 答案 |
| `/internal/knowledge/personal-memories/**` | Control | 个人记忆搜索投影事件与检索；canonical 数据仍由 Control 回读 |

历史匿名 `/ai/biz-index/**` 已退役。把平台 Bearer 或业务 Bearer 直接发送给 Knowledge 不会自动获得授权。

## 检索与事实边界

- Runtime `KNOWLEDGE_RETRIEVAL` 只消费结构化证据，不调用 Knowledge 生成最终回答。
- `outcome=NO_EVIDENCE` 是成功业务结果。`evidencePolicy=REQUIRED` 的 Workflow 必须走固定无证据回答，不能让 LLM 猜测。
- Business Index 文本和 metadata 只用于发现；业务对象仍要在当前签名用户身份下调用 resolver 回源鉴权。
- 个人记忆投影可删除、重建和 shadow；Control canonical item 才是长期记忆事实源。

## 主要代码区域

| Package | 责任 |
| --- | --- |
| `controller` / `service` / `repository` | 知识库、文件、导入和应用服务 |
| `pipeline` | 解析、清洗、Chunk、Embedding 与向量持久化 |
| `retrieval` / `vector` / `embedding` | 检索核心、Milvus 和 Model Gateway client |
| `rag` | RAG 编排与提示构建 |
| `bizindex` | 业务索引定义、记录、附件和检索 |
| `personalmemory` | 个人记忆检索投影 |
| `text.tooling.scanner` | OpenAPI / Spring MVC 扫描实现 |
| `internal` / `internalauth` | Runtime/Control internal contract 与可信调用校验 |

## 下游依赖与配置

| 分组 | 主要变量 | 说明 |
| --- | --- | --- |
| MySQL/Redis | `AI_MYSQL_*`、`REDIS_*` | Knowledge-owned 元数据、任务和协调状态 |
| 向量 | `MILVUS_HOST`、`MILVUS_PORT`、`MILVUS_USERNAME`、`MILVUS_PASSWORD` | 向量索引；启用 Milvus 认证时用户名与密码必须同时配置，可用性需单独验证 |
| Model | `MODEL_SERVICE_URL` | Chat/Embedding/Rerank 统一进入 Model Gateway；没有 OpenAI HTTP 代理回退 |
| Docling | `REACHAI_DOCLING_*` | 可选重解析服务、版本、OCR、超时和并发限制 |
| 工件 | `REACHAI_KNOWLEDGE_ARTIFACT_*` | 本地或 S3-compatible 原件/解析工件存储 |
| 导入 worker | `REACHAI_KNOWLEDGE_DOCUMENT_*` | 文件上限、队列、租约、重试和 worker 开关 |
| 个人记忆投影 | `REACHAI_PERSONAL_MEMORY_*` | Embedding、search mode、阈值、worker 与指标 |
| 内部认证 | `REACHAI_INTERNAL_*` | HMAC、轮换与传输模式 |

默认本地模式会提供公开的内部调用和个人记忆身份开发值；显式配置优先，生产 profile 与 Kubernetes 环境必须从 Secret 注入。

生产启用 Docling、S3/MinIO、向量或异步 worker 前，必须验证目标存储、网络、密钥、容量、重试/死信和恢复。

## 构建、启动与验证

```powershell
mvn -pl reachai-knowledge-service -am -DskipTests compile
mvn -pl reachai-knowledge-service -am test
Set-Location reachai-knowledge-service
mvn spring-boot:run
```

健康检查：`GET http://localhost:18602/ai/actuator/health`。

```powershell
node scripts/check-internal-api-contracts.mjs
node scripts/check-service-table-ownership.mjs
node scripts/check-physical-service-route-contracts.mjs
```

Knowledge 变更的真实验收至少区分：有证据命中、无证据分支、文件 ACL、Model/向量依赖、目标库 schema 和 Runtime/浏览器端到端链路。

## 进一步阅读

- [知识、模型与企业资产](../docs/05-知识模型与企业资产.md)
- [Docling 文档导入契约](../docs/architecture/docling-document-ingestion.md)
- [Personal Agent Memory](../docs/architecture/personal-agent-memory.md)
- [Workflow 语义契约](../docs/architecture/workflow-semantic-contract.md)
