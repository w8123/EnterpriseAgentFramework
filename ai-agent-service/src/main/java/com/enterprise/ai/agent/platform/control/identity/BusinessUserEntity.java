package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_business_user")
public class BusinessUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String globalUserId;

    private String displayName;

    private String email;

    private String mobile;

    private String status;

    private String source;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
