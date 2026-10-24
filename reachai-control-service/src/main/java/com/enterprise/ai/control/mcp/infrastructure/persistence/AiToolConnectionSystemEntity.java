package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_tool_connection_system")
public class AiToolConnectionSystemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long connectionId;
    private String projectCode;
    private Long publicationId;
    private LocalDateTime createdAt;
}
