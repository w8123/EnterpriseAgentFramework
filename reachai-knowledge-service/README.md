# ReachAI Knowledge / Retrieval Service

`reachai-knowledge-service` 是当前 Knowledge / Retrieval 部署单元，目录名和 Maven artifactId 均已收口到 `reachai-knowledge-service`。产品、文档和部署说明默认使用 Knowledge / Retrieval 口径，不再把它描述为旧的技能服务。

## 定位

本服务负责知识库、文件、Chunk、向量检索、RAG、业务索引和历史扫描器实现。扫描能力的长期产品归属是 Capability Catalog；当前由 `reachai-capability-service` 通过内部服务调用复用这里的 OpenAPI / Spring MVC Controller 扫描实现，支持扫描项目和能力资产沉淀。

当前五服务拓扑为：

- `reachai-control-service`: Platform Control public API/BFF，统一承接 `/api/**`、`/embed/**` 和 SDK 注册兼容入口。
- `reachai-runtime-service`: Runtime Host，负责 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service`: Capability Catalog，负责 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service`: Knowledge / Retrieval，本服务。
- `reachai-model-service`: Model Gateway，负责模型实例、Chat、Embedding、Rerank 和 OpenAI 兼容代理。

## Runtime Contract

- 默认端口：`18602`
- Spring context path `/ai`
- 健康检查：`GET /ai/actuator/health`
- 前端开发代理：`/ai/** -> http://localhost:18602`
- Model 调用：通过 `MODEL_SERVICE_URL` 指向 `reachai-model-service`
- 公共 `/api/**` 入口仍归 `reachai-control-service`，本服务不承担 Platform Control BFF 角色

## Key Packages

| Package | Responsibility |
| --- | --- |
| `com.enterprise.ai.controller` | Knowledge, file, retrieval, RAG, embedding, scanner, and pipeline REST endpoints. |
| `com.enterprise.ai.service` | Knowledge base, import, dedup, and application services. |
| `com.enterprise.ai.repository` | MyBatis mapper/repository layer for the shared MySQL schema. |
| `com.enterprise.ai.pipeline` | Import pipeline, parsing, chunking, cleaning, embedding, and vector persistence. |
| `com.enterprise.ai.vector` | Vector search abstraction and Milvus implementation. |
| `com.enterprise.ai.embedding` | Embedding client abstraction backed by Model Gateway. |
| `com.enterprise.ai.rag` | RAG orchestration, prompt building, and LLM call integration. |
| `com.enterprise.ai.bizindex` | Business index APIs, storage, and semantic search. |
| `com.enterprise.ai.text.tooling.scanner` | OpenAPI and Spring MVC Controller scanner implementation. |

## Main Endpoints

Paths below are relative to the `/ai` context path:

| Method and path | Purpose |
| --- | --- |
| `GET /knowledge/base/list` | List knowledge bases. |
| `POST /knowledge/base` | Create or update a knowledge base. |
| `GET /knowledge/kb/{kbCode}/files` | List files in a knowledge base. |
| `POST /knowledge/import` | Import files into a knowledge base. |
| `GET /file/{fileId}/chunks` | List chunks for a file. |
| `DELETE /file/{fileId}` | Delete a file and its chunks. |
| `POST /retrieval/test` | Run retrieval test queries. |
| `POST /rag/query` | Execute RAG question answering. |
| `POST /embedding/vectorize` | Generate embedding vectors. |
| `POST /scanner/openapi` | Scan an OpenAPI document into tool manifests. |
| `POST /scanner/controller` | Scan Spring MVC Controller source into tool manifests. |
| `/biz-index/**` | Manage and search business indexes. |

Specific request and response contracts should follow the current controller DTOs and frontend API usage.

## Configuration

Common environment variables:

| Variable | Purpose |
| --- | --- |
| `AI_MYSQL_URL` | Shared MySQL JDBC URL. |
| `AI_MYSQL_USERNAME` | Shared MySQL username. |
| `AI_MYSQL_PASSWORD` | Shared MySQL password. |
| `AI_REDIS_HOST`, `AI_REDIS_PORT`, `AI_REDIS_PASSWORD` | Redis connection. |
| `AI_MILVUS_HOST`, `AI_MILVUS_PORT` | Milvus connection. |
| `MODEL_SERVICE_URL` | Base URL for `reachai-model-service`. |

第一阶段仍保持同一个 MySQL 库，不拆库。Schema 基线统一在 `sql/init.sql`，已有环境升级脚本统一放在 `sql/upgrade-*.sql`。

## Local Build And Run

From the repository root:

```bash
mvn -pl reachai-knowledge-service -am -DskipTests compile
```

To run only this service:

```bash
cd reachai-knowledge-service
mvn spring-boot:run
```

When validating the whole physical split locally, start all five services and then run from the repository root:

```bash
node scripts/check-physical-service-smoke.mjs
```

The smoke script expects Control, Runtime, Capability, Knowledge, and Model services to be running on their configured local ports.
