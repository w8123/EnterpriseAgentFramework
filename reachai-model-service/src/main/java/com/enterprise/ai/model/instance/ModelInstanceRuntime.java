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
    private String endpointType;
    private Map<String, Object> credential;
    private Map<String, Object> defaultOptions;
}
