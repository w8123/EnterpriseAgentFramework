package com.enterprise.ai.agent.capability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_asset")
public class ToolAssetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long capabilityModuleId;

    private String capabilityCode;

    private String toolCode;

    private String name;

    private String qualifiedName;

    private String description;

    private String inputSchemaJson;

    private String outputSchemaJson;

    private String executorType;

    private String executorRef;

    private String sideEffect;

    private Boolean enabled;

    private Boolean agentVisible;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
