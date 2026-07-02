package com.enterprise.ai.bizindex.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件索引记录 —— 附件解析后按 Chunk 粒度存储，每段关联回业务记录。
 * <p>一个业务记录（bizId）可对应多个附件，每个附件可产生多个 Chunk。</p>
 */
@Data
@TableName("business_index_attachment")
public class BusinessIndexAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属索引编码 */
    private String indexCode;

    /** 关联的业务主键 */
    private String bizId;

    /** 关联 business_index_record.id */
    private Long recordId;

    /** 附件原始文件名 */
    private String fileName;

    /** 文件类型（pdf / docx / txt） */
    private String fileType;

    /** 附件解析后的完整原始文本（仅 chunk_index=0 的记录存储，用于重建索引） */
    private String rawText;

    /** 切分序号（从 0 开始） */
    private Integer chunkIndex;

    /** 切分后的文本片段 */
    private String chunkContent;

    /** 该 Chunk 在 Milvus 中的向量 ID */
    private String vectorId;

    /** 状态 */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
