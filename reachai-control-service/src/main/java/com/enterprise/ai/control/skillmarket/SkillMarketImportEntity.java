package com.enterprise.ai.control.skillmarket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_agent_skill_market_import")
public class SkillMarketImportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String originFingerprint;
    private String providerKey;
    private String marketplaceSkillId;
    private String repository;
    private String repositoryUrl;
    private String sourceCommitSha;
    private String sourceRoot;
    private String bundleSourceSha256;
    private String selectedSourceSha256;
    private Long skillId;
    private Long skillVersionId;
    private String publisher;
    private String standardName;
    private String version;
    private String visibility;
    private String projectCode;
    private Long importedByUserId;
    private String importedBy;
    private LocalDateTime createdAt;
}
