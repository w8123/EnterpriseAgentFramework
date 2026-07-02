package com.enterprise.ai.agent.capability.catalog.semantic;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_semantic_doc")
public class SemanticDocEntity {

    public static final String LEVEL_PROJECT = "project";
    public static final String LEVEL_MODULE = "module";
    public static final String LEVEL_TOOL = "tool";
    public static final String LEVEL_SCAN_TOOL = "scan_tool";

    public static final String STATUS_GENERATED = "generated";
    public static final String STATUS_EDITED = "edited";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String level;

    private Long projectId;

    private Long moduleId;

    private Long toolId;

    private String contentMd;

    private String promptVersion;

    private String modelName;

    private Integer tokenUsage;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
