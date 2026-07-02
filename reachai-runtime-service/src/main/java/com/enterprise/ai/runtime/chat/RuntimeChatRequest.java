package com.enterprise.ai.runtime.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeChatRequest {

    private String message;

    private String sessionId;

    private String userId;

    private String modelInstanceId;

    private String intentHint;

    private String agentDefinitionId;

    private List<String> roles;

    private String interactionId;

    private Object uiSubmit;
}
