package com.enterprise.ai.agent.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_visibility")
public class McpVisibilityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String targetKind;

    private String targetName;

    /** 是否允许通过 MCP 协议暴露给外部 Client。 */
    private Boolean exposed;

    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
