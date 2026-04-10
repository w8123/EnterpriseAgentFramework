package com.jishi.ai.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话记忆中的单条消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryMessage {
    private String role;
    private String content;
}
