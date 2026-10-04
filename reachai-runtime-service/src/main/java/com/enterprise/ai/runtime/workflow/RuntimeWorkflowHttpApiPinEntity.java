package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Runtime-only release evidence; no origin, credential reference, or secret is persisted here. */
@Data
@TableName("runtime_workflow_http_api_pin")
public class RuntimeWorkflowHttpApiPinEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private Long workflowVersionId;
    private String nodeId;
    private Long apiId;
    private String qualifiedName;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String acceptedContractHash;
    private String sourceSetRevision;
    private Long connectionRevision;
    private String credentialRevision;
    private LocalDateTime createdAt;
}
