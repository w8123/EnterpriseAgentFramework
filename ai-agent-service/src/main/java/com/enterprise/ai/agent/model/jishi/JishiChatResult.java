package com.enterprise.ai.agent.model.jishi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 极视角对话API返回的核心结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JishiChatResult {

    private String answer;

    @JsonProperty("session_id")
    private String sessionId;
}
