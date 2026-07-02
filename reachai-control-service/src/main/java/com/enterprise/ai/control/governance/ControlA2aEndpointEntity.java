package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_endpoint")
public class ControlA2aEndpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentId;
    private String agentKey;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private String cardJson;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
