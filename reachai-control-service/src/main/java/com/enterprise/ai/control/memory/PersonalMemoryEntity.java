package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One user-owned long-term memory. Deleting a memory removes the row. */
@Data
@TableName("control_personal_memory")
public class PersonalMemoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String memoryKey;
    private String tenantId;
    private String ownerId;
    private String ownerDomain;
    /** {@code personal} or {@code project:<code>}; see {@link PersonalMemoryScope}. */
    private String scopeKey;
    private String projectCode;
    private Long groupId;
    private String memoryType;
    private String title;
    private String content;
    private String contentSha256;
    /** Hashed semantic key; memories sharing a key replace each other. */
    private String semanticKey;
    private String tagsJson;
    private String sourceType;
    private String sourceAgentId;
    private String sourceSessionId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
