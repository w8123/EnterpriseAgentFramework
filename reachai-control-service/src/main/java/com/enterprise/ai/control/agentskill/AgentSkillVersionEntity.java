package com.enterprise.ai.control.agentskill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_agent_skill_version")
public class AgentSkillVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private String version;
    private String status;
    private String sourceType;
    private String sourceRef;
    private String sourceSha256;
    private String contentTreeSha256;
    private String artifactKey;
    private Long artifactSize;
    private String declaredLicense;
    private String declaredCompatibility;
    private Boolean hasScripts;
    private String frontmatterJson;
    private String packageManifestJson;
    private String validationReportJson;
    private String riskReportJson;
    private String compatibilityReportJson;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
