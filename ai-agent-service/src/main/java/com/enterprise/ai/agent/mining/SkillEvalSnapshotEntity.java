package com.enterprise.ai.agent.mining;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_eval_snapshot")
public class SkillEvalSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String skillName;
    private Integer callCount;
    private Double hitRate;
    private Double replacementRate;
    private Double successRateDiff;
    private Integer tokenSavings;
    private String status;
    private String note;
    private LocalDateTime createTime;
}
