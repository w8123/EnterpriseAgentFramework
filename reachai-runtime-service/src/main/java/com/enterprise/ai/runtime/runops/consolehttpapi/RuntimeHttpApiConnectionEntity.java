package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Runtime-owned transport choice; the Capability contract never contains an execution origin. */
@Data
@TableName("runtime_http_api_connection")
public class RuntimeHttpApiConnectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String qualifiedName;
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
