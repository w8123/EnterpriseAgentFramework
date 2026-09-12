package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** Version 0 is the draft; ordinal -1 records coverage even when a graph has no TOOL nodes. */
@Data
@TableName("runtime_workflow_capability_reference")
public class RuntimeWorkflowReferenceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private Long workflowVersionId;
    private Integer nodeOrdinal;
    private String nodeId;
    private String referenceKey;
    private String state;
    private String warningsJson;
    private LocalDateTime indexedRevision;
}
