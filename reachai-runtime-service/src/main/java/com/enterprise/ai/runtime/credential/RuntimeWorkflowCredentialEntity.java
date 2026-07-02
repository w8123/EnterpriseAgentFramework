package com.enterprise.ai.runtime.credential;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_workflow_credential")
public class RuntimeWorkflowCredentialEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String credentialRef;

    private String name;

    private String type;

    private Long projectId;

    private String projectCode;

    private String scope;

    private String status;

    private String secretJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
