package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("context_evidence")
public class ContextEvidenceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;
    private String evidenceType;
    private String evidenceRef;
    private String evidenceExcerpt;
    private String traceId;
    private BigDecimal confidence;
    private String metadataJson;
    private LocalDateTime createdAt;
}
