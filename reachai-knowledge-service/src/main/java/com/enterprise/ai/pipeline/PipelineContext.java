package com.enterprise.ai.pipeline;

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
 * 创建 → FileParseStep(写入rawText)
 *       → OcrStep(补充rawText)
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

    /** 目标知识库编码 */
    private String knowledgeBaseCode;

    /** 切分策略: fixed_length / paragraph / semantic */
    private String chunkStrategy = "fixed_length";

    /** 切分大小（字符数） */
    private int chunkSize = 500;

    /** 切分重叠（字符数） */
    private int chunkOverlap = 50;

    /** 扩展参数（用于未来步骤传参，如 OCR 语言、rerank 模型等） */
    private Map<String, Object> extraParams = new HashMap<>();

    // ==================== 流水线中间产物 ====================

    /** 文件解析后的原始文本 */
    private String rawText;

    /** OCR 处理后的文本（无 OCR 时与 rawText 相同） */
    private String ocrText;

    /** 清洗后的文本 */
    private String cleanedText;

    /** 切分后的文本块列表 */
    private List<String> chunks = new ArrayList<>();

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
