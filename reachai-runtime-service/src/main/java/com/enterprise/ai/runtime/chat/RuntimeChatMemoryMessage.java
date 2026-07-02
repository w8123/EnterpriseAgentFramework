package com.enterprise.ai.runtime.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeChatMemoryMessage {

    private String role;

    private String content;
}
