package com.jishi.ai.agent.model.jishi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 极视角会话历史消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JishiHistoryMessage {

    private Long id;

    /** 用户标识（如 user_123）或模型标识（如 lazyllm） */
    @JsonProperty("from_who")
    private String fromWho;

    private String content;

    @JsonProperty("turn_number")
    private Integer turnNumber;

    private List<Object> files;

    @JsonProperty("created_at")
    private Long createdAt;

    @JsonProperty("is_satisfied")
    private Boolean isSatisfied;

    @JsonProperty("user_feedback")
    private String userFeedback;
}
