package com.jishi.ai.agent.model.jishi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 极视角会话列表项
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JishiSessionInfo {

    @JsonProperty("session_id")
    private String sessionId;

    private String title;

    private Integer order;
}
