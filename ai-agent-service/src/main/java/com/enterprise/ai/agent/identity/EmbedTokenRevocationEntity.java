package com.enterprise.ai.agent.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_embed_token_revocation")
public class EmbedTokenRevocationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String jti;

    private String reason;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;
}
