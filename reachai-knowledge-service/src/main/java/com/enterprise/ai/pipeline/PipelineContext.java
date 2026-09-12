package com.enterprise.ai.pipeline;

import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.pipeline.document.DocumentChunkSource;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 上下文对象 — 贯穿整个入库流水线的数据载体。
 *
 * <p>每个 {@link PipelineStep} 从 context 读取上一步产出、写入本步结果，
 * 实现步骤间松耦合的数据传递。</p>
 *
 * <h3>生命周期</h3>
 * <pre>
 * 创建 → FileParseStep(写入parsedDocument/rawText)
 *       → TextCleanStep(写入cleanedText)
 *       → ChunkStep(写入chunks)
 *       → EmbeddingStep(写入vectors)
 *       → VectorStoreStep(写入vectorIds)
 *       → MetadataPersistStep(持久化元数据)
 * </pre>
 */
@Data
public class PipelineContext {

    // ==================== 输入参数 ====================

    /** 上传的原始文件 */
    private MultipartFile file;

    /** 文件业务ID */
    private String fileId;

    /** 文件名称 */
    private String fileName;

    /** Durable source metadata. Present for asynchronous document import jobs. */
    private Long sourceFileSize;
    private String sourceContentType;
    private String sourceObjectKey;
    private String sourceSha256;
    private String parseArtifactObjectKey;
    private String importJobId;
    /** Unique worker lease for this indexing execution, separate from the durable job ID. */
    private String importLeaseOwner;
    /** Independent identity for a synchronous PIPELINE, DIRECT or REEMBED write. */
    private String indexExecutionId;
    private String indexOperation;
    private com.enterprise.ai.pipeline.document.job.DocumentIndexTargetSnapshot indexTarget;
    /** Set only after the metadata and job completion transaction commits. */
    private boolean importPublished;

    /** 目标知识库编码 */
    private String knowledgeBaseCode;

    /** 异步任务保留提交时的知识库 ID；执行入口按此 ID 确认目标，禁止转向同编码新库。 */
    private Long knowledgeBaseId;

    /** 执行入口从目标知识库解析，后续写入、发布和补偿使用相同物理集合。 */
    private String vectorCollectionName;

    private Integer knowledgeBaseDimension;

    /** 切分策略: fixed_length / paragraph / semantic */
    private String chunkStrategy = "fixed_length";

    /** 切分大小（字符数） */
    private int chunkSize = 500;

    /** 切分重叠（字符数） */
    private int chunkOverlap = 50;

    /** 扩展参数（用于未来步骤传参，如清洗规则、追踪ID等） */
    private Map<String, Object> extraParams = new HashMap<>();

    // ==================== 流水线中间产物 ====================

    /** 文件解析后的原始文本 */
    private String rawText;

    /** 结构化解析结果；OCR、表格和来源定位均由 Provider 在本步骤完成。 */
    private DocumentParseResult parsedDocument;

    /** 清洗后的文本 */
    private String cleanedText;

    /** 切分后的文本块列表 */
    private List<String> chunks = new ArrayList<>();

    /** Chunk 与 Docling/Java Fast 来源锚点按索引一一对应。 */
    private List<DocumentChunkSource> chunkSources = new ArrayList<>();

    /** 每个 chunk 对应的向量 */
    private List<List<Float>> vectors = new ArrayList<>();

    /** 写入 Milvus 后返回的向量 ID 列表 */
    private List<String> vectorIds = new ArrayList<>();

    // ==================== 执行状态 ====================

    /** 当前执行到的步骤名称 */
    private String currentStep;

    /** 各步骤耗时记录 (stepName → ms) */
    private Map<String, Long> stepDurations = new HashMap<>();

    /** 是否已中断 */
    private boolean aborted = false;

    /** 中断原因 */
    private String abortReason;

    // ==================== 便捷方法 ====================

    /**
     * 记录步骤耗时
     */
    public void recordDuration(String stepName, long durationMs) {
        stepDurations.put(stepName, durationMs);
    }

    /**
     * 中断流水线
     */
    public void abort(String reason) {
        this.aborted = true;
        this.abortReason = reason;
    }

    /**
     * 获取扩展参数（带类型转换）
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtraParam(String key, T defaultValue) {
        Object val = extraParams.get(key);
        return val != null ? (T) val : defaultValue;
    }
}
