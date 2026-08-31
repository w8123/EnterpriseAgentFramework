package com.enterprise.ai.control.skillmarket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_agent_skill_market_source")
public class SkillMarketSourceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceKey;
    private String displayName;
    private String sourceType;
    private String baseUrl;
    private String repositoryUrl;
    private String trustLevel;
    private String status;
    private Boolean supportsSearch;
    private Boolean supportsImport;
    private Boolean official;
    private String description;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
