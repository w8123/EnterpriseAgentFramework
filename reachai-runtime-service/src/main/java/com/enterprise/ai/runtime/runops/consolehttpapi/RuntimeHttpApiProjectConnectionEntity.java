package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Runtime-owned project + environment default connection inherited by every HTTP API without an override. */
@Data
@TableName("runtime_http_api_project_connection")
public class RuntimeHttpApiProjectConnectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String origin;
    private String authMode;
    private String credentialRef;
    private Long revision;
    private String savedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
