package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_dataset_version")
public class RuntimeEvalDatasetVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Integer versionNo;
    private String status;
    private String fingerprintSha256;
    private Integer itemCount;
    private String changeNote;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
