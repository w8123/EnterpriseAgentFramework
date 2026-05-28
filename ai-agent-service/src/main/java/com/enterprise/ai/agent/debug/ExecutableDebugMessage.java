package com.enterprise.ai.agent.debug;

import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutableDebugMessage {

    private String id;

    private String role;

    private String content;

    private String nodeId;

    private String traceId;

    private UiRequestPayload uiRequest;

    private LocalDateTime createdAt;
}
