package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_evaluator_suite_version")
public class RuntimeEvalEvaluatorSuiteVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String name;
    private Integer versionNo;
    private String status;
    private String configJson;
    private String fingerprintSha256;
    private String createdBy;
    private LocalDateTime createdAt;
}
