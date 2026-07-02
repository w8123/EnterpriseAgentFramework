package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_hit_log")
public class KnowledgeHitLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;

    private Long chunkId;

    private String queryText;

    private String searchMode;

    private Float score;

    private Integer directReturn;

    private String traceId;

    private String userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
