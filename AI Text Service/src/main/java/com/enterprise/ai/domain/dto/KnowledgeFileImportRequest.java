package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline 入库请求 — 通过文件上传触发完整的入库流水线。
 *
 * <p>注意：file 字段通过 MultipartFile 方式单独接收（@RequestParam），
 * 其他参数通过 @RequestParam 或 @ModelAttribute 绑定。</p>
 */
@Data
public class KnowledgeFileImportRequest {

    /** 目标知识库编码 */
    @NotBlank(message = "知识库编码不能为空")
    private String knowledgeBaseCode;

    /** 文件业务ID */
    @NotBlank(message = "文件ID不能为空")
    private String fileId;

    /** 切分策略: fixed_length / paragraph / semantic */
    private String chunkStrategy = "fixed_length";

    /** 切分大小（字符数） */
    private Integer chunkSize = 500;

    /** 切分重叠（字符数） */
    private Integer chunkOverlap = 50;

    /** 扩展参数（如 OCR 语言、自定义清洗规则等） */
    private Map<String, Object> extraParams = new HashMap<>();
}
