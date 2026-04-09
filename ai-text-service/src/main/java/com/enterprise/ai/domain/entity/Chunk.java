package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chunk")
public class Chunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文件ID */
    private String fileId;

    /** 所属知识库ID */
    private Long knowledgeBaseId;

    /** 文本内容 */
    private String content;

    /** chunk 在文件内的序号 */
    private Integer chunkIndex;

    /** Milvus 中的向量 ID */
    private String vectorId;

    /** 关联的 collection 名称 */
    private String collectionName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
