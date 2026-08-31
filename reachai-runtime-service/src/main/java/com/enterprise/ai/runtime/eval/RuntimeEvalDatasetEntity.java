package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_dataset")
public class RuntimeEvalDatasetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String projectCode;
    private String targetType;
    private String targetId;
    private String name;
    private String description;
    private String source;
    private String status;
    private Long currentVersionId;
    private Integer versionCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
