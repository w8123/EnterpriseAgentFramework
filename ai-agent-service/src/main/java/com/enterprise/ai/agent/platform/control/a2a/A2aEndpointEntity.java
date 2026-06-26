package com.enterprise.ai.agent.platform.control.a2a;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("a2a_endpoint")
public class A2aEndpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;

    private String agentKey;

    private Long projectId;

    private String projectCode;

    private String environment;

    private String tenantId;

    /**
     * AgentCard JSON：name / description / version / capabilities / defaultInputModes / defaultOutputModes /
     * skills / examples 等。
     */
    private String cardJson;

    private Boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
