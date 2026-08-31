package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_remote_agent_revision")
public class A2aRemoteAgentRevisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long remoteAgentId;
    private Integer revisionNo;
    private String name;
    private String description;
    private String providerOrganization;
    private String providerUrl;
    private String documentationUrl;
    private String iconUrl;
    private String agentVersion;
    private String supportedInterfacesJson;
    private String capabilitiesJson;
    private String defaultInputModesJson;
    private String defaultOutputModesJson;
    private String protocolSkillsJson;
    private String securitySchemesJson;
    private String securityRequirementsJson;
    private String agentCardJson;
    private String agentCardSha256;
    private String signatureStatus;
    private String signingKeyId;
    private String tlsIdentitySha256;
    private String networkEvidenceJson;
    private String httpEtag;
    private String httpLastModified;
    private String reviewStatus;
    private LocalDateTime discoveredAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
