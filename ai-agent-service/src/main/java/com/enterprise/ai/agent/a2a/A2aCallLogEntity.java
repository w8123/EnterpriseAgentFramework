package com.enterprise.ai.agent.a2a;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("a2a_call_log")
public class A2aCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long endpointId;
    // agent_definition.id 在本工程中是 String 形态（IdType.INPUT）；这里保持字符串。

    private String agentKey;

    private String taskId;

    private String method;

    private Boolean success;

    private Long latencyMs;

    private String requestBody;

    private String responseBody;

    private String errorMessage;

    private String traceId;

    private String remoteIp;

    private LocalDateTime createdAt;
}
