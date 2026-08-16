package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_tool_result_artifact")
public class RuntimeToolResultArtifactEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String artifactRef;
    private String ownerScopeHash;
    private String sessionScopeHash;
    private String agentName;
    private String traceId;
    private String toolCallId;
    private String toolName;
    private String contentHmacSha256;
    private Integer activeSlot;
    private Integer contentChars;
    private Integer contentBytes;
    private String encryptionKeyId;
    private byte[] encryptionNonce;
    private byte[] contentCiphertext;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
