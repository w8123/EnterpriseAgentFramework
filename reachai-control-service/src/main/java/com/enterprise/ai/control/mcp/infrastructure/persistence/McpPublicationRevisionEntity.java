package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_publication_revision")
public class McpPublicationRevisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long publicationId;
    private Integer revisionNo;
    private String toolsSnapshotJson;
    private String riskSummaryJson;
    private LocalDateTime publishedAt;
}
