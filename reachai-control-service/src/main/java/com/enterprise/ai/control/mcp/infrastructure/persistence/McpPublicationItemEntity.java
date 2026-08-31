package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_publication_item")
public class McpPublicationItemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long publicationId;
    private String sourceKind;
    private String sourceRef;
    private String alias;
    private String descriptionOverride;
    private String riskLevelOverride;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
