package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_publication")
public class McpPublicationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String state;
    private Long currentRevisionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
