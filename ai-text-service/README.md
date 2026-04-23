# AI Text Service（文本/RAG + Tooling 混合能力层）

## 一、项目简介

基于 **Spring Boot 3.x + Java 17** 构建的企业级 AI 能力服务，当前定位为 **文本/RAG + Tooling 混合层**，统一承载：

- **RAG**（检索增强生成） — 多知识库问答
- **语义查重**（相似度检测） — 单库 / 多库查重
- **Embedding**（文本向量化） — 可扩展多模型
- **知识库管理闭环**（V2） — 知识库详情、文件管理、Chunk 查看、检索测试、策略配置
- **Tooling 扫描核心**（V4） — OpenAPI / Spring MVC Controller 扫描，供 `ai-agent-service` 运行时复用
- **业务语义索引**（V3） — 多业务系统语义检索能力

核心特性：文件级权限控制、多知识库隔离、Milvus 向量检索、扫描能力内聚、接口与实现解耦。

> 说明：`ai-text-service` 已不再是“仅文本/RAG”服务，而是知识能力与 Tooling 基础能力的混合承载层。

---

## 二、技术栈

| 组件 | 说明 |
|------|------|
| Java 17 | 语言版本 |
| Spring Boot 3.2.x | 基础框架 |
| MyBatis-Plus 3.5.x | ORM |
| Milvus 2.4 | 向量数据库 |
| MySQL 8.x | 业务数据存储 |
| Redis | 权限缓存 |
| 通义千问 | Embedding & LLM（可扩展） |

---

## 三、项目结构

```
AI Text Service/
├── pom.xml                         # Maven 依赖配置
├── sql/
│   ├── init.sql                    # 数据库建表 & 初始数据
│   └── upgrade_v2.sql              # V2 升级脚本（新增字段）
├── src/main/java/com/enterprise/ai/
│   ├── AiTextServiceApplication.java     # 启动类
│   ├── config/                           # 配置层
│   │   ├── MilvusConfig.java             #   Milvus 连接配置
│   │   ├── RedisConfig.java              #   Redis 序列化配置
│   │   ├── RestTemplateConfig.java       #   HTTP 客户端配置
│   │   ├── MyBatisPlusConfig.java        #   MP 分页 & 自动填充
│   │   └── GlobalExceptionHandler.java   #   全局异常处理
│   ├── controller/                       # 接口层
│   │   ├── RagController.java            #   POST /ai/rag/query
│   │   ├── DedupController.java          #   POST /ai/dedup/check
│   │   ├── KnowledgeController.java      #   知识库 CRUD + 详情 + 配置
│   │   ├── FileController.java           #   V2: 文件删除/重解析/chunk查看
│   │   ├── RetrievalController.java      #   V2: 检索测试
│   │   ├── PipelineController.java       #   Pipeline 入库
│   │   └── EmbeddingController.java      #   POST /ai/embedding/vectorize
│   ├── domain/                           # 实体层
│   │   ├── entity/                       #   数据库实体
│   │   │   ├── KnowledgeBase.java        #   含 chunk 策略配置字段
│   │   │   ├── FileInfo.java             #   含文件大小和原始文本
│   │   │   ├── Chunk.java
│   │   │   └── UserFilePermission.java
│   │   ├── dto/                          #   请求/响应 DTO
│   │   │   ├── ApiResult.java
│   │   │   ├── KnowledgeBaseVO.java
│   │   │   ├── KnowledgeBaseRequest.java
│   │   │   ├── FileInfoVO.java           #   V2: 文件列表展示
│   │   │   ├── ChunkVO.java              #   V2: chunk 列表展示
│   │   │   ├── RetrievalTestRequest.java #   V2: 检索测试请求
│   │   │   ├── RetrievalTestResponse.java#   V2: 检索测试响应
│   │   │   ├── KbConfigRequest.java      #   V2: chunk 策略配置
│   │   │   ├── RagRequest/Response.java
│   │   │   ├── DedupRequest/Response.java
│   │   │   ├── EmbeddingRequest/Response.java
│   │   │   └── KnowledgeImportRequest.java
│   │   └── vo/
│   │       └── SimilarItem.java          #   相似结果视图
│   ├── repository/                       # 数据访问层 (MyBatis-Plus Mapper)
│   ├── service/                          # 业务逻辑层
│   │   ├── KnowledgeService.java         #   知识库管理接口（含V2新方法）
│   │   └── impl/
│   │       └── KnowledgeServiceImpl.java #   含检索测试/文件管理/重解析
│   ├── pipeline/                         # Pipeline 入库流水线
│   │   ├── step/
│   │   │   └── MetadataPersistStep.java  #   V2: 额外存储fileSize和rawText
│   │   ├── chunk/                        #   切分策略（FIXED/PARAGRAPH/SEMANTIC）
│   │   └── parser/                       #   文档解析（PDF/Word/纯文本）
│   ├── vector/                           # Milvus 向量封装层
│   ├── embedding/                        # Embedding 模块
│   ├── rag/                              # RAG 模块
│   ├── text/tooling/scanner/             # Tooling 扫描核心（OpenAPI / Controller）
│   └── security/                         # 权限模块
└── src/main/resources/
    └── application.yml                   # 应用配置
```

---

## 四、核心接口

### 0. Tooling 扫描核心（库能力 + HTTP 能力）

该能力由 `ai-text-service` 统一承载，对外提供扫描接口供 `ai-agent-service` 通过 Feign 调用：

- `POST /ai/scanner/openapi` — 基于 OpenAPI 规范扫描并返回 `ToolManifest`
- `POST /ai/scanner/controller` — 基于 Spring MVC Controller 扫描并返回 `ToolManifest`

服务内核心实现位于：

- `com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner`
- `com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner`
- `com.enterprise.ai.text.tooling.scanner.manifest.*`

用途：支撑 `/api/scan-projects/*` 运行时扫描链路，将历史项目接口转换为动态 Tool 定义。

---

### 1. RAG 问答

```
POST /ai/rag/query
```

**请求体：**

```json
{
  "question": "什么是合同的生效条件？",
  "userId": "user_001",
  "knowledgeBaseCodes": ["kb_contract"],
  "topK": 5,
  "scoreThreshold": 0.5
}
```

**流程：** Embedding → 多 Collection 检索 → 权限过滤 → TopK 合并 → Prompt 构建 → LLM 生成

---

### 2. 语义查重

```
POST /ai/dedup/check
```

**请求体：**

```json
{
  "text": "本合同自双方签字盖章之日起生效",
  "userId": "user_001",
  "knowledgeBaseCodes": ["kb_contract"],
  "topK": 10,
  "scoreThreshold": 0.7
}
```

**返回：** 相似内容列表（含 chunkId、fileId、fileName、score、content）

---

### 3. 知识入库

```
POST /ai/knowledge/import
```

**请求体：**

```json
{
  "knowledgeBaseCode": "kb_contract",
  "fileId": "file_20240101",
  "fileName": "合同模板.docx",
  "chunks": ["第一条 合同目的...", "第二条 合同期限..."]
}
```

---

### 4. 文本向量化

```
POST /ai/embedding/vectorize
```

**请求体：**

```json
{
  "texts": ["示例文本1", "示例文本2"]
}
```

---

### 5. 知识库详情 — 获取文件列表（V2）

```
GET /ai/knowledge/kb/{kbCode}/files
```

**返回：** 文件列表（含 fileName、fileSize、chunkCount、status、createTime）

---

### 6. 文件管理（V2）

```
GET    /ai/file/{fileId}/chunks    — 获取文件 chunk 列表
DELETE /ai/file/{fileId}           — 删除文件（同时删除 chunk + 向量）
POST   /ai/file/{fileId}/reparse  — 使用知识库最新配置重新解析
```

---

### 7. 检索测试（V2）

```
POST /ai/retrieval/test
```

**请求体：**

```json
{
  "query": "什么是合同的生效条件？",
  "knowledgeBaseCodes": ["kb_contract"],
  "topK": 5,
  "scoreThreshold": 0.3
}
```

**返回：** chunk 内容、相似度 score、来源文件名、知识库编码、耗时

---

### 8. Chunk 策略配置（V2）

```
PUT /ai/knowledge/kb/{kbCode}/config
```

**请求体：**

```json
{
  "chunkSize": 500,
  "chunkOverlap": 50,
  "splitType": "FIXED"
}
```

支持: `FIXED`（固定长度）、`PARAGRAPH`（段落切分）、`SEMANTIC`（语义切分）

---

## 五、模块说明

### 0. Tooling Scanner 模块（V4）

| 类/包 | 说明 |
|----|------|
| `text.tooling.scanner.openapi` | OpenAPI 规范扫描 |
| `text.tooling.scanner.controller` | Spring MVC Controller 扫描 |
| `text.tooling.scanner.manifest` | 扫描结果内部模型（ToolManifest 等） |
| `controller.ScannerController` | 扫描 HTTP 能力面（`/ai/scanner/openapi`、`/ai/scanner/controller`） |

定位：为编排层提供可复用的扫描核心，并通过 REST 暴露标准扫描能力供远程调用。

---

### 1. Embedding 模块

| 类 | 说明 |
|----|------|
| `EmbeddingService` | 接口 — 定义 embed / embedBatch / getModelName |
| `TongyiEmbeddingService` | 通义实现 — HTTP 调用 DashScope API |

**扩展方式：** 新增实现类 + `@Service` + 通过 `@Primary` 或配置切换。

### 2. Vector 模块

| 类 | 说明 |
|----|------|
| `VectorService` | 接口 — insert / search / delete / ensureCollection |
| `MilvusVectorService` | Milvus 实现 — 自动建表、IVF_FLAT 索引、COSINE 距离 |

### 3. RAG 模块

| 类 | 说明 |
|----|------|
| `RagService` | 接口 — 编排完整 RAG 流程 |
| `RagServiceImpl` | 实现 — 串联 embedding → 检索 → 权限 → 合并 → prompt → LLM |
| `LlmService` | 接口 — LLM 调用抽象 |
| `TongyiLlmService` | 通义实现 |
| `PromptBuilder` | Prompt 模板组装器 |

### 4. Security 模块

| 类 | 说明 |
|----|------|
| `PermissionService` | 接口 — 获取权限 file_id 列表 + 构建 Milvus filter |
| `PermissionServiceImpl` | 实现 — MySQL 查询 + Redis 缓存 |

**权限流程：** 查询 `user_file_permission` 表 → 获取 file_id 列表 → 转换为 Milvus `file_id in [...]` 过滤表达式 → 在向量检索阶段生效。

---

## 六、数据模型

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| `knowledge_base` | 知识库 | code、dimension、embedding_model、**chunk_size**、**chunk_overlap**、**split_type** |
| `file_info` | 文件信息 | file_id、knowledge_base_id、status、**file_size**、**raw_text** |
| `chunk` | 文本块 | file_id、knowledge_base_id、vector_id、content、collection_name |
| `user_file_permission` | 用户文件权限 | user_id、file_id、permission_type |

> **V2 新增字段**（加粗标注）：`knowledge_base` 表新增 chunk 策略配置，`file_info` 表新增文件大小和原始文本存储（支持重新解析）。

**初始化：** 执行 `sql/init.sql` → 再执行 `sql/upgrade_v2.sql`（已有环境升级）。

---

## 七、快速启动

1. **环境准备**

   - MySQL 8.x，执行 `sql/init.sql`，已有环境再执行 `sql/upgrade_v2.sql`
   - Milvus 2.4+（默认 localhost:19530）
   - Redis（默认 localhost:6379）
   - Node.js 18+（前端）

2. **后端配置 & 启动**

   ```bash
   # 编辑 application.yml 或设置环境变量
   export DB_PASSWORD=your_db_password
   export TONGYI_API_KEY=your_api_key
   export MILVUS_HOST=localhost
   export REDIS_HOST=localhost

   # 构建 & 运行
   cd "AI Text Service"
   mvn clean package -DskipTests
   java -jar target/ai-text-service-1.0.0-SNAPSHOT.jar
   ```

3. **前端启动**

   ```bash
   cd AITextFront
   npm install
   npm run dev
   ```

   默认开发代理已配置为 `localhost:8080`（后端地址）。

---

## 八、前端页面说明（V2 新增）

| 页面 | 路由 | 功能 |
|------|------|------|
| 知识库列表 | `/knowledge` | 知识库 CRUD，点击名称进入详情 |
| 知识库详情 | `/knowledge/:code` | 文件列表、chunk 策略配置、文件删除/重解析 |
| 文件详情 | `/knowledge/:code/file/:fileId` | chunk 列表（可展开内容） |
| 文件入库 | `/knowledge/import` | 上传文件、预览切分、执行入库 |
| 检索测试 | `/retrieval` | 输入 query 测试 RAG 检索效果 |

前端技术栈：**Vue 3 + TypeScript + Element Plus + Pinia + Vite**

---

## 八-B、业务语义索引模块（V3 新增）

### 1. 模块简介

支持多业务系统接入的语义检索能力。各业务系统（物资、合同、考勤等）将结构化数据 + 附件推送到 AI 中台，中台负责向量化和语义匹配，返回匹配的业务 ID 供调用方回查详情。

**核心特性：**
- 业务系统间数据物理隔离（独立 Milvus Collection）
- 灵活的文本模板引擎（业务系统自定义，AI 中台渲染）
- 支持附件解析与 Chunk 语义检索
- 过滤条件透传（权限由业务系统控制）
- 搜索结果按 bizId 去重、取最高分

### 2. 数据流

```
业务系统注册索引 → 定义模板和字段
         │
业务系统推送数据 → POST /ai/biz-index/{indexCode}/upsert
         │
AI 中台处理：
  模板渲染 → Embedding → Milvus 存储
  附件解析 → Chunk 切分 → Embedding → Milvus 存储
         │
语义搜索 → POST /ai/biz-index/{indexCode}/search
         │
AI 中台：向量检索 → 按 bizId 去重 → 返回 bizId + score + metadata
         │
业务系统：根据 bizId 查询自身 MySQL 获取完整业务详情
```

### 3. 接口一览

| 接口 | 说明 |
|------|------|
| `POST   /ai/biz-index` | 注册新索引 |
| `PUT    /ai/biz-index/{indexCode}` | 更新索引配置 |
| `DELETE /ai/biz-index/{indexCode}` | 删除索引 |
| `GET    /ai/biz-index/list` | 索引列表 |
| `GET    /ai/biz-index/{indexCode}` | 索引详情 |
| `GET    /ai/biz-index/{indexCode}/stats` | 索引统计 |
| `POST   /ai/biz-index/{indexCode}/upsert` | 推送数据（Multipart） |
| `POST   /ai/biz-index/{indexCode}/batch` | 批量推送 |
| `DELETE /ai/biz-index/{indexCode}/record/{bizId}` | 删除记录 |
| `POST   /ai/biz-index/{indexCode}/rebuild` | 重建索引 |
| `POST   /ai/biz-index/{indexCode}/search` | 语义搜索 |

### 4. 推送数据示例

```
POST /ai/biz-index/biz_material/upsert
Content-Type: multipart/form-data

Part "data" (application/json):
{
  "bizId": "MAT-2026-001",
  "bizType": "seal",
  "fields": {
    "name": "氟橡胶O型密封圈",
    "spec": "内径50mm 线径3.5mm",
    "category": "密封件",
    "useScene": "高温蒸汽管道法兰连接"
  },
  "metadata": {
    "materialNo": "MAT-2026-001",
    "warehouse": "A区3号库"
  },
  "ownerUserId": "user_001",
  "ownerOrgId": "org_001"
}

Part "attachments" (file, 可选):
  产品规格书.pdf
```

### 5. 搜索示例

```
POST /ai/biz-index/biz_material/search
{
  "query": "耐高温的管道密封材料",
  "topK": 10,
  "scoreThreshold": 0.5,
  "filters": {
    "owner_org_id": ["org_001"]
  }
}
```

### 6. 模板语法

```
{fieldName}          → 替换为字段值
{fieldName|默认文本}  → 字段为空时使用默认值

示例：物资名称：{name}，规格型号：{spec|未知}，用途：{useScene}
```

### 7. 数据库表

| 表名 | 说明 |
|------|------|
| `business_index` | 索引注册（编码、模板、字段定义、Embedding 配置） |
| `business_index_record` | 索引记录（bizId、searchText、权限字段、元数据） |
| `business_index_attachment` | 附件 Chunk（文件名、切分内容、向量 ID） |

**初始化：** 执行 `sql/business_index_v3.sql`

### 8. 前端页面

| 页面 | 路由 | 功能 |
|------|------|------|
| 业务索引列表 | `/biz-index` | 索引 CRUD、统计概览 |
| 索引详情 | `/biz-index/:code` | 统计、接入指南（自动生成示例代码）、搜索测试 |

---

## 九、扩展点

| 扩展方向 | 方式 |
|----------|------|
| 新增 Embedding 模型 | 实现 `EmbeddingService` 接口 |
| 切换 LLM | 实现 `LlmService` 接口 |
| 替换向量库 | 实现 `VectorService` 接口 |
| 增强权限模型 | 扩展 `PermissionService` |
| 多租户 | 在 filter 中追加 tenant_id |
| Rerank | 在 RagServiceImpl 的 TopK 合并后插入 rerank 逻辑 |
| 新增切分策略 | 实现 `ChunkStrategy` 接口 + 注册到工厂 |

---

## 十、适用场景

- 企业知识库问答
- 文档语义检索
- 数据去重 / 查重
- 智能辅助决策
- RAG 效果评测与调优
