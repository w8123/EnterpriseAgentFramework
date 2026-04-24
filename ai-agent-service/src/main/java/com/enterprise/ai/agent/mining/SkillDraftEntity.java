package com.enterprise.ai.agent.mining;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_draft")
public class SkillDraftEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String status; // DRAFT / APPROVED / DISCARDED / PUBLISHED / ROLLBACK_CANDIDATE
    private String sourceTraceIds;
    private String specJson;
    private Double confidenceScore;
    private String reviewNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
