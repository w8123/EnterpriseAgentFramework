package com.enterprise.ai.bizindex.domain.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 索引统计信息
 */
@Data
@Builder
public class BizIndexStatsVO {

    /** 索引编码 */
    private String indexCode;

    /** 索引名称 */
    private String indexName;

    /** 索引记录总数 */
    private long recordCount;

    /** 含附件的记录数 */
    private long attachmentRecordCount;

    /** 附件 Chunk 总数 */
    private long attachmentChunkCount;

    /** 向量总数（主记录 + 附件 Chunk） */
    private long totalVectorCount;
}
