package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_employee_access")
public class McpEmployeeAccessEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long publicationId;
    private Long projectId;
    private String projectCode;
    private String displayName;
    private Boolean enabled;
    private Boolean writeApprovalRequired;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
