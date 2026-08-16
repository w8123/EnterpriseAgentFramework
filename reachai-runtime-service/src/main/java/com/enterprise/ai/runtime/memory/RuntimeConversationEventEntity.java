package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_conversation_event")
public class RuntimeConversationEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationSessionId;
    private Integer sequenceNo;
    private String traceId;
    private String turnId;
    private String eventType;
    private String role;
    private String content;
    private String contentSha256;
    private String payloadJson;
    private LocalDateTime createdAt;
}
