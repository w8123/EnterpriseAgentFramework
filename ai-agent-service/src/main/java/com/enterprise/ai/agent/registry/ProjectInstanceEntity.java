package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_project_instance")
public class ProjectInstanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String projectCode;

    private String instanceId;

    private String baseUrl;

    private String host;

    private Integer port;

    private String appVersion;

    private String sdkVersion;

    private String status;

    private String metadataJson;

    private LocalDateTime lastHeartbeatAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
