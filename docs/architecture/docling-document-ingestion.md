# Docling 文档导入契约

## 目标与服务边界

`reachai-knowledge-service` 是文档解析、原件/解析工件保存、Chunk 来源锚点和知识入库任务的唯一 owner。Docling 是该服务的内部依赖，不是浏览器可访问的通用文件转换网关。

生产拓扑：

```text
Console / Control BFF
  -> Knowledge import-job API
    -> source artifact store
      -> Java Fast or internal Docling Service
        -> immutable parse artifact
          -> Chunk / embedding / vector / metadata pipeline
```

Docling Kubernetes Service 只使用 `ClusterIP`；不配置公网 Ingress，也不将 `REACHAI_DOCLING_API_KEY` 下发给浏览器。

## 固定路由与失败语义

| 文件类型 | Provider | 约束 |
| --- | --- | --- |
| `txt`、`md` / `markdown`、普通 `csv` | `JAVA_FAST` | 仅接受 UTF-8；二进制 NUL、错误编码和超限文件 fail-closed |
| `doc`、`docx`、`pdf`、`pptx`、`xlsx`、`png`、`jpg` / `jpeg`、`tif` / `tiff`、`bmp`、`webp` | `DOCLING` | 通过 v1 `/v1/convert/file`；PDF/Office/图片均保存结构化 JSON |

扩展名、声明 MIME 和最小 magic header 会先交叉校验。路由是确定性的：Docling 超时、拒绝、返回部分成功或没有可索引文本时，任务失败或按同一个 Docling Provider 重试，绝不回落到 POI、PDFBox 或任何 Legacy Provider。

图片/扫描件 OCR 没有得到可索引文本时会以 `DOCLING_EMPTY_OUTPUT` 失败；不会把 `<!-- image -->` 占位符写入向量库。

## 导入任务状态机

`knowledge_document_import_job` 保存 `QUEUED -> PARSING -> PARSED -> INDEXING -> COMPLETED` 的状态。可重试解析进入 `RETRY_WAIT`；不可恢复错误进入 `FAILED`；用户主动放弃进入 `CANCELLED`。

1. 上传只保存一次原件，并记录 SHA-256、检测到的格式和固定 Provider。
2. Worker 从工件存储重新打开原件，保存 `DocumentParseResult` JSON；预览和正式入库读取同一份 JSON，并复用同一套清洗/结构化切分步骤，不重新上传或重新调用浏览器文件。
3. `PARSED` 状态可以预览；提交后才进入 embedding/vector/metadata pipeline。
4. 重试解析仍使用原 Provider。索引失败复用已经保存的结构化解析工件；Milvus 使用稳定主键 upsert，MySQL 在单个事务内替换同一任务的文件/Chunk 元数据，任务在任意索引阶段失败后均可幂等重试。
5. 重新解析创建新的 file/job；只有新文件完整入库后才删除被替换的旧文件和其工件。

每个 `knowledge_chunk` 保存 element type、section path 和 `source_locator_json`（页码、坐标、Provider reference 等），用于检索结果追溯。

## Console 任务闭环

文件入库页展示持久任务的 `jobId`、Provider/版本、阶段、尝试次数、耗时和稳定错误码，并提供安全取消、失败重试和暂停轮询后的继续刷新。当前任务 ID 保存在浏览器会话存储中；页面刷新只恢复查询，不重新上传原件或重新创建任务。轮询从 1 秒逐步退避到 3 秒，并在页面卸载时停止；20 分钟仍未结束时仅暂停页面刷新，后台任务继续执行。

知识库文件页显示实际解析 Provider 和版本，重新解析任务会持续跟踪到新文件完成索引并替换旧文件。预览、段落运营和文件详情显示 element type、section path，以及适用的页码、幻灯片、工作表/单元格或文本行来源。50 MiB 文件上限同时在浏览器和 Knowledge 服务端校验，服务端仍是最终信任边界。

## 配置与部署

Knowledge 服务配置前缀为 `reachai.knowledge.docling`，部署时通过环境变量提供：

- `REACHAI_DOCLING_BASE_URL`：集群内部 Docling URL，例如 `http://reachai-docling:5001`。
- `REACHAI_DOCLING_API_KEY`：仓库不提供默认凭据；本地应用与 Compose 从 Git 忽略的 `deploy/.env` 读取，生产环境只从 Secret 注入，`prod` / `production` profile 会拒绝空值。
- `REACHAI_DOCLING_ENABLED`、`REACHAI_DOCLING_REQUEST_TIMEOUT_MS`、`REACHAI_DOCLING_DOCUMENT_TIMEOUT_SECONDS`：容量与超时控制。
- `REACHAI_DOCLING_MAX_CONCURRENT_REQUESTS`、`REACHAI_DOCLING_CONCURRENCY_WAIT_TIMEOUT_MS`：Knowledge 侧并发准入；1 GiB Pod 默认只归一化一个 Docling 响应，扩容内存后才能同步提高并发。
- `REACHAI_DOCLING_MAX_RESPONSE_BYTES`：流式响应的硬上限，默认 64 MiB；超限以 `DOCLING_RESPONSE_TOO_LARGE` 失败，不把不完整结果写入知识库。提高上限时必须同步验证 Knowledge Pod heap。
- `REACHAI_DOCLING_OCR_LANGUAGES`、`REACHAI_DOCLING_TABLE_MODE`、`REACHAI_DOCLING_DO_TABLE_STRUCTURE`、`REACHAI_DOCLING_DO_PDF_HEADING_HIERARCHY`：服务端统一解析策略。

Knowledge 到 Docling 的 multipart 文件体和 HTTP 响应均流式传输，解析工件先写临时文件再上传对象存储，不再为每个任务创建完整请求、响应和工件 `byte[]` 副本。多副本或生产环境必须设置 `REACHAI_KNOWLEDGE_ARTIFACT_STORE_TYPE=s3` 并配置共享的 S3/MinIO endpoint、bucket 与 Secret；`local` 只适合单机开发。`deploy/Dockerfile.docling` 固定了官方 `docling-serve-cpu:v1.30.0` digest，并补充 LibreOffice、CJK font 和中文 locale，从而支持旧 `.doc`。

## 公开入口与鉴权前置条件

浏览器只调用 Control 的 `/api/knowledge/import-jobs/**` 和 `/api/knowledge/import-files/{fileId}/reparse`。Control 必须先验证真实的平台会话，再根据 Knowledge 返回的资源归属执行 workspace/project 级 RBAC；随后将已验证的 tenant、actor 和资源 scope 作为精确请求体的一部分签名，转发到 Knowledge 的 `/internal/knowledge/console/document-import/**`。

Knowledge 会独立校验 method/path/body HMAC、时间窗口和 nonce，并把 tenant、创建者、workspace、project 与资源 scope 快照写入导入任务。任务查询和变更同时受 tenant/actor owner fence 与 Control 侧资源权限约束，不能仅凭客户端提交的 `knowledgeBaseCode` 或 `jobId` 决定可见性。

历史浏览器直连入口 `/ai/knowledge/import/file`、`/ai/pipeline/import`、`/ai/file/{fileId}/reparse` 和 `/ai/knowledge/import-jobs/**` 已退役。Knowledge Service 的其他 `/ai/**` 仍是当前阶段的服务部署路径，但不能用这些退役入口绕过 Control 鉴权。

## 最小验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -pl reachai-knowledge-service -am test -DskipITs

Set-Location ai-admin-front
npm run test:knowledge
npm run build
Set-Location ..

docker build -f deploy/Dockerfile.docling -t reachai-docling:v1.30.0 .
docker compose --env-file deploy/.env -f deploy/docker-compose.infra.yml --profile docling up -d
```

验证至少覆盖 Java Fast UTF-8 文本、Docling PDF/图片 OCR、旧 `.doc` 的 LibreOffice 依赖、无 API Key 返回 401、结构化 `body.children` 顺序以及删除后的工件清理。
