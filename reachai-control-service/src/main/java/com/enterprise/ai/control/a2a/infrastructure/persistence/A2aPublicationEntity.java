package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_publication")
public class A2aPublicationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String publicationKey;
    private String agentId;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantScope;
    private String publicHost;
    private Long trustProfileId;
    private Long currentRevisionId;
    private String status;
    @Version
    private Integer version;
    private LocalDateTime publishedAt;
    private LocalDateTime suspendedAt;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
