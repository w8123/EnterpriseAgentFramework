package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_publication_revision")
public class A2aPublicationRevisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long publicationId;
    private Integer revisionNo;
    private Long agentConfigVersionId;
    private String agentVersion;
    private String name;
    private String description;
    private String providerOrganization;
    private String providerUrl;
    private String documentationUrl;
    private String iconUrl;
    private String publicOrigin;
    private String protocolBasePath;
    private String protocolBinding;
    private String protocolVersion;
    private Boolean streamingSupported;
    private Boolean pushNotificationsSupported;
    private Boolean extendedCardSupported;
    private String defaultInputModesJson;
    private String defaultOutputModesJson;
    private String protocolSkillsJson;
    private String securitySchemesJson;
    private String securityRequirementsJson;
    private String agentCardJson;
    private String agentCardSha256;
    private String signatureStatus;
    private String conformanceStatus;
    private String validationSummaryJson;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
