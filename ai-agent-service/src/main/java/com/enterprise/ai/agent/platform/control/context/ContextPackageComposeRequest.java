package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextPackageComposeRequest {

    private ContextQueryRequest query;
    private Integer maxItems;
    private Integer tokenBudget;
}
