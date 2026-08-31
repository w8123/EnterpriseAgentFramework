package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Runtime-owned immutable Skill binding snapshot for one Agent config version.
 *
 * <p>The Control catalog remains authoritative for package lifecycle. Runtime
 * stores the exact published version identity and digests needed to reproduce
 * an execution without reading Control-owned tables.</p>
 */
@Data
@TableName("runtime_agent_skill_binding")
public class RuntimeAgentSkillBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;
    private Long agentConfigVersionId;
    private Long skillId;
    private Long skillVersionId;
    private String publisher;
    private String standardName;
    private String displayName;
    private String visibility;
    private String projectCode;
    private String version;
    private String sourceSha256;
    private String contentTreeSha256;
    private String sourceRoot;
    private String packageManifestJson;
    private String riskReportJson;
    private Boolean hasScripts;
    private String activationMode;
    private String scriptPolicy;
    private Boolean required;
    private Boolean enabled;
    private Integer priority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
