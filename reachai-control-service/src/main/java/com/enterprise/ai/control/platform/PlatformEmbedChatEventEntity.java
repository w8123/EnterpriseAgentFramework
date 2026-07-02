package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_embed_chat_event")
public class PlatformEmbedChatEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String eventType;
    private String role;
    private String content;
    private String payloadJson;
    private String traceId;
    private LocalDateTime createdAt;
}
