package com.enterprise.ai.model.instance;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModelInstanceTestResponse {

    private boolean success;
    private long latencyMs;
    private String message;
    private String modelInstanceId;
    private String provider;
    private String modelName;
    private String modelType;
    private Integer dimension;
}
