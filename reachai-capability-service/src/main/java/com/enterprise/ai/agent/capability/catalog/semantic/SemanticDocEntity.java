package com.enterprise.ai.agent.capability.catalog.semantic;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_semantic_doc")
public class SemanticDocEntity {

    public static final String STATUS_GENERATED = "generated";
    public static final String STATUS_EDITED = "edited";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String subjectType;
    private Long subjectId;
    private String subjectKey;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String basisContractHash;
    private Boolean basisAccepted;

    private String contentMd;

    private String promptVersion;

    private String modelName;

    private Integer tokenUsage;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
