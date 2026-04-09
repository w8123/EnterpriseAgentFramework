package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_info")
public class FileInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
