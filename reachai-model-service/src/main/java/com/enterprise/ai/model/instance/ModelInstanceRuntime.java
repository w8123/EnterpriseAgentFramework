package com.enterprise.ai.model.instance;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ModelInstanceRuntime {
    private String id;
    private String name;
    private String provider;
    private String modelType;
    private String modelName;
    private String protocol;
    /** 明文连接配置，仅供运行时内部使用 */
    private Map<String, Object> connectionConfig;
    private Map<String, Object> defaultOptions;
}
