package com.enterprise.ai.agent.capability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_module")
public class CapabilityModuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String version;

    private String sourceType;

    private String status;

    private Boolean enabled;

    private String manifestJson;

    private String configSchemaJson;

    private String configJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
