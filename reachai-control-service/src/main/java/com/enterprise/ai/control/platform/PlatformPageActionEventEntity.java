package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_page_action_event")
public class PlatformPageActionEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String sessionId;
    private String tenantId;
    private String appId;
    private String agentId;
    private String nodeId;
    private String actionKey;
    private String title;
    private String argsJson;
    private String targetPageInstanceId;
    private Boolean confirmRequired;
    private String status;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
}
