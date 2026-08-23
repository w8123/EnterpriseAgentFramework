package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunk")
public class Chunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文件ID */
    private String fileId;

    /** 所属知识库ID */
    private Long knowledgeBaseId;

    /** 文本内容 */
    private String content;

    private String title;

    /** chunk 在文件内的序号 */
    private Integer chunkIndex;

    /** Milvus 中的向量 ID */
    private String vectorId;

    /** 关联的 collection 名称 */
    private String collectionName;

    private Integer hitCount;

    private Integer enabled;

    /** Docling element type, e.g. HEADING / TABLE / PARAGRAPH. */
    private String elementType;

    /** Logical heading path at extraction time. */
    private String sectionPath;

    /** JSON source anchor (page / slide / sheet / cell / bounding box). */
    private String sourceLocatorJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
