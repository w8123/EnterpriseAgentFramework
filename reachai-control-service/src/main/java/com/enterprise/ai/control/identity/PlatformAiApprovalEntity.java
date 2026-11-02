package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** An owner-reviewed action bound to one platform AI connection. */
@Data
@TableName("control_platform_ai_approval")
public class PlatformAiApprovalEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long connectionId;
    private Long ownerUserId;
    private String action;
    private String subject;
    private String requestKey;
    private String requestDigest;
    private String payloadKeyId;
    private String payloadNonce;
    private String payloadCiphertext;
    private String status;
    private String resultCode;
    private LocalDateTime expiresAt;
    private LocalDateTime decidedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
