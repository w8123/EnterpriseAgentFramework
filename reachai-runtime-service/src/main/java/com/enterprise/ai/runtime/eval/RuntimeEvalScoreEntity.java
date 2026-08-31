package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_score")
public class RuntimeEvalScoreEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    private Long experimentItemId;
    private Long variantId;
    private Long datasetItemId;
    private String evaluatorKey;
    private String evaluatorType;
    private Double score;
    private Boolean passed;
    private Double weight;
    private String reason;
    private String metadataJson;
    private LocalDateTime createdAt;
}
