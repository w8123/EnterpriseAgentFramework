package com.enterprise.ai.model.instance;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ModelInstanceResponse {
    private String id;
    private String name;
    private String provider;
    private String modelType;
    private String modelName;
    private String protocol;
    private String projectCode;
    /** 引用的厂商账号；为空表示连接配置由实例自己保存 */
    private String providerAccountId;
    private String providerAccountName;
    /** 公开连接配置；不含 apiKey 或密钥片段。 */
    private Map<String, Object> connection;
    private boolean apiKeyConfigured;
    private Map<String, Object> defaultOptions;
    private Object paramsSchema;
    private String status;
    private String lastTestStatus;
    private LocalDateTime lastTestAt;
    private Long lastTestLatencyMs;
    private String lastTestError;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
