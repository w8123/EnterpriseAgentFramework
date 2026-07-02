package com.enterprise.ai.capability.catalog.mining;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_draft")
public class CapabilitySkillDraftEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String status;
    private String sourceTraceIds;
    private String specJson;
    private Double confidenceScore;
    private String reviewNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
