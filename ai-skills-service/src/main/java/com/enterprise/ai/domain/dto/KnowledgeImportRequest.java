package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeImportRequest {

    /** 知识库编码 */
    @NotBlank(message = "知识库编码不能为空")
    private String knowledgeBaseCode;

    /** 文件业务ID */
    @NotBlank(message = "文件ID不能为空")
    private String fileId;

    /** 文件名称 */
    private String fileName;

    /** 文本块列表 */
    @NotEmpty(message = "文本块不能为空")
    private List<String> chunks;
}
