package com.enterprise.ai.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SimilarItem {

    /** chunk ID */
    private String chunkId;

    /** 文件业务ID */
    private String fileId;

    /** 文件名称 */
    private String fileName;

    /** 命中的文本内容 */
    private String content;

    /** 相似度分数 */
    private float score;

    /** 来源知识库编码 */
    private String knowledgeBaseCode;
}
