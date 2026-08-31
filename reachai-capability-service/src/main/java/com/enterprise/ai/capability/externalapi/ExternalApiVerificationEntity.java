package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_external_api_verification")
public class ExternalApiVerificationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entryId;
    private Long versionId;
    private String verificationType;
    private String status;
    private Integer httpStatus;
    private Long latencyMs;
    private String checkedUrl;
    private String evidenceSummary;
    private LocalDateTime checkedAt;
    private LocalDateTime createdAt;
}
