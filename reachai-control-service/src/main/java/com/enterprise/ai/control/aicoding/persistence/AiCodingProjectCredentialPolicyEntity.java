package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_project_policy")
public class AiCodingProjectCredentialPolicyEntity {

    @TableId(value = "project_id", type = IdType.INPUT)
    private Long projectId;

    private Integer handoffActivationTtlHours;

    private Integer taskTokenTtlHours;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
