package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_experiment_item")
public class RuntimeEvalExperimentItemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    private Long variantId;
    private Long datasetItemId;
    private Integer repeatNo;
    private String status;
    private Boolean runtimeSuccess;
    private Boolean assertionPassed;
    private Double score;
    private Integer elapsedMs;
    private String answer;
    private String traceId;
    private String executionMetadataJson;
    private String evaluatorResultsJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
