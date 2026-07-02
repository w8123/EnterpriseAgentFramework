package com.enterprise.ai.runtime.debug;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_executable_debug_session")
public class RuntimeExecutableDebugSessionEntity {

    @TableId
    private String id;

    private String runId;

    private String traceId;

    private String targetType;

    private String status;

    private String currentNodeId;

    private String draftDefinitionJson;

    private String debugOptionsJson;

    private String stateJson;

    private String messagesJson;

    private String stepsJson;

    private String uiRequestJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime expiresAt;
}
