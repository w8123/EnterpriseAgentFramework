package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_visibility")
public class ControlMcpVisibilityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetKind;
    private String targetName;
    private Boolean exposed;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
