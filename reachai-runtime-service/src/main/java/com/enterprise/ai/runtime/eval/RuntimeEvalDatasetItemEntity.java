package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_dataset_item")
public class RuntimeEvalDatasetItemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetVersionId;
    private String itemKey;
    private String message;
    private String inputJson;
    private String expectedJson;
    private String metadataJson;
    private String tagsJson;
    private String sourceTraceId;
    private Boolean enabled;
    private Integer ordinalNo;
    private String contentSha256;
    private LocalDateTime createdAt;
}
