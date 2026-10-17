package com.enterprise.ai.model.account;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ModelProviderAccountResponse {
    private String id;
    private String name;
    private String provider;
    /** 脱敏后的连接配置 */
    private Map<String, Object> connection;
    private boolean apiKeyConfigured;
    private String status;
    private String remark;
    /** 引用该账号且未归档的模型实例数 */
    private long instanceCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
