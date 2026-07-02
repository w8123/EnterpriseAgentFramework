package com.enterprise.ai.agent.capability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("composition_definition")
public class CompositionDefinitionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long capabilityModuleId;

    private String capabilityCode;

    private String compositionCode;

    private String name;

    private String qualifiedName;

    private String description;

    private String graphSpecJson;

    private String inputSchemaJson;

    private String outputSchemaJson;

    private String sideEffect;

    private Boolean enabled;

    private Boolean agentVisible;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
