package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_managed_artifact")
public class ManagedArtifactEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String artifactId;
    private String executionId;
    private String artifactType;
    private String objectKey;
    private String sha256;
    private Long sizeBytes;
    private String mediaType;
    private String validationStatus;
    private String scanStatus;
    private String rejectionCode;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
