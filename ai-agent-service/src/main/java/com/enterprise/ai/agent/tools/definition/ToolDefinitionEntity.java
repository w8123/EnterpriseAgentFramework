package com.enterprise.ai.agent.tools.definition;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_definition")
public class ToolDefinitionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String parametersJson;

    private String source;

    private String sourceLocation;

    private String httpMethod;

    private String baseUrl;

    private String contextPath;

    private String endpointPath;

    private String requestBodyType;

    private String responseType;

    private Boolean enabled;

    private Boolean agentVisible;

    private Boolean lightweightEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
