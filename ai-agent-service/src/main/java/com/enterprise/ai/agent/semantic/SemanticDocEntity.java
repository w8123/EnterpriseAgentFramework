package com.enterprise.ai.agent.semantic;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 三层语义文档实体。
 * 按 (level, project_id, module_id, tool_id) 唯一：重生成时覆盖。
 */
@Data
@TableName("semantic_doc")
public class SemanticDocEntity {

    public static final String LEVEL_PROJECT = "project";
    public static final String LEVEL_MODULE = "module";
    public static final String LEVEL_TOOL = "tool";

    public static final String STATUS_DRAFT = "draft";
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
