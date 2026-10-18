package com.enterprise.ai.control.platform;

import lombok.Data;

import java.time.LocalDateTime;

/** 嵌入式对话事件按会话汇总后的投影。 */
@Data
public class PlatformEmbedConversationStats {
    private String sessionId;
    private Integer questionCount;
    private LocalDateTime lastEventAt;
    private String lastQuestion;
}
