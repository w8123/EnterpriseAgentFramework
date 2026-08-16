package com.enterprise.ai.personalmemory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_personal_memory_index_event")
public class KnowledgePersonalMemoryIndexEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String eventType;
    private Long memoryId;
    private String status;
    private String payloadSha256;
    private LocalDateTime processedAt;
}
