# AI Skills Service

企业级 **知识层 + Tooling 层** 服务：在单进程内提供 RAG、向量检索、知识库与文档处理、业务语义索引，以及 **OpenAPI / Spring MVC Controller 扫描**（供 `ai-agent-service` 通过 Feign 调用，支撑扫描项目与动态 Tool 注册）。

**技术栈概要：** Java 17、Spring Boot 3.4.x、MyBatis-Plus、Milvus、MySQL、Redis；Embedding/LLM 可对接通义等（可替换实现）。

---

## 一、能力概览

| 领域 | 说明 |
|------|------|
| **RAG** | 多知识库检索增强生成（Embedding → Milvus → 权限 → Prompt → LLM） |
| **语义查重** | 单库 / 多库相似度检测 |
| **Embedding** | 文本向量化，实现可插拔 |
| **知识库** | 知识库与文件、Chunk、检索测试、切分策略配置等管理闭环 |
| **Tooling 扫描** | OpenAPI 与 Controller 源码扫描，产出 `ToolManifest` |
| **业务语义索引** | 多业务线结构化数据 + 附件的向量化与语义检索（独立 Collection） |

**横切能力：** 文件级权限、多知识库隔离、接口与实现解耦。

---

## 二、项目结构（精要）

```
ai-skills-service/
├── pom.xml
├── sql/                        # 建表与数据迁移脚本（按部署说明执行）
├── src/main/java/com/enterprise/ai/
│   ├── AiSkillsServiceApplication.java
│   ├── config/                 # Milvus、Redis、HTTP、MyBatis、全局异常等
│   ├── controller/             # REST：RAG、查重、知识库、文件、Pipeline、Embedding、Scanner 等
│   ├── bizindex/               # 业务语义索引（Controller / Service / DTO 等）
│   ├── domain/                 # 实体、DTO、VO
│   ├── repository/            # Mapper
│   ├── service/               # 知识库等业务服务
│   ├── pipeline/              # 文档入库流水线（解析、切分、持久化）
│   ├── vector/                # Milvus 封装
│   ├── embedding/             # Embedding 抽象与实现
│   ├── rag/                   # RAG 编排
│   ├── text/tooling/scanner/  # OpenAPI / Controller 扫描与 manifest 模型
│   └── security/              # 权限与 Milvus 过滤
└── src/main/resources/
    └── application.yml
```

---

## 三、对外 HTTP 接口（摘要）

> 服务默认 **context-path：`/ai`**。下列路径均相对于该前缀（如完整 RAG 路径为 `POST /ai/rag/query`）。

### 1. Tooling 扫描（供 `ai-agent-service` 等调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/scanner/openapi` | 基于 OpenAPI 规范扫描，返回 `ToolManifest` |
| POST | `/scanner/controller` | 基于 Spring MVC Controller 扫描，返回 `ToolManifest` |

核心实现包：`com.enterprise.ai.text.tooling.scanner.openapi`、`com.enterprise.ai.text.tooling.scanner.controller`、`com.enterprise.ai.text.tooling.scanner.manifest`。

### 2. RAG 问答

- **POST** `/rag/query`  
  请求体字段示例：`question`、`userId`、`knowledgeBaseCodes`、`topK`、`scoreThreshold`（以实际 DTO 为准）。  
  流程：Embedding → 多 Collection 检索 → 权限过滤 → TopK 合并 → Prompt → LLM。

### 3. 语义查重

- **POST** `/dedup/check`  
  返回相似 chunk 列表（含 `score`、来源文件等，以实际响应为准）。

### 4. 知识库与文件

- **GET** `/knowledge/kb/{kbCode}/files` — 知识库下文件列表  
- **GET** `/file/{fileId}/chunks` — 文件 Chunk 列表  
- **DELETE** `/file/{fileId}` — 删除文件及关联 Chunk 与向量  
- **POST** `/file/{fileId}/reparse` — 按当前知识库切分配置重新解析  
- **PUT** `/knowledge/kb/{kbCode}/config` — Chunk 策略（如 `splitType`：`FIXED` / `PARAGRAPH` / `SEMANTIC` 等）  
- **POST** `/retrieval/test` — 检索与相似度效果测试（不经过完整 LLM 时可作调试用）

### 5. 入库与向量化

- **POST** `/knowledge/import` — 推送到知识库（含 chunk 等，以接口定义为准）  
- **POST** `/embedding/vectorize` — 文本批量向量化  

### 6. 业务语义索引

业务系统注册索引、推送数据、按 `indexCode` 搜索等；完整路径见下文 **「八、业务语义索引」** 中接口表。

---

## 四、模块说明

| 模块 | 职责 |
|------|------|
| **Tooling 扫描** | OpenAPI/Controller 解析、ToolManifest 组装、Scanner REST |
| **Embedding** | `EmbeddingService` 及实现；新模型：新实现 + `@Service` 与主候选配置 |
| **Vector** | `VectorService` / `MilvusVectorService`，Collection 与索引管理 |
| **RAG** | `RagService` 全链路；`LlmService`、`PromptBuilder` |
| **Security** | `PermissionService`：用户可见 `file_id` → Milvus `filter` |

**权限数据流（摘要）：** 从 `user_file_permission` 取允许的 `file_id` → 转为检索阶段向量过滤，避免只在后置结果中剔除。

---

## 五、数据模型（核心表）

| 表 | 说明 |
|----|------|
| `knowledge_base` | 知识库元数据及切分策略等（如 `chunk_size`、`chunk_overlap`、`split_type`） |
| `file_info` | 文件元信息、状态、与解析相关的扩展字段等 |
| `chunk` | 分块内容、与向量/Collection 的关联 |
| `user_file_permission` | 用户与文件的权限关系 |

其他业务表（如业务索引、扫描/工具相关）以 `sql/` 下脚本及实体为准。

**数据库初始化：** 以 `sql/init.sql` 为基线；生产或增量请按仓库内 `sql/` 目录中脚本及发布说明执行，勿重复执行已生效的变更。

---

## 六、配置与快速启动

1. **依赖环境**  
   - MySQL 8.x  
   - Milvus 2.x（与客户端版本匹配）  
   - Redis（权限缓存等）  
   - 在 `application.yml` 或环境变量中配置连接串、通义/模型服务密钥等（生产勿提交密钥）。

2. **构建与运行本服务**

   ```bash
   cd ai-skills-service
   mvn clean package -DskipTests
   java -jar target/ai-skills-service-1.0.0-SNAPSHOT.jar
   ```

3. **管理端（同仓库，可选）**  
   知识库、业务索引、扫描等运维界面在 **`ai-admin-front`**（Vue 3 + Vite）中，开发时通过其代理或环境变量指向本服务（默认开发常指向 `http://localhost:8080`，与 `server.servlet.context-path` 为 `/ai` 一致）。  

---

## 七、管理前端（`ai-admin-front`）

| 能力 | 路由示例 | 说明 |
|------|----------|------|
| 知识库 | `/knowledge`、子路由 | 列表、详情、文件、Chunk、入库与策略 |
| 检索试跑 | `/retrieval` 等 | 与后端检索测试类接口配合 |
| 业务索引 | `/biz-index` 等 | 索引管理、数据接入、搜索联调 |
| 扫描与工具 | 以管理端实际菜单为准 | 与 `ai-agent-service` 扫描流程配合 |

具体路由以后端与管理端 **当前** 实现为准，上表为常见入口。

---

## 八、业务语义索引

面向「多业务系统将结构化数据 + 附件推送到中台、由中台做向量与召回」的场景。

**特点摘要：** 业务间数据隔离（通常独立 Collection）、模板化拼接检索文本、附件解析与分块、过滤条件可随请求传入、按业务主键去重等。

**典型数据流：** 注册索引 → 定义模板/字段 → `upsert` 推送（可含附件）→ `search` 按语义召回 `bizId` 与分数 → 调用方用自有系统根据 `bizId` 取详情。

**主要接口（路径均带 context-path 前缀 `/ai`）：**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/biz-index` | 注册新索引 |
| PUT | `/biz-index/{indexCode}` | 更新配置 |
| DELETE | `/biz-index/{indexCode}` | 删除索引 |
| GET | `/biz-index/list` | 索引列表 |
| GET | `/biz-index/{indexCode}` | 详情 |
| GET | `/biz-index/{indexCode}/stats` | 统计 |
| POST | `/biz-index/{indexCode}/upsert` | 推送/更新单条（多为 Multipart） |
| POST | `/biz-index/{indexCode}/batch` | 批量推送 |
| DELETE | `/biz-index/{indexCode}/record/{bizId}` | 删除单条业务记录 |
| POST | `/biz-index/{indexCode}/rebuild` | 重建 |
| POST | `/biz-index/{indexCode}/search` | 语义搜索 |

**模板占位符（示例）：** `{fieldName}` 替换为字段值；`{fieldName|默认值}` 为空时用默认值。  

**相关表（概念）：** `business_index`、`business_index_record`、`business_index_attachment` 等，以实体与 `sql` 目录为准。

---

## 九、扩展点

| 方向 | 做法 |
|------|------|
| 新 Embedding 提供方 | 实现 `EmbeddingService` 并配置为主 Bean |
| 新 LLM | 实现 `LlmService` |
| 新向量库 | 实现 `VectorService` |
| 权限与多租户 | 扩展 `PermissionService` 或在 filter 中增加维度 |
| Rerank | 在 RAG 合并或排序阶段插入 |
| 新切分策略 | 实现 `ChunkStrategy` 并注册到工厂类 |

---

## 十、典型适用场景

- 企业知识库与制度问答  
- 文档与 Chunk 级语义检索  
- 查重与相似度风控  
- 多业务线统一语义检索中台（业务语义索引）  
- 为 Agent 层提供可扫描、可入库的 **Tool 清单** 能力  

与 **`ai-agent-service`（编排、扫描项目、Tool 管理）**、**`ai-model-service`（模型网关）**、**`ai-admin-front`（运维与调试）** 联调时，请统一网络与 `context-path`、鉴权等约定。
