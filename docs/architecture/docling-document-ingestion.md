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
4. 重试解析仍使用原 Provider。索引失败复用已保存的结构化解析工件；每次索引认领取得新的执行身份和确定性主键，先登记清单再写 Milvus。MySQL 发布事务核对任务、租约、截止时间和目标身份，提交文件/Chunk、任务完成和执行发布状态；旧租约不能发布，未知远端写入保留回收清单。
5. 重新解析创建新的 file/job，并由服务保存原文件内部 ID 和知识库物理集合快照。发布新文件与退场原文件在同一数据库事务内完成；原文件已删除、重建或被另一轮解析替换时拒绝发布。失败回滚保留原正文、权限和问题引用。
6. 两个文件删除入口统一提交文件/Chunk 删除、权限撤销、问题与 Chunk 解除关联、关联任务取消及准确向量回收意图。问题正文保留，旧向量由后台按登记主键回收；仍有已发布片段引用的向量暂不删除。已完成/已取消任务保留历史，其他状态通过数据库唯一约束占用 fileId，取消后才能重新使用。
7. 知识库创建和删除归属 `KnowledgeBaseLifecycleService`。创建先独立提交 `knowledge_collection_lifecycle` 的物理身份，再在事务外调用 Milvus；确认返回后，知识库元数据与集合 READY 状态一起提交。此流程挂起调用方事务，创建结果不随外层事务回滚。整体删除在同一事务退场全部文件、取消待处理任务、撤销权限、删除问题和标签，并保存集合回收意图；命中历史保留。
8. `KnowledgeCollectionReclaimer` 只回收登记的旧物理集合；仍有知识库或 chunk 引用时退避。创建结果未知时持续复查，不能凭一次成功 drop 结束回收。向量入库步骤不再隐式创建集合，避免迟到索引操作重建已删除集合。已有映射不代表远端健康，集合缺失会报错。
9. 原件和解析工件由 `ManagedDocumentArtifactStore` 统一登记写入身份。物理写入前独立提交 WRITING，确认成功后才允许引用发布；原件引用与任务提交、解析引用与有效租约的 PARSED 状态分别原子提交。取消、文件删除和替换在业务事务中保存 RECLAIMING，后台依据准确对象身份重试；仍被文件或未完成任务引用的对象保留。文件删除和同一导入任务的元数据替换同时退场 FILE/CHUNK 标签，并解除存活子标签的父引用；创建标签使用相同的知识库锁确认当前目标。问题创建也使用当前知识库锁和片段锁；文件删除与片段替换在同一事务内解除旧问题引用，保留文本与命中次数，整库退场拒绝迟到问题。进行中检索已绑定原文件/授权记录和知识库物理身份，并在模型调用及返回边界复核；Runtime、RAG、查重使用同一个检索入口。文件和授权每次插入生成不可变记录身份；检索快照、重解析目标与重新向量化目标均核对该身份，主键复用不延续原记录。已有空身份需要执行记录身份升级，历史任务目标不推断回填。已验证直接复用主键的持久化行为，整库恢复及完整服务故障恢复仍待验证。

工件回收独立于导入 Worker 开关，默认每 10 秒最多处理 8 条；配置为 `reachai.knowledge.artifact-cleanup.enabled` 和 `reachai.knowledge.artifact-cleanup.interval-ms`。认领使用 60 秒租约，物理 IO 在事务外执行，结案核对回收 owner；失败保存错误类型并退避 30 秒。写入未确认时，即使清理成功也保持 RECLAIMING，300 秒后复查迟到数据；迟到 ACK 撤销旧回收租约。引用发布期限为登记后 20 分钟，并在取得行锁后重新读取数据库时间。上传提交是独立事务，外层调用事务回滚不会撤销已经提交的导入任务。

本地后端使用规范根路径和 `.reachai-storage-id` 标记推导身份，半写文件放入该根目录的确定性 `.reachai-upload` 路径；重建实例后仍可按准确 key 清理。移动根目录或替换身份标记会改变后端身份，不应在存在待回收记录时直接切换。S3 身份由配置的 endpoint 与 bucket 推导，禁止自动连接重试。回收先中止完全相同 key 的未完成分片，再枚举该 key 的版本正文和删除标记，显式传 versionId 删除，包括字面量 null 版本；删除后重新列举确认。每轮版本列表最多 8 页、每页请求 100 项，未确认清完、分页异常、缺失版本身份和权限错误均保留重试，不删除相邻 key。

兼容存储需支持 ListObjectVersions，账号除既有对象/分片权限外，还需要桶级 s3:ListBucketVersions 和工件对象的 s3:DeleteObjectVersion；回收不绕过对象保留限制，也不修改桶配置。同部署版本的本机 MinIO 已通过真实版本、分页和保留策略测试；开发库只读检查的已结案登记为 0。部署环境 IAM/网络/持久卷、其他环境已结案记录的历史回补与无登记孤立对象仍待验证或治理，后台不会全桶扫描。见 [真实 MinIO 验收](../../output/tasks/architecture-audit-20260905/knowledge-s3-server-notes.md)。详见 [S3 版本回收验收](../../output/tasks/architecture-audit-20260905/knowledge-s3-versions-notes.md)。

解析临时 JSON 由 Worker 以 CREATE_NEW 和 DELETE_ON_CLOSE 打开，序列化与上传共享同一个句柄；不按路径重新打开，不扫描删除其他任务文件。本机 Windows/JDK 17 已通过序列化中断、上传中断和强退验证；其他部署文件系统和整机故障仍需验收。

集合回收默认独立启用：`reachai.knowledge.collection-cleanup.enabled=true`，扫描间隔 `reachai.knowledge.collection-cleanup.interval-ms=10000`，每轮最多 8 个集合。认领和完成使用短事务，远端 drop 在事务外执行；删除失败退避 30 秒，未知创建结果完整清理后退避 300 秒。迟到创建确认撤销旧回收租约，旧 owner 不能结束新一轮回收。集合创建和 drop 禁用隐式 SDK 重试，存在性查询的网络、鉴权和空响应错误均不能当成“不存在”。这些约束适用于知识库文档集合，业务索引集合的生命周期仍独立维护。

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

Knowledge 到 Docling 的 multipart 文件体和 HTTP 响应均流式传输，解析工件先写临时文件再上传对象存储，不再为每个任务创建完整请求、响应和工件 `byte[]` 副本。临时文件在支持 POSIX 的文件系统上以 `rw-------` 创建，其他文件系统继承目录权限。多副本或生产环境必须设置 `REACHAI_KNOWLEDGE_ARTIFACT_STORE_TYPE=s3` 并配置共享的 S3/MinIO endpoint、bucket 与 Secret；`local` 只适合单机开发。`deploy/Dockerfile.docling` 固定了官方 `docling-serve-cpu:v1.30.0` digest，并补充 LibreOffice、CJK font 和中文 locale，从而支持旧 `.doc`。

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
