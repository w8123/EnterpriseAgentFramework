package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_file_info")
public class FileInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 每次插入生成的记录身份；主键复用不延续原文件。 */
    @JsonIgnore
    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private String recordGeneration;

    /** 文件业务ID（对外暴露） */
    private String fileId;

    /** 所属知识库ID */
    private Long knowledgeBaseId;

    /** 文件名称 */
    private String fileName;

    /** 文件类型 */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** chunk 数量 */
    private Integer chunkCount;

    /** 状态: 0-处理中 1-已完成 2-失败 */
    private Integer status;

    /** 解析后的原始文本（用于重新解析） */
    private String rawText;

    /** Durable upload original; required for a real provider reparse. */
    private String sourceObjectKey;

    private String sourceContentType;

    private String sourceSha256;

    /** Java Fast or Docling, recorded with the result rather than inferred later. */
    private String parseProvider;

    private String parseProviderVersion;

    /** JSON parse result persisted as an immutable object-storage artifact. */
    private String parseArtifactObjectKey;

    /** Import job that produced this file record. */
    private String importJobId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public String requireRecordGeneration() {
        if (recordGeneration == null || !recordGeneration.matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("文件缺少有效记录身份，请完成数据库升级");
        }
        return recordGeneration;
    }

    public boolean hasRecordIdentity(Long rowId, String generation) {
        return rowId != null && rowId.equals(id) && generation != null
                && generation.matches("[0-9a-f]{32}") && generation.equals(recordGeneration);
    }
}
